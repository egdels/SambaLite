// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

public enum SyncDirection: String, Codable, Sendable, CaseIterable {
    /// This Mac to the share.
    case upload
    /// The share to this Mac.
    case download
    case bidirectional

    public var title: String {
        switch self {
        case .upload: "Mac → Share"
        case .download: "Share → Mac"
        case .bidirectional: "Both Ways"
        }
    }

    var sendsUp: Bool { self != .download }
    var sendsDown: Bool { self != .upload }
}

/// A folder on this Mac kept in step with a folder on a share.
public struct SyncConfig: Codable, Sendable, Identifiable, Equatable {
    public var id: UUID
    public var connectionID: UUID
    /// Absolute path of the folder on this Mac.
    public var localPath: String
    /// Folder in the share (`RemotePath`); "" is the share's root.
    public var remotePath: String
    public var direction: SyncDirection
    /// 0 = only when started by hand.
    public var intervalMinutes: Int
    /// One-way only: what disappears from the source is removed from the
    /// target too (with the safeguards of `SyncPlanner`).
    public var mirror: Bool
    /// Mirror moves instead of deleting: to `.sambalite-trash` on the
    /// share, to the Trash on this Mac.
    public var mirrorUseTrash: Bool
    /// false = paused.
    public var enabled: Bool
    /// Notify when the sync starts or stops failing.
    public var notify: Bool

    public init(
        id: UUID = UUID(), connectionID: UUID, localPath: String, remotePath: String,
        direction: SyncDirection = .bidirectional, intervalMinutes: Int = 60, mirror: Bool = false,
        mirrorUseTrash: Bool = true, enabled: Bool = true, notify: Bool = true
    ) {
        self.id = id
        self.connectionID = connectionID
        self.localPath = localPath
        self.remotePath = remotePath
        self.direction = direction
        self.intervalMinutes = intervalMinutes
        self.mirror = mirror
        self.mirrorUseTrash = mirrorUseTrash
        self.enabled = enabled
        self.notify = notify
    }

    public static let intervals = [0, 15, 30, 60, 360, 720, 1440]

    public var name: String {
        let local = (localPath as NSString).lastPathComponent
        return local.isEmpty ? localPath : local
    }

    /// Mirror counts only one way.
    public var mirrors: Bool { mirror && direction != .bidirectional }

    public var localURL: URL { URL(fileURLWithPath: localPath, isDirectory: true) }
}

/// What both sides looked like after the last successful step for one
/// path: the baseline that tells "changed here" from "changed there" and
/// "deleted" from "never synced".
public struct SyncEntry: Codable, Sendable, Equatable {
    /// The real path on the share, relative to the sync's remote folder.
    public var remotePath: String
    /// The real path on this Mac, relative to the local folder.
    public var localPath: String
    public var isDirectory: Bool
    public var localSize: Int64
    public var localModified: Date
    public var remoteSize: Int64
    public var remoteModified: Date
    public var synced: Date

    public init(
        remotePath: String, localPath: String, isDirectory: Bool, localSize: Int64, localModified: Date,
        remoteSize: Int64, remoteModified: Date, synced: Date
    ) {
        self.remotePath = remotePath
        self.localPath = localPath
        self.isDirectory = isDirectory
        self.localSize = localSize
        self.localModified = localModified
        self.remoteSize = remoteSize
        self.remoteModified = remoteModified
        self.synced = synced
    }

    init(local: LocalItem, remote: RemoteItem, synced: Date) {
        self.init(
            remotePath: remote.path, localPath: local.path, isDirectory: local.isDirectory,
            localSize: local.size, localModified: local.modified, remoteSize: remote.size,
            remoteModified: remote.modified, synced: synced)
    }
}

/// Baselines by `Names.key` of the relative path.
public typealias SyncState = [String: SyncEntry]

public enum SyncRunStatus: String, Codable, Sendable {
    case completed
    case completedWithErrors
    case stopped
    case failed
}

public struct SyncFileError: Codable, Sendable, Equatable {
    public var path: String
    public var category: FailureCategory
    public var message: String
    /// Runs in a row this path failed.
    public var streak: Int
}

public struct SyncCounts: Codable, Sendable, Equatable {
    public var uploaded = 0
    public var downloaded = 0
    public var skipped = 0
    public var deleted = 0
    public var conflicts = 0
    public var folders = 0
    public var bytes: Int64 = 0

    public init() {}

    public var transferred: Int { uploaded + downloaded }
}

/// One run of one sync.
public struct SyncRunResult: Codable, Sendable, Identifiable, Equatable {
    public var id: UUID
    public var configID: UUID
    public var started: Date
    public var ended: Date
    public var status: SyncRunStatus
    public var counts: SyncCounts
    /// What ended a failed run.
    public var failure: Failure?
    /// The first 50 file errors.
    public var errors: [SyncFileError]
    public var errorCount: Int
    /// Files looked at; the next run's progress counts toward it.
    public var checked: Int
    /// Mirror skipped or aborted, and why.
    public var mirrorNote: String?

    public static let maxErrors = 50
}

/// The state a sync shows in the monitor.
public enum SyncHealth: Equatable, Sendable {
    case paused
    case running
    case neverRun
    case upToDate
    case withErrors(Int)
    case failed(FailureCategory)
    case overdue
    case localFolderMissing

    public var title: String {
        switch self {
        case .paused: "Paused"
        case .running: "Running"
        case .neverRun: "Not run yet"
        case .upToDate: "Up to date"
        case .withErrors(let n): n == 1 ? "1 file with errors" : "\(n) files with errors"
        case .failed(let category): "Failed: \(category.title)"
        case .overdue: "Overdue"
        case .localFolderMissing: "Local folder missing"
        }
    }

    /// 0 fine, 1 warning, 2 problem: the monitor's colour and sort order.
    public var severity: Int {
        switch self {
        case .paused, .running, .neverRun, .upToDate: 0
        case .withErrors, .overdue: 1
        case .failed, .localFolderMissing: 2
        }
    }

    /// From the config, the runs (newest last) and the clock. A sync with
    /// an interval that has not completed for twice its interval plus an
    /// hour is overdue, as in the Android app.
    public static func of(
        _ config: SyncConfig, runs: [SyncRunResult], running: Bool, now: Date = Date(),
        localExists: Bool? = nil
    ) -> SyncHealth {
        if running { return .running }
        if !config.enabled { return .paused }
        if localExists == false { return .localFolderMissing }
        guard let last = runs.last(where: { $0.status != .stopped }) else { return .neverRun }
        switch last.status {
        case .failed:
            if last.failure?.category == .localFolderMissing { return .localFolderMissing }
            return .failed(last.failure?.category ?? .other)
        case .completedWithErrors:
            if isOverdue(config, runs: runs, now: now) { return .overdue }
            return .withErrors(last.errorCount)
        default:
            if isOverdue(config, runs: runs, now: now) { return .overdue }
            return .upToDate
        }
    }

    static func isOverdue(_ config: SyncConfig, runs: [SyncRunResult], now: Date) -> Bool {
        guard config.intervalMinutes > 0 else { return false }
        let lastGood = runs.last { $0.status == .completed || $0.status == .completedWithErrors }?.ended
        guard let lastGood else { return false }
        let limit = 2 * Double(config.intervalMinutes) * 60 + 3600
        return now.timeIntervalSince(lastGood) > limit
    }
}
