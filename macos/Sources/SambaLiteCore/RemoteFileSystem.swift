// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// One entry of a share.
public struct RemoteItem: Sendable, Equatable, Hashable, Codable {
    /// Path inside the share, see `RemotePath`.
    public var path: String
    public var isDirectory: Bool
    public var size: Int64
    public var modified: Date

    public init(path: String, isDirectory: Bool, size: Int64, modified: Date) {
        self.path = path
        self.isDirectory = isDirectory
        self.size = size
        self.modified = modified
    }

    public var name: String { RemotePath.name(path) }
}

/// What the app needs from a share. `SMBFileSystem` talks to a server;
/// tests use `DirectoryFileSystem`, which keeps the "share" in a local
/// folder. Paths are share-relative (`RemotePath`). Every method throws
/// a `Failure`.
public protocol RemoteFileSystem: Sendable {
    /// The entries of a folder, without "." and "..".
    func list(_ path: String) async throws -> [RemoteItem]
    /// The entry at `path`, nil when there is none.
    func item(at path: String) async throws -> RemoteItem?
    /// Reads the file from `offset` to its end in chunks and hands each
    /// to `chunk`; returning false stops the read.
    func read(_ path: String, from offset: Int64, chunk: @escaping @Sendable (Data) throws -> Bool) async throws
    /// Writes `data` at `offset`, keeping what lies before it. At offset
    /// 0 the file is created, or emptied when it exists.
    func write(_ path: String, at offset: Int64, data: Data) async throws
    /// Cuts the file to `size` bytes.
    func truncate(_ path: String, to size: Int64) async throws
    func createDirectory(_ path: String) async throws
    /// Removes a file, or a folder with everything in it.
    func remove(_ path: String) async throws
    /// Renames; fails when `to` exists.
    func move(_ from: String, to: String) async throws
    func setModified(_ path: String, to date: Date) async throws
    /// Closes the connection; the next call opens a new one.
    func disconnect() async
}

extension RemoteFileSystem {
    /// Creates `path` and every missing folder above it.
    public func createDirectories(_ path: String) async throws {
        var current = ""
        for component in RemotePath.components(path) {
            current = RemotePath.join(current, component)
            if let existing = try await item(at: current) {
                if !existing.isDirectory {
                    throw Failure(.other, "\(current) is a file, not a folder")
                }
                continue
            }
            do {
                try await createDirectory(current)
            } catch {
                // someone else (another sync, the user) made it meanwhile
                if try await item(at: current)?.isDirectory != true { throw error }
            }
        }
    }

    /// Every file and folder below `path`, folders before their contents.
    /// A folder that cannot be listed is reported in `skipped` and its
    /// contents are missing from the result; the caller must not take
    /// them for deleted.
    public func walk(
        _ path: String, skip: (String) -> Bool = { Names.isIgnored($0) },
        progress: (@Sendable (Int) -> Void)? = nil
    ) async throws -> (items: [RemoteItem], skipped: [(path: String, failure: Failure)]) {
        var items: [RemoteItem] = []
        var skipped: [(String, Failure)] = []
        var queue = [path]
        while !queue.isEmpty {
            try Task.checkCancellation()
            let folder = queue.removeFirst()
            let children: [RemoteItem]
            do {
                children = try await list(folder)
            } catch {
                let failure = Failure.from(error)
                // Lost connection: the whole walk is worthless, say so.
                if failure.category == .serverUnreachable || failure.category == .loginRejected
                    || folder == path
                {
                    throw failure
                }
                skipped.append((folder, failure))
                continue
            }
            for child in children where !skip(child.name) {
                items.append(child)
                if child.isDirectory { queue.append(child.path) }
            }
            progress?(items.count)
        }
        return (items, skipped)
    }
}
