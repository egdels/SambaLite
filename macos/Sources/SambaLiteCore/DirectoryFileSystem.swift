// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// A "share" that is a local folder: what the tests sync and transfer
/// against when no Samba server is needed. It keeps SMB's rules where
/// they matter: `move` does not replace, errors come as `Failure`.
/// `failing` lets a test make chosen calls fail.
public final class DirectoryFileSystem: RemoteFileSystem, @unchecked Sendable {
    public let root: URL
    /// Called before every operation with its name and path; a thrown
    /// error is what the operation throws.
    public let failing = Locked<(@Sendable (String, String) throws -> Void)?>(nil)
    /// Bytes per chunk handed out by `read`.
    public var chunkSize = 64 * 1024

    public init(root: URL) {
        self.root = root
    }

    private func url(_ path: String) -> URL {
        path.isEmpty ? root : root.appendingPathComponent(path)
    }

    private func check(_ operation: String, _ path: String) throws {
        try failing.get()?(operation, path)
    }

    private func wrap<T>(_ body: () throws -> T) throws -> T {
        do { return try body() } catch { throw Failure.from(error) }
    }

    public func list(_ path: String) async throws -> [RemoteItem] {
        try check("list", path)
        return try wrap {
            let names = try FileManager.default.contentsOfDirectory(atPath: url(path).path)
            return try names.compactMap { name in try stat(RemotePath.join(path, name)) }
        }
    }

    public func item(at path: String) async throws -> RemoteItem? {
        try check("item", path)
        return try wrap { try stat(path) }
    }

    private func stat(_ path: String) throws -> RemoteItem? {
        let target = url(path)
        guard FileManager.default.fileExists(atPath: target.path) else { return nil }
        let values = try target.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey])
        return RemoteItem(
            path: path, isDirectory: values.isDirectory ?? false, size: Int64(values.fileSize ?? 0),
            modified: values.contentModificationDate ?? Date(timeIntervalSince1970: 0))
    }

    public func read(_ path: String, from offset: Int64, chunk: @escaping @Sendable (Data) throws -> Bool) async throws {
        try check("read", path)
        let handle = try wrap { try FileHandle(forReadingFrom: url(path)) }
        defer { try? handle.close() }
        try wrap { try handle.seek(toOffset: UInt64(offset)) }
        while true {
            try Task.checkCancellation()
            try check("readChunk", path)
            let data = try wrap { try handle.read(upToCount: chunkSize) ?? Data() }
            if data.isEmpty { return }
            if try !chunk(data) { return }
        }
    }

    public func write(_ path: String, at offset: Int64, data: Data) async throws {
        try check("write", path)
        try wrap {
            let target = url(path)
            if !FileManager.default.fileExists(atPath: target.path) {
                guard FileManager.default.createFile(atPath: target.path, contents: nil) else {
                    throw Failure(.notFound, "cannot create \(path)")
                }
            }
            let handle = try FileHandle(forWritingTo: target)
            defer { try? handle.close() }
            if offset == 0 { try handle.truncate(atOffset: 0) }
            try handle.seek(toOffset: UInt64(offset))
            try handle.write(contentsOf: data)
        }
    }

    public func truncate(_ path: String, to size: Int64) async throws {
        try check("truncate", path)
        try wrap {
            let handle = try FileHandle(forWritingTo: url(path))
            defer { try? handle.close() }
            try handle.truncate(atOffset: UInt64(size))
        }
    }

    public func createDirectory(_ path: String) async throws {
        try check("createDirectory", path)
        try wrap {
            if FileManager.default.fileExists(atPath: url(path).path) {
                throw Failure(.other, "\(path) exists")
            }
            try FileManager.default.createDirectory(at: url(path), withIntermediateDirectories: false)
        }
    }

    public func remove(_ path: String) async throws {
        try check("remove", path)
        try wrap { try FileManager.default.removeItem(at: url(path)) }
    }

    public func move(_ from: String, to: String) async throws {
        try check("move", from)
        try wrap {
            if FileManager.default.fileExists(atPath: url(to).path) {
                throw Failure(.other, "\(to) exists")
            }
            try FileManager.default.moveItem(at: url(from), to: url(to))
        }
    }

    public func setModified(_ path: String, to date: Date) async throws {
        try check("setModified", path)
        try wrap { try FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: url(path).path) }
    }

    public func disconnect() async {}
}
