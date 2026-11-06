// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

public enum TransferKind: String, Codable, Sendable {
    case upload, download
}

public enum TransferStatus: String, Codable, Sendable {
    /// Waiting for its turn, or for `retryAt`, or for the network.
    case pending
    case active
    case completed
    /// Gave up: a permanent error, or the attempts ran out. Retry starts it over.
    case failed
    case cancelled

    public var isFinished: Bool { self == .completed || self == .cancelled }
    public var isOpen: Bool { self == .pending || self == .active }
}

/// One file to copy. A folder becomes one transfer per file, sharing a
/// `batchID`.
public struct Transfer: Codable, Sendable, Identifiable, Equatable {
    public var id: UUID
    public var kind: TransferKind
    public var connectionID: UUID
    /// The file on this Mac (absolute path).
    public var localPath: String
    /// The file on the share (`RemotePath`).
    public var remotePath: String
    public var size: Int64
    public var done: Int64
    public var status: TransferStatus
    /// Attempts that ended in an error.
    public var attempts: Int
    public var error: Failure?
    /// Earliest time of the next automatic attempt.
    public var retryAt: Date?
    public var created: Date
    public var updated: Date
    public var batchID: UUID?

    public init(
        kind: TransferKind, connectionID: UUID, localPath: String, remotePath: String, size: Int64,
        batchID: UUID? = nil, now: Date = Date()
    ) {
        id = UUID()
        self.kind = kind
        self.connectionID = connectionID
        self.localPath = localPath
        self.remotePath = remotePath
        self.size = size
        done = 0
        status = .pending
        attempts = 0
        created = now
        updated = now
        self.batchID = batchID
    }

    public var name: String {
        kind == .download ? (localPath as NSString).lastPathComponent : RemotePath.name(remotePath)
    }

    /// The same file between the same places: the duplicate rule.
    func sameJob(as other: Transfer) -> Bool {
        kind == other.kind && connectionID == other.connectionID
            && Names.key(remotePath) == Names.key(other.remotePath)
            && (kind == .upload || localPath == other.localPath)
    }
}

/// Bytes per second over the last three seconds, like the Android app's
/// `TransferRateTracker`; nil until a second of data is there.
public struct RateTracker: Sendable {
    private var samples: [(time: Date, bytes: Int64)] = []
    public private(set) var lastProgress = Date()
    public static let window: TimeInterval = 3

    public init() {}

    public mutating func add(_ bytes: Int64, at time: Date = Date()) {
        if let last = samples.last, bytes > last.bytes { lastProgress = time }
        if samples.isEmpty { lastProgress = time }
        samples.append((time, bytes))
        samples.removeAll { time.timeIntervalSince($0.time) > Self.window }
    }

    public func bytesPerSecond(at time: Date = Date()) -> Double? {
        guard let first = samples.first, let last = samples.last else { return nil }
        let span = last.time.timeIntervalSince(first.time)
        guard span >= 1 else { return nil }
        if time.timeIntervalSince(lastProgress) > 10 { return 0 }  // stalled
        return Double(last.bytes - first.bytes) / span
    }
}
