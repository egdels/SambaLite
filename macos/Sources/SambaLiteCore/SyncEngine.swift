// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// What a running sync reports while it works.
public struct SyncProgress: Sendable, Equatable {
    public var phase: Phase
    public var checked: Int
    /// What the previous run checked: the goal for a progress bar.
    public var expected: Int?
    public var current: String?

    public enum Phase: String, Sendable {
        case listing, syncing, mirroring
    }
}

/// The log of one run: one line per file and what happened to it, kept
/// for "why was this file not synced?".
public final class SyncLog: @unchecked Sendable {
    public static let maxLines = 50_000
    private let lock = NSLock()
    private var lines: [String] = []
    private let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    public init() {}

    public func add(_ action: String, _ path: String, _ detail: String = "") {
        lock.sync {
            guard lines.count < Self.maxLines else { return }
            let time = formatter.string(from: Date())
            lines.append(detail.isEmpty ? "\(time)\t\(action)\t\(path)" : "\(time)\t\(action)\t\(path)\t\(detail)")
        }
    }

    public var text: String { lock.sync { lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n") } }
    public var count: Int { lock.sync { lines.count } }
}

/// Runs one sync: lists both sides, plans (`SyncPlanner`), carries the
/// plan out file by file with `FileCopier`, and keeps the baseline up to
/// date as it goes, so an interrupted run loses nothing it finished.
public struct SyncEngine: Sendable {
    public let config: SyncConfig
    public let remote: any RemoteFileSystem
    /// Saves the baseline; called every 50 changes and at the end.
    public var saveState: @Sendable (SyncState) -> Void = { _ in }
    public var progress: @Sendable (SyncProgress) -> Void = { _ in }
    public var now: @Sendable () -> Date = { Date() }

    public init(config: SyncConfig, remote: any RemoteFileSystem) {
        self.config = config
        self.remote = remote
    }

    private func absRemote(_ relative: String) -> String {
        relative.isEmpty ? config.remotePath : RemotePath.join(config.remotePath, relative)
    }

    private func absLocal(_ relative: String) -> URL {
        relative.isEmpty ? config.localURL : config.localURL.appendingPathComponent(relative)
    }

    public func run(state initial: SyncState, previous: SyncRunResult?, log: SyncLog) async -> (SyncRunResult, SyncState) {
        let started = now()
        var state = initial
        var counts = SyncCounts()
        var errors: [SyncFileError] = []
        var errorCount = 0
        var checked = 0
        var mirrorNote: String?
        let previousStreaks = Dictionary(
            (previous?.errors ?? []).map { ($0.path, $0.streak) }, uniquingKeysWith: { a, _ in a })

        func fileError(_ path: String, _ failure: Failure) {
            errorCount += 1
            log.add("error", path, "\(failure.category.rawValue): \(failure.message)")
            if errors.count < SyncRunResult.maxErrors {
                errors.append(SyncFileError(
                    path: path, category: failure.category, message: failure.message,
                    streak: (previousStreaks[path] ?? 0) + 1))
            }
        }

        func result(_ status: SyncRunStatus, _ failure: Failure? = nil) -> (SyncRunResult, SyncState) {
            if let failure { log.add("failed", "", "\(failure.category.rawValue): \(failure.message)") }
            saveState(state)
            let run = SyncRunResult(
                id: UUID(), configID: config.id, started: started, ended: now(), status: status, counts: counts,
                failure: failure, errors: errors, errorCount: errorCount, checked: checked, mirrorNote: mirrorNote)
            return (run, state)
        }

        // MARK: list

        progress(SyncProgress(phase: .listing, checked: 0, expected: previous?.checked, current: nil))
        guard LocalFiles.isFolder(config.localURL) else {
            return result(.failed, Failure(.localFolderMissing, "\(config.localPath) is not there. Is its disk connected?"))
        }
        let rootItem: RemoteItem?
        do {
            rootItem = try await remote.item(at: config.remotePath)
        } catch {
            return result(.failed, Failure.from(error))
        }
        guard let rootItem, rootItem.isDirectory else {
            return result(.failed, Failure(.remoteFolderMissing, "\(config.remotePath.isEmpty ? "The share" : config.remotePath) is not on the share."))
        }

        let localListing: (items: [LocalItem], skipped: [(path: String, failure: Failure)], symlinks: [String])
        do {
            localListing = try LocalFiles.walk(config.localURL)
        } catch {
            return result(.failed, Failure.from(error))
        }
        for link in localListing.symlinks { log.add("skipped", link, "symbolic link, not followed") }
        for skipped in localListing.skipped { fileError(skipped.path, Failure(.folderSkipped, skipped.failure.message)) }

        let remoteListing: (items: [RemoteItem], skipped: [(path: String, failure: Failure)])
        do {
            remoteListing = try await remote.walk(config.remotePath)
        } catch {
            if Task.isCancelled { return result(.stopped) }
            return result(.failed, Failure.from(error))
        }
        let prefix = config.remotePath.isEmpty ? "" : config.remotePath + "/"
        func relative(_ path: String) -> String { String(path.dropFirst(prefix.count)) }
        let remoteItems = remoteListing.items.map {
            RemoteItem(path: relative($0.path), isDirectory: $0.isDirectory, size: $0.size, modified: $0.modified)
        }
        let remoteSkipped = remoteListing.skipped.map { relative($0.path) }
        for skipped in remoteListing.skipped { fileError(relative(skipped.path), Failure(.folderSkipped, skipped.failure.message)) }

        let caseSensitive = (try? config.localURL.resourceValues(forKeys: [.volumeSupportsCaseSensitiveNamesKey]))?
            .volumeSupportsCaseSensitiveNames ?? false

        let plan = SyncPlanner.plan(.init(
            direction: config.direction, mirror: config.mirrors, local: localListing.items, remote: remoteItems,
            localSkipped: localListing.skipped.map(\.path), remoteSkipped: remoteSkipped, state: state,
            localCaseSensitive: caseSensitive, now: now()))
        mirrorNote = plan.mirrorNote
        if let note = plan.mirrorNote { log.add("mirror", "", note) }

        // MARK: carry out

        let copier = FileCopier(remote: remote)
        var unsaved = 0
        let trashStamp = String(Int64(now().timeIntervalSince1970 * 1000))

        func changedState() {
            unsaved += 1
            if unsaved >= 50 {
                saveState(state)
                unsaved = 0
            }
        }

        for action in plan.actions {
            if Task.isCancelled { return result(.stopped) }
            var current: String?
            switch action {
            case .upload(let l, _, _): current = l.path
            case .download(let r, _, _): current = r.path
            case .deleteLocal(let p, _), .deleteRemote(let p, _): current = p
            default: break
            }
            if let current {
                progress(SyncProgress(phase: .syncing, checked: checked, expected: previous?.checked, current: current))
            }
            do {
                switch action {
                case .createRemoteFolder(let remotePath, let localPath):
                    try await remote.createDirectories(absRemote(remotePath))
                    counts.folders += 1
                    log.add("created folder", remotePath, "on the share")
                    if let r = try await remote.item(at: absRemote(remotePath)), let l = LocalFiles.stat(absLocal(localPath)) {
                        state[Names.key(remotePath)] = entry(l, localPath, r, remotePath)
                        changedState()
                    }

                case .createLocalFolder(let localPath, let remotePath):
                    try createLocalFolder(absLocal(localPath))
                    counts.folders += 1
                    log.add("created folder", localPath, "on this Mac")
                    if let r = remoteItems.first(where: { $0.path == remotePath }), let l = LocalFiles.stat(absLocal(localPath)) {
                        state[Names.key(remotePath)] = entry(l, localPath, r, remotePath)
                        changedState()
                    }

                case .upload(let l, let remotePath, let conflictCopy):
                    checked += 1
                    if conflictCopy {
                        let copy = RemotePath.join(RemotePath.parent(remotePath), Names.conflictName(RemotePath.name(remotePath), at: now()))
                        try await remote.move(absRemote(remotePath), to: absRemote(copy))
                        counts.conflicts += 1
                        log.add("conflict", remotePath, "the share's version kept as \(RemotePath.name(copy))")
                    }
                    let uploaded = try await copier.upload(absLocal(l.path), to: absRemote(remotePath))
                    counts.uploaded += 1
                    counts.bytes += l.size
                    log.add("uploaded", l.path)
                    state[Names.key(remotePath)] = entry(l, l.path, uploaded, remotePath)
                    changedState()

                case .download(let r, let localPath, let conflictCopy):
                    checked += 1
                    let target = absLocal(localPath)
                    if conflictCopy, FileManager.default.fileExists(atPath: target.path) {
                        let copyName = Names.conflictName(target.lastPathComponent, at: now())
                        try FileManager.default.moveItem(at: target, to: target.deletingLastPathComponent().appendingPathComponent(copyName))
                        counts.conflicts += 1
                        log.add("conflict", localPath, "this Mac's version kept as \(copyName)")
                    }
                    let absolute = RemoteItem(path: absRemote(r.path), isDirectory: false, size: r.size, modified: r.modified)
                    let downloaded = try await copier.download(absolute.path, to: target, expected: absolute)
                    counts.downloaded += 1
                    counts.bytes += r.size
                    log.add("downloaded", r.path)
                    state[Names.key(r.path)] = entry(downloaded, localPath, r, r.path)
                    changedState()

                case .record(let key, let value):
                    state[key] = value
                    changedState()

                case .skip(let path, let reason):
                    if reason == "unchanged" || reason.hasPrefix("the share's") || reason.hasPrefix("this Mac's") {
                        checked += 1
                    }
                    counts.skipped += 1
                    log.add("skipped", path, reason)

                case .error(let path, let failure):
                    checked += 1
                    fileError(path, failure)

                case .deleteRemote(let path, let isDirectory):
                    progress(SyncProgress(phase: .mirroring, checked: checked, expected: previous?.checked, current: path))
                    if try await deleteRemote(path, isDirectory: isDirectory, stamp: trashStamp, log: log) {
                        counts.deleted += 1
                    }
                    state[Names.key(path)] = nil
                    changedState()

                case .deleteLocal(let path, let isDirectory):
                    progress(SyncProgress(phase: .mirroring, checked: checked, expected: previous?.checked, current: path))
                    if try deleteLocal(path, isDirectory: isDirectory, log: log) {
                        counts.deleted += 1
                    }
                    state[Names.key(path)] = nil
                    changedState()

                case .forget(let key):
                    state[key] = nil
                    changedState()
                }
            } catch {
                if Task.isCancelled { return result(.stopped) }
                let failure = Failure.from(error)
                switch failure.category {
                case .cancelled:
                    return result(.stopped)
                case .serverUnreachable, .loginRejected, .localFull, .remoteFull:
                    // everything after this would fail the same way
                    if let current { fileError(current, failure) }
                    return result(.failed, failure)
                default:
                    fileError(current ?? "", failure)
                }
            }
        }
        progress(SyncProgress(phase: .syncing, checked: checked, expected: checked, current: nil))
        return result(errorCount > 0 ? .completedWithErrors : .completed)
    }

    private func entry(_ l: LocalItem, _ localPath: String, _ r: RemoteItem, _ remotePath: String) -> SyncEntry {
        SyncEntry(
            remotePath: remotePath, localPath: localPath, isDirectory: l.isDirectory, localSize: l.size,
            localModified: l.modified, remoteSize: r.size, remoteModified: r.modified, synced: now())
    }

    private func createLocalFolder(_ url: URL) throws {
        do {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        } catch {
            throw Failure.from(error)
        }
    }

    /// Mirror on the share: into `.sambalite-trash/<stamp>/` or deleted.
    /// A folder goes only when nothing but ignored files is left in it.
    private func deleteRemote(_ path: String, isDirectory: Bool, stamp: String, log: SyncLog) async throws -> Bool {
        let absolute = absRemote(path)
        if isDirectory {
            guard try await remote.item(at: absolute) != nil else { return false }
            let rest = try await remote.list(absolute).filter { !Names.isIgnored($0.name) }
            if !rest.isEmpty {
                log.add("mirror kept", path, "folder still holds files SambaLite did not sync")
                return false
            }
            try await remote.remove(absolute)
            log.add("mirror deleted", path, "empty folder")
            return true
        }
        if config.mirrorUseTrash {
            let trashed = RemotePath.join(RemotePath.join(RemotePath.join(config.remotePath, Names.trashFolder), stamp), path)
            do {
                try await remote.createDirectories(RemotePath.parent(trashed))
                try await remote.move(absolute, to: trashed)
                log.add("mirror trashed", path, "moved to \(Names.trashFolder)/\(stamp)")
                return true
            } catch {
                log.add("warning", path, "could not move into the trash folder, deleting: \(Failure.from(error).message)")
            }
        }
        try await remote.remove(absolute)
        log.add("mirror deleted", path)
        return true
    }

    /// Mirror on this Mac: to the Trash, or deleted.
    private func deleteLocal(_ path: String, isDirectory: Bool, log: SyncLog) throws -> Bool {
        let url = absLocal(path)
        guard FileManager.default.fileExists(atPath: url.path) else { return false }
        do {
            if isDirectory {
                let rest = try FileManager.default.contentsOfDirectory(atPath: url.path).filter { !Names.isIgnored($0) }
                if !rest.isEmpty {
                    log.add("mirror kept", path, "folder still holds files SambaLite did not sync")
                    return false
                }
            }
            if config.mirrorUseTrash {
                try LocalFiles.trashOrDelete(url)
                log.add("mirror trashed", path, "moved to the Trash")
            } else {
                try FileManager.default.removeItem(at: url)
                log.add("mirror deleted", path)
            }
            return true
        } catch {
            throw Failure.from(error)
        }
    }
}
