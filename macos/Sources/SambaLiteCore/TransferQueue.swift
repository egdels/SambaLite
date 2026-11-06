// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// The persistent queue of uploads and downloads.
///
/// - Kept in `transfers.json`; what was active when the app ended is
///   pending again at the next start and resumes at its part file.
/// - `maxParallel` workers; each keeps one SMB session per connection
///   and works through the queue in order.
/// - A transient error (server gone, file busy) is tried again after
///   5 s, 15 s, 1 min, 5 min; after `maxAttempts` the transfer fails and
///   waits for Retry. While the Mac has no network, attempts are not
///   counted; the queue waits and goes on when the network is back.
/// - Cancelled and finished transfers leave no part files behind.
/// - While anything runs, idle sleep is held off.
public actor TransferQueue {
    public static let maxAttempts = 5
    public static let defaultBackoff: [TimeInterval] = [5, 15, 60, 300]
    /// Completed and cancelled entries are dropped after a week.
    static let keepFinished: TimeInterval = 7 * 24 * 3600

    public struct Snapshot: Sendable {
        public var transfers: [Transfer]
        /// Bytes per second of the transfers running now.
        public var speeds: [UUID: Double]

        public init(transfers: [Transfer], speeds: [UUID: Double]) {
            self.transfers = transfers
            self.speeds = speeds
        }
    }

    /// Finds the connection and its password; nil when it was deleted.
    public typealias Resolver = @Sendable (UUID) -> (Connection, String)?
    public typealias FileSystemFactory = @Sendable (Connection, String) -> any RemoteFileSystem

    public let maxParallel: Int
    private let backoff: [TimeInterval]
    private var transfers: [Transfer] = []
    private let file: JSONFile<[Transfer]>?
    private let resolve: Resolver
    private let makeFileSystem: FileSystemFactory
    private var workers = 0
    private var running: [UUID: Task<Result<Void, Failure>, Never>] = [:]
    private var userCancelled: Set<UUID> = []
    private var rates: [UUID: RateTracker] = [:]
    private var observer: (@Sendable (Snapshot) -> Void)?
    private var wakeTask: Task<Void, Never>?
    private var networkAvailable = true
    private var lastSave = Date.distantPast
    private var lastPublish = Date.distantPast
    private var activity: NSObjectProtocol?
    private var started = false
    private let now: @Sendable () -> Date

    public init(
        file: JSONFile<[Transfer]>?, maxParallel: Int = 2, backoff: [TimeInterval] = defaultBackoff,
        resolve: @escaping Resolver,
        makeFileSystem: @escaping FileSystemFactory, now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.file = file
        self.maxParallel = maxParallel
        self.backoff = backoff
        self.resolve = resolve
        self.makeFileSystem = makeFileSystem
        self.now = now
    }

    // MARK: Public

    public func setObserver(_ observer: (@Sendable (Snapshot) -> Void)?) {
        self.observer = observer
        observer?(snapshot())
    }

    /// Loads the saved queue and starts working on it.
    public func start() {
        guard !started else { return }
        started = true
        transfers = file?.loadOrSetAside() ?? []
        for index in transfers.indices where transfers[index].status == .active {
            transfers[index].status = .pending
        }
        let cutoff = now().addingTimeInterval(-Self.keepFinished)
        transfers.removeAll { $0.status.isFinished && $0.updated < cutoff }
        changed(save: true)
        pump()
    }

    public func snapshot() -> Snapshot {
        var speeds: [UUID: Double] = [:]
        for (id, rate) in rates { if let speed = rate.bytesPerSecond(at: now()) { speeds[id] = speed } }
        return Snapshot(transfers: transfers, speeds: speeds)
    }

    /// Adds transfers; returns the ones left out because the same job is
    /// still open.
    @discardableResult
    public func enqueue(_ new: [Transfer]) -> [Transfer] {
        var duplicates: [Transfer] = []
        for transfer in new {
            if transfers.contains(where: { ($0.status.isOpen || $0.status == .failed) && $0.sameJob(as: transfer) }) {
                duplicates.append(transfer)
            } else {
                transfers.append(transfer)
            }
        }
        changed(save: true)
        pump()
        return duplicates
    }

    /// Stops transfers; their part files are deleted.
    public func cancel(_ ids: Set<UUID>) async {
        var discard: [Transfer] = []
        for index in transfers.indices where ids.contains(transfers[index].id) {
            switch transfers[index].status {
            case .active:
                userCancelled.insert(transfers[index].id)
                running[transfers[index].id]?.cancel()
            case .pending, .failed:
                transfers[index].status = .cancelled
                transfers[index].updated = now()
                discard.append(transfers[index])
            default:
                break
            }
        }
        changed(save: true)
        for transfer in discard { await discardPart(of: transfer) }
    }

    /// Starts failed transfers over, with their attempts reset.
    public func retry(_ ids: Set<UUID>) {
        for index in transfers.indices where ids.contains(transfers[index].id) {
            guard transfers[index].status == .failed || transfers[index].status == .cancelled else { continue }
            transfers[index].status = .pending
            transfers[index].attempts = 0
            transfers[index].retryAt = nil
            transfers[index].error = nil
            transfers[index].updated = now()
        }
        changed(save: true)
        pump()
    }

    /// Drops entries from the list; unfinished ones are cancelled first.
    public func remove(_ ids: Set<UUID>) async {
        await cancel(ids.filter { id in transfers.contains { $0.id == id && $0.status != .completed } })
        // an active one ends a moment later; it is removed when it does
        transfers.removeAll { ids.contains($0.id) && $0.status != .active }
        changed(save: true)
    }

    public func clearFinished() {
        transfers.removeAll { $0.status.isFinished }
        changed(save: true)
    }

    /// The app learnt that the network went away or came back.
    public func setNetworkAvailable(_ available: Bool) {
        let cameBack = available && !networkAvailable
        networkAvailable = available
        if cameBack {
            for index in transfers.indices
            where transfers[index].status == .pending && transfers[index].error?.category == .serverUnreachable {
                transfers[index].retryAt = nil
            }
            changed(save: false)
        }
        pump()
    }

    /// Saves where every transfer stands; for the app's quit.
    public func flush() {
        save()
    }

    // MARK: Scheduling

    private func isEligible(_ transfer: Transfer, at time: Date) -> Bool {
        guard transfer.status == .pending, networkAvailable else { return false }
        if let retryAt = transfer.retryAt, retryAt > time { return false }
        return true
    }

    private func pump() {
        guard started else { return }
        let time = now()
        let waiting = transfers.filter { isEligible($0, at: time) }.count
        while workers < maxParallel && workers < waiting {
            workers += 1
            Task { await self.work() }
        }
        scheduleWake()
        updateActivity()
    }

    /// Wakes the queue when the earliest backoff ends.
    private func scheduleWake() {
        wakeTask?.cancel()
        guard networkAvailable,
              let next = transfers.filter({ $0.status == .pending }).compactMap(\.retryAt).min(),
              next > now()
        else { return }
        let delay = next.timeIntervalSince(now())
        wakeTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000) + 50_000_000)
            if !Task.isCancelled { self.pump() }
        }
    }

    private func next() -> Int? {
        let time = now()
        return transfers.firstIndex { isEligible($0, at: time) }
    }

    private func work() async {
        var session: (id: UUID, fs: any RemoteFileSystem)?
        while let index = next() {
            let transfer = transfers[index]
            guard let (connection, password) = resolve(transfer.connectionID) else {
                transfers[index].status = .failed
                transfers[index].error = Failure(.other, "The connection was deleted.")
                transfers[index].updated = now()
                changed(save: true)
                continue
            }
            if session?.id != connection.id {
                if let old = session { await old.fs.disconnect() }
                session = (connection.id, makeFileSystem(connection, password))
            }
            let fs = session!.fs
            transfers[index].status = .active
            transfers[index].updated = now()
            rates[transfer.id] = RateTracker()
            changed(save: true)

            let task = Task { await Self.execute(transfer, fs: fs, queue: self) }
            running[transfer.id] = task
            let result = await task.value
            running[transfer.id] = nil
            rates[transfer.id] = nil
            await finish(transfer.id, result, fs: fs)
        }
        if let old = session { await old.fs.disconnect() }
        workers -= 1
        updateActivity()
        scheduleWake()
        changed(save: true, force: true)
    }

    private static func execute(_ transfer: Transfer, fs: any RemoteFileSystem, queue: TransferQueue) async
        -> Result<Void, Failure>
    {
        let copier = FileCopier(remote: fs)
        let id = transfer.id
        let lastReport = Locked(Date.distantPast)
        let progress: FileCopier.Progress = { done, total in
            let time = Date()
            let due = lastReport.update { last -> Bool in
                guard time.timeIntervalSince(last) >= 0.25 || done == total else { return false }
                last = time
                return true
            }
            if due { Task { await queue.progress(id, done: done, total: total) } }
        }
        do {
            let local = URL(fileURLWithPath: transfer.localPath)
            switch transfer.kind {
            case .download:
                try await copier.download(transfer.remotePath, to: local, progress: progress)
            case .upload:
                try await copier.upload(local, to: transfer.remotePath, progress: progress)
            }
            return .success(())
        } catch {
            return .failure(Task.isCancelled ? Failure(.cancelled, "Cancelled") : Failure.from(error))
        }
    }

    private func progress(_ id: UUID, done: Int64, total: Int64) {
        guard let index = transfers.firstIndex(where: { $0.id == id }), transfers[index].status == .active else { return }
        transfers[index].done = done
        transfers[index].size = total
        rates[id]?.add(done, at: now())
        changed(save: false)
    }

    private func finish(_ id: UUID, _ result: Result<Void, Failure>, fs: any RemoteFileSystem) async {
        guard let index = transfers.firstIndex(where: { $0.id == id }) else { return }
        let time = now()
        transfers[index].updated = time
        switch result {
        case .success:
            userCancelled.remove(id)  // finished before the cancel reached it
            transfers[index].status = .completed
            transfers[index].done = transfers[index].size
            transfers[index].error = nil
            transfers[index].retryAt = nil
        case .failure(let failure):
            if userCancelled.remove(id) != nil || failure.category == .cancelled {
                transfers[index].status = .cancelled
                transfers[index].error = nil
                let transfer = transfers[index]
                if transfer.kind == .download {
                    FileCopier.discardDownload(to: URL(fileURLWithPath: transfer.localPath))
                } else {
                    await FileCopier(remote: fs).discardUpload(to: transfer.remotePath)
                }
            } else if failure.category == .serverUnreachable && !networkAvailable {
                // not the transfer's fault: wait for the network, uncounted
                transfers[index].status = .pending
                transfers[index].error = failure
            } else if failure.category.isTransient && transfers[index].attempts + 1 < Self.maxAttempts {
                let attempt = transfers[index].attempts
                transfers[index].attempts += 1
                transfers[index].status = .pending
                transfers[index].error = failure
                transfers[index].retryAt = time.addingTimeInterval(backoff[min(attempt, backoff.count - 1)])
            } else {
                transfers[index].attempts += 1
                transfers[index].status = .failed
                transfers[index].error = failure
                transfers[index].retryAt = nil
            }
        }
        changed(save: true, force: true)
    }

    private func discardPart(of transfer: Transfer) async {
        if transfer.kind == .download {
            FileCopier.discardDownload(to: URL(fileURLWithPath: transfer.localPath))
        } else if let (connection, password) = resolve(transfer.connectionID) {
            let fs = makeFileSystem(connection, password)
            await FileCopier(remote: fs).discardUpload(to: transfer.remotePath)
            await fs.disconnect()
        }
    }

    // MARK: State out

    private func changed(save shouldSave: Bool, force: Bool = false) {
        let time = now()
        if shouldSave || time.timeIntervalSince(lastSave) > 2 { save() }
        if force || shouldSave || time.timeIntervalSince(lastPublish) >= 0.2 {
            lastPublish = time
            observer?(snapshot())
        }
    }

    private func tick() {
        observer?(snapshot())
    }

    private func save() {
        lastSave = now()
        try? file?.save(transfers)
    }

    /// While something runs, the observer hears from the queue every second
    /// even without progress, so a stalled transfer shows as stalled.
    private var ticker: Task<Void, Never>?

    private func updateActivity() {
        if workers > 0, ticker == nil {
            ticker = Task {
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    self.tick()
                }
            }
        } else if workers == 0 {
            ticker?.cancel()
            ticker = nil
        }
        if workers > 0, activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .idleSystemSleepDisabled], reason: "Transferring files")
        } else if workers == 0, let token = activity {
            ProcessInfo.processInfo.endActivity(token)
            activity = nil
        }
    }

    /// For tests: waits until nothing runs and nothing is due.
    public func waitUntilIdle(timeout: TimeInterval = 10) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let time = now()
            let due = transfers.contains { $0.status == .pending && (networkAvailable || isEligible($0, at: time)) }
            if workers == 0 && !due { return }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }
}
