// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Owns the syncs: their settings, baselines, run history and logs on
/// disk, and when they run.
///
/// - A sync with an interval runs when that much time has passed since its
///   last run started; checked every 30 s, after the Mac wakes and when
///   the network comes back.
/// - A run that failed for a passing reason (server away) is tried again
///   sooner: after 2, 4, 8 ... minutes, never later than the interval.
/// - One run per sync at a time, at most two syncs at once, none without
///   network. Stopping cancels the run; what it finished stays done.
/// - Each sync keeps its last 20 runs and the log of its last run (a run
///   that failed before it reached any file keeps the log before it).
public actor SyncCoordinator {
    public static let historyLength = 20
    public static let maxParallel = 2

    public struct Snapshot: Sendable {
        public var configs: [SyncConfig]
        /// Oldest first.
        public var history: [UUID: [SyncRunResult]]
        public var running: [UUID: SyncProgress]
        public var queued: Set<UUID>

        public init(configs: [SyncConfig], history: [UUID: [SyncRunResult]], running: [UUID: SyncProgress], queued: Set<UUID>) {
            self.configs = configs
            self.history = history
            self.running = running
            self.queued = queued
        }
    }

    /// A run ended: the config, its health before and after, the result.
    public typealias RunEnded = @Sendable (SyncConfig, SyncHealth, SyncHealth, SyncRunResult) -> Void

    private let folder: URL?
    private let resolve: TransferQueue.Resolver
    private let makeFileSystem: TransferQueue.FileSystemFactory
    private let now: @Sendable () -> Date
    private var configs: [SyncConfig] = []
    private var history: [UUID: [SyncRunResult]] = [:]
    private var running: [UUID: Task<Void, Never>] = [:]
    private var progress: [UUID: SyncProgress] = [:]
    private var queued: [UUID] = []
    private var observer: (@Sendable (Snapshot) -> Void)?
    private var runEnded: RunEnded?
    private var networkAvailable = true
    private var networkExpensive = false
    private var ticker: Task<Void, Never>?
    private var activity: NSObjectProtocol?
    private var lastPublish = Date.distantPast

    public init(
        folder: URL?, resolve: @escaping TransferQueue.Resolver,
        makeFileSystem: @escaping TransferQueue.FileSystemFactory, now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.folder = folder
        self.resolve = resolve
        self.makeFileSystem = makeFileSystem
        self.now = now
    }

    // MARK: Files

    private var configsFile: JSONFile<[SyncConfig]>? { folder.map { JSONFile($0.appendingPathComponent("syncs.json")) } }
    private var historyFile: JSONFile<[UUID: [SyncRunResult]]>? {
        folder.map { JSONFile($0.appendingPathComponent("sync-history.json")) }
    }
    private func stateFile(_ id: UUID) -> JSONFile<SyncState>? {
        folder.map { JSONFile($0.appendingPathComponent("sync-state/\(id.uuidString).json")) }
    }
    private func logURL(_ id: UUID) -> URL? {
        folder?.appendingPathComponent("sync-logs/\(id.uuidString).log")
    }

    // MARK: Public

    public func setObserver(_ observer: (@Sendable (Snapshot) -> Void)?, runEnded: RunEnded? = nil) {
        self.observer = observer
        self.runEnded = runEnded
        observer?(snapshot())
    }

    public func start(tick: TimeInterval = 30) {
        configs = configsFile?.loadOrSetAside() ?? []
        history = historyFile?.loadOrSetAside() ?? [:]
        publish(force: true)
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                await self?.runDue()
                try? await Task.sleep(nanoseconds: UInt64(tick * 1_000_000_000))
            }
        }
    }

    public func stopAll() {
        ticker?.cancel()
        for task in running.values { task.cancel() }
        queued.removeAll()
    }

    public func snapshot() -> Snapshot {
        Snapshot(configs: configs, history: history, running: progress, queued: Set(queued))
    }

    public func add(_ config: SyncConfig) {
        configs.append(config)
        saveConfigs()
        publish(force: true)
        if config.enabled { enqueue(config.id) }
    }

    /// Saves changed settings. A different folder on either side starts
    /// over with an empty baseline, as the Android app does.
    public func update(_ config: SyncConfig) {
        guard let index = configs.firstIndex(where: { $0.id == config.id }) else { return }
        let old = configs[index]
        configs[index] = config
        if old.localPath != config.localPath || old.remotePath != config.remotePath
            || old.connectionID != config.connectionID
        {
            running[config.id]?.cancel()
            if let file = stateFile(config.id) { try? FileManager.default.removeItem(at: file.url) }
        }
        saveConfigs()
        publish(force: true)
    }

    public func remove(_ id: UUID) {
        running[id]?.cancel()
        queued.removeAll { $0 == id }
        configs.removeAll { $0.id == id }
        history[id] = nil
        if let file = stateFile(id) { try? FileManager.default.removeItem(at: file.url) }
        if let url = logURL(id) { try? FileManager.default.removeItem(at: url) }
        saveConfigs()
        saveHistory()
        publish(force: true)
    }

    /// Removes the syncs of a deleted connection.
    public func removeSyncs(of connectionID: UUID) {
        for config in configs where config.connectionID == connectionID { remove(config.id) }
    }

    public func runNow(_ id: UUID) {
        enqueue(id)
    }

    public func stop(_ id: UUID) {
        queued.removeAll { $0 == id }
        running[id]?.cancel()
        publish(force: true)
    }

    public func setNetworkAvailable(_ available: Bool, expensive: Bool = false) {
        let better = (available && !networkAvailable) || (networkExpensive && !expensive)
        networkAvailable = available
        networkExpensive = expensive
        if better { runDue() }
        startQueued()
    }

    /// After sleep: the timers did not run, catch up.
    public func systemDidWake() {
        runDue()
    }

    public func log(of id: UUID) -> String {
        guard let url = logURL(id), let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return text
    }

    /// The baseline, for "which files does this sync know?".
    public func state(of id: UUID) -> SyncState {
        stateFile(id)?.loadOrSetAside() ?? [:]
    }

    // MARK: Scheduling

    /// When the sync is due next, nil for manual syncs.
    public static func nextRun(of config: SyncConfig, runs: [SyncRunResult]) -> Date? {
        guard config.enabled, config.intervalMinutes > 0 else { return nil }
        let interval = TimeInterval(config.intervalMinutes * 60)
        let counted = runs.filter { $0.status != .stopped }
        guard let last = counted.last else { return .distantPast }
        if last.status == .failed, last.failure?.category.isTransient == true || last.failure?.category == .serverUnreachable {
            let failures = counted.reversed().prefix { $0.status == .failed }.count
            let retry = min(interval, 120 * pow(2, Double(max(0, failures - 1))))
            return last.started.addingTimeInterval(retry)
        }
        return last.started.addingTimeInterval(interval)
    }

    func runDue() {
        let time = now()
        for config in configs where running[config.id] == nil && !queued.contains(config.id)
            && !(networkExpensive && config.avoidExpensiveNetworks)
        {
            if let next = Self.nextRun(of: config, runs: history[config.id] ?? []), next <= time {
                queued.append(config.id)
            }
        }
        startQueued()
    }

    private func enqueue(_ id: UUID) {
        guard running[id] == nil, !queued.contains(id), configs.contains(where: { $0.id == id }) else { return }
        queued.append(id)
        startQueued()
        publish(force: true)
    }

    private func startQueued() {
        guard networkAvailable else { return }
        while running.count < Self.maxParallel, !queued.isEmpty {
            let id = queued.removeFirst()
            guard let config = configs.first(where: { $0.id == id }), running[id] == nil else { continue }
            begin(config)
        }
        updateActivity()
    }

    private func begin(_ config: SyncConfig) {
        let previousRuns = history[config.id] ?? []
        let healthBefore = SyncHealth.of(config, runs: previousRuns, running: false, now: now())
        progress[config.id] = SyncProgress(phase: .listing, checked: 0, expected: previousRuns.last?.checked, current: nil)
        let state = stateFile(config.id)?.loadOrSetAside() ?? [:]
        let stateFile = stateFile(config.id)
        let resolved = resolve(config.connectionID)
        let makeFileSystem = makeFileSystem
        let now = now
        let id = config.id
        running[id] = Task {
            let log = SyncLog()
            let result: SyncRunResult
            if let (connection, password) = resolved {
                let fs = makeFileSystem(connection, password)
                var engine = SyncEngine(config: config, remote: fs)
                engine.now = now
                engine.saveState = { state in try? stateFile?.save(state) }
                engine.progress = { value in Task { await self.report(id, value) } }
                (result, _) = await engine.run(state: state, previous: previousRuns.last, log: log)
                await fs.disconnect()
            } else {
                let time = now()
                result = SyncRunResult(
                    id: UUID(), configID: id, started: time, ended: time, status: .failed, counts: SyncCounts(),
                    failure: Failure(.other, "The sync's connection was deleted."), errors: [], errorCount: 0,
                    checked: 0, mirrorNote: nil)
            }
            self.finish(config, result, log: log, healthBefore: healthBefore)
        }
        publish(force: true)
    }

    private func report(_ id: UUID, _ value: SyncProgress) {
        guard running[id] != nil else { return }
        progress[id] = value
        publish(force: false)
    }

    private func finish(_ config: SyncConfig, _ result: SyncRunResult, log: SyncLog, healthBefore: SyncHealth) {
        running[config.id] = nil
        progress[config.id] = nil
        guard configs.contains(where: { $0.id == config.id }) else {
            startQueued()
            return
        }
        var runs = history[config.id] ?? []
        runs.append(result)
        if runs.count > Self.historyLength { runs.removeFirst(runs.count - Self.historyLength) }
        history[config.id] = runs
        saveHistory()
        let keepOldLog = result.status == .failed && result.checked == 0 && log.count <= 2
        if !keepOldLog, let url = logURL(config.id) {
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? log.text.write(to: url, atomically: true, encoding: .utf8)
        }
        let current = configs.first { $0.id == config.id } ?? config
        let healthAfter = SyncHealth.of(current, runs: runs, running: false, now: now())
        runEnded?(current, healthBefore, healthAfter, result)
        startQueued()
        publish(force: true)
    }

    // MARK: Out

    private func saveConfigs() { try? configsFile?.save(configs) }
    private func saveHistory() { try? historyFile?.save(history) }

    private func publish(force: Bool) {
        let time = now()
        guard force || time.timeIntervalSince(lastPublish) >= 0.3 else { return }
        lastPublish = time
        observer?(snapshot())
        updateActivity()
    }

    private func updateActivity() {
        if !running.isEmpty, activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .idleSystemSleepDisabled], reason: "Syncing folders")
        } else if running.isEmpty, let token = activity {
            ProcessInfo.processInfo.endActivity(token)
            activity = nil
        }
    }

    /// For tests.
    public func waitUntilIdle(timeout: TimeInterval = 10) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if running.isEmpty && queued.isEmpty { return }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }
}

/// Whether a run's outcome is worth a notification: when a sync starts to
/// fail or to have errors, and when it works again. A sync that keeps
/// failing the same way does not notify again (the Android app's rule).
public enum SyncNotice {
    public static func message(for config: SyncConfig, before: SyncHealth, after: SyncHealth) -> (title: String, body: String)? {
        guard config.notify, before != after else { return nil }
        switch after {
        case .failed(let category):
            if case .failed = before { return nil }
            return ("\(config.name) does not sync", category.title + ". " + category.advice)
        case .localFolderMissing:
            return ("\(config.name) does not sync", FailureCategory.localFolderMissing.advice)
        case .withErrors(let count):
            if case .withErrors = before { return nil }
            return ("\(config.name) has \(count == 1 ? "1 file" : "\(count) files") with errors", "Open SambaLite to see which.")
        case .upToDate where before.severity > 0:
            return ("\(config.name) syncs again", "The last run completed without errors.")
        default:
            return nil
        }
    }
}
