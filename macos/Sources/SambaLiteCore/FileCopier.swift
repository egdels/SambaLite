// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Copies one file between this Mac and a share, the same way for the
/// transfer queue and for syncs:
///
/// - The data goes into a part file next to the target
///   (`Names.partName`); only a complete copy is renamed to the real
///   name, so the target is either the old version or the new one,
///   never half of one.
/// - An interrupted copy resumes at the part file's length when the
///   source is still the same version (size and time are in the part
///   file's name).
/// - The source is checked again at the end; a file that changed while
///   it was copied fails with `.fileChanged` instead of arriving mixed.
/// - The part file's size must equal the source's before the rename.
/// - The modification time is carried over in both directions.
public struct FileCopier: Sendable {
    public let remote: any RemoteFileSystem
    /// Bytes per upload write. Each write opens the file on the server,
    /// so this should be large; 4 MiB costs about 3 % in round trips.
    public var chunkSize = 4 * 1024 * 1024

    public init(remote: any RemoteFileSystem) {
        self.remote = remote
    }

    /// Bytes done so far, the total, and the bytes this call actually
    /// moved (a resumed copy starts with done > 0).
    public typealias Progress = @Sendable (_ done: Int64, _ total: Int64) -> Void

    /// What the target may be when the copy replaces it. A sync plans from
    /// listings made at the start of a run; a file edited on the target
    /// side since must not be overwritten unseen.
    public enum Target: Sendable {
        /// Replace whatever is there (the user chose Replace).
        case replace
        /// There must be nothing there.
        case absent
        /// What is there must still be this version.
        case unchanged(size: Int64, modified: Date)
        /// If the name is taken, use the next free "name 2.ext" (downloads).
        case keepBoth
    }

    // MARK: Download

    /// Copies `remotePath` to `local` (see `Target` for what may be there).
    /// Returns the new local file; its `path` is the name it got.
    @discardableResult
    public func download(
        _ remotePath: String, to local: URL, expected: RemoteItem? = nil, target: Target = .replace,
        progress: Progress? = nil
    ) async throws -> LocalItem {
        guard let source = try await remote.item(at: remotePath) else {
            throw Failure(.notFound, "\(remotePath) is no longer on the share")
        }
        if source.isDirectory { throw Failure(.other, "\(remotePath) is a folder") }
        if let expected, !same(expected, source) {
            throw Failure(.fileChanged, "\(remotePath) changed on the share since it was listed")
        }
        let folder = local.deletingLastPathComponent()
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            throw Failure.from(error)
        }
        let part = folder.appendingPathComponent(
            Names.partName(local.lastPathComponent, size: source.size, modified: source.modified))

        var offset: Int64 = 0
        if let existing = LocalFiles.stat(part), !existing.isDirectory, existing.size <= source.size {
            offset = existing.size
        } else {
            try? FileManager.default.removeItem(at: part)
        }
        if !FileManager.default.fileExists(atPath: part.path) {
            guard FileManager.default.createFile(atPath: part.path, contents: nil) else {
                throw Failure(.accessDenied, "Cannot write in \(folder.path)")
            }
        }
        guard LocalFiles.hasRoom(for: source.size - offset, at: folder) else {
            throw Failure(.localFull, "Not enough free space for \(local.lastPathComponent)")
        }

        let handle: FileHandle
        do {
            handle = try FileHandle(forWritingTo: part)
            try handle.truncate(atOffset: UInt64(offset))
            try handle.seek(toOffset: UInt64(offset))
        } catch {
            throw Failure.from(error)
        }
        let written = Locked(offset)
        let total = source.size
        progress?(offset, total)
        do {
            if offset < total {
                let checkEvery: Int64 = 10 * 1024 * 1024
                let lastCheck = Locked(offset)
                try await remote.read(remotePath, from: offset) { data in
                    try handle.write(contentsOf: data)
                    let now = written.update { value -> Int64 in
                        value += Int64(data.count)
                        return value
                    }
                    progress?(now, total)
                    // the disk can fill up while we write: check every 10 MB
                    if now - lastCheck.get() >= checkEvery {
                        lastCheck.set(now)
                        if !LocalFiles.hasRoom(for: total - now, at: folder) {
                            throw Failure(.localFull, "Not enough free space for \(local.lastPathComponent)")
                        }
                    }
                    return now < total
                }
            }
            try handle.synchronize()
            try handle.close()
        } catch {
            try? handle.close()
            throw Failure.from(error)
        }

        let got = LocalFiles.stat(part)?.size ?? -1
        guard got == total else {
            if got > total { try? FileManager.default.removeItem(at: part) }
            throw Failure(.fileChanged, "\(remotePath): got \(got) of \(total) bytes")
        }
        // the same version still on the share? (a writer may have been busy)
        guard let after = try await remote.item(at: remotePath), same(after, source) else {
            try? FileManager.default.removeItem(at: part)
            throw Failure(.fileChanged, "\(remotePath) changed on the share during the download")
        }
        do {
            try FileManager.default.setAttributes([.modificationDate: source.modified], ofItemAtPath: part.path)
        } catch {
            // best effort, like the Android app; the copy itself is good
        }
        if LocalFiles.isFolder(local) {
            throw Failure(.other, "\(local.path) is a folder")
        }
        let final: URL
        do {
            final = try place(part, at: local, target: target)
        } catch let failure as Failure where failure.category == .fileChanged {
            // the plan changes next run; no stray part file in the user's folder
            try? FileManager.default.removeItem(at: part)
            throw failure
        }
        return LocalFiles.stat(final) ?? LocalItem(path: final.lastPathComponent, isDirectory: false, size: total, modified: source.modified)
    }

    /// Gives the finished part file its name. rename(2) replaces atomically;
    /// renamex_np with RENAME_EXCL refuses an existing name atomically.
    private func place(_ part: URL, at local: URL, target: Target) throws -> URL {
        func fail(_ code: Int32) -> Failure {
            Failure.from(POSIXError(POSIXErrorCode(rawValue: code) ?? .EIO))
        }
        switch target {
        case .replace:
            if rename(part.path, local.path) != 0 { throw fail(errno) }
            return local
        case .unchanged(let size, let modified):
            guard let now = LocalFiles.stat(local), !now.isDirectory, now.size == size,
                  abs(now.modified.timeIntervalSince(modified)) < 1
            else {
                throw Failure(.fileChanged, "\(local.lastPathComponent) changed on this Mac during the sync; it is looked at again next run")
            }
            if rename(part.path, local.path) != 0 { throw fail(errno) }
            return local
        case .absent:
            if renamex_np(part.path, local.path, UInt32(RENAME_EXCL)) != 0 {
                if errno == EEXIST {
                    throw Failure(.fileChanged, "\(local.lastPathComponent) appeared on this Mac during the sync; it is looked at again next run")
                }
                throw fail(errno)
            }
            return local
        case .keepBoth:
            let folder = local.deletingLastPathComponent()
            var candidate = local
            var number = 2
            while renamex_np(part.path, candidate.path, UInt32(RENAME_EXCL)) != 0 {
                guard errno == EEXIST, number < 10_000 else { throw fail(errno) }
                candidate = folder.appendingPathComponent(Names.numbered(local.lastPathComponent, number))
                number += 1
            }
            return candidate
        }
    }

    /// Deletes what an unfinished download of `remote` to `local` left.
    public static func discardDownload(to local: URL) {
        let folder = local.deletingLastPathComponent()
        let prefix = local.lastPathComponent + "."
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        for name in names where name.hasPrefix(prefix) && name.hasSuffix(Names.partSuffix) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
        }
    }

    // MARK: Upload

    /// Copies `local` to `remotePath`, replacing a file there. Returns the
    /// new remote entry.
    @discardableResult
    public func upload(
        _ local: URL, to remotePath: String, target: Target = .replace, progress: Progress? = nil
    ) async throws -> RemoteItem {
        guard let source = LocalFiles.stat(local), !source.isDirectory else {
            throw Failure(.notFound, "\(local.path) is no longer there")
        }
        let name = RemotePath.name(remotePath)
        if let reason = Names.invalidSMBNameReason(name) {
            throw Failure(.invalidName, "\(name): \(reason)")
        }
        let folder = RemotePath.parent(remotePath)
        try await remote.createDirectories(folder)
        let part = RemotePath.join(folder, Names.partName(name, size: source.size, modified: source.modified))

        var offset: Int64 = 0
        if let existing = try await remote.item(at: part), !existing.isDirectory {
            if existing.size <= source.size {
                offset = existing.size
            } else {
                try await remote.truncate(part, to: 0)
            }
        }

        let total = source.size
        progress?(offset, total)
        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: local)
            try handle.seek(toOffset: UInt64(offset))
        } catch {
            throw Failure.from(error)
        }
        defer { try? handle.close() }
        if total == 0 {
            try await remote.write(part, at: 0, data: Data())
        }
        while offset < total {
            try Task.checkCancellation()
            let data: Data
            do {
                data = try handle.read(upToCount: chunkSize) ?? Data()
            } catch {
                throw Failure.from(error)
            }
            if data.isEmpty { break }
            try await remote.write(part, at: offset, data: data)
            offset += Int64(data.count)
            progress?(offset, total)
        }

        // the same version still here? (an app may have been saving it)
        guard let after = LocalFiles.stat(local), after.size == source.size,
              abs(after.modified.timeIntervalSince(source.modified)) < 0.001
        else {
            try? await remote.remove(part)
            throw Failure(.fileChanged, "\(local.lastPathComponent) changed during the upload")
        }
        guard let written = try await remote.item(at: part), written.size == total else {
            try? await remote.remove(part)
            throw Failure(.other, "\(name): the server holds a different number of bytes than were sent")
        }
        do {
            try await remote.setModified(part, to: source.modified)
        } catch {
            // best effort: some servers refuse it, the copy itself is good
        }
        try await swapIn(part, as: remotePath, target: target)
        guard let result = try await remote.item(at: remotePath) else {
            throw Failure(.notFound, "\(remotePath) vanished right after the upload")
        }
        return result
    }

    /// SMB's rename does not replace, so an existing target steps aside
    /// first and is deleted only once the new file has its name. If the
    /// second rename fails, the old file gets its name back.
    private func swapIn(_ part: String, as target: String, target check: Target) async throws {
        guard let existing = try await remote.item(at: target) else {
            if case .unchanged = check {
                try? await remote.remove(part)
                throw Failure(.fileChanged, "\(target) was removed from the share during the sync; it is looked at again next run")
            }
            try await remote.move(part, to: target)
            return
        }
        if existing.isDirectory { throw Failure(.other, "\(target) is a folder on the share") }
        switch check {
        case .replace, .keepBoth:
            break
        case .absent:
            try? await remote.remove(part)
            throw Failure(.fileChanged, "\(target) appeared on the share during the sync; it is looked at again next run")
        case .unchanged(let size, let modified):
            if existing.size != size || abs(existing.modified.timeIntervalSince(modified)) >= 1 {
                try? await remote.remove(part)
                throw Failure(.fileChanged, "\(target) changed on the share during the sync; it is looked at again next run")
            }
        }
        let old = target + Names.oldSuffix
        if try await remote.item(at: old) != nil { try await remote.remove(old) }
        try await remote.move(target, to: old)
        do {
            try await remote.move(part, to: target)
        } catch {
            try? await remote.move(old, to: target)
            throw error
        }
        try? await remote.remove(old)
    }

    /// Deletes what an unfinished upload to `remotePath` left.
    public func discardUpload(to remotePath: String) async {
        let folder = RemotePath.parent(remotePath)
        let prefix = RemotePath.name(remotePath) + "."
        guard let items = try? await remote.list(folder) else { return }
        for item in items where item.name.hasPrefix(prefix) && item.name.hasSuffix(Names.partSuffix) {
            try? await remote.remove(item.path)
        }
    }

    private func same(_ a: RemoteItem, _ b: RemoteItem) -> Bool {
        a.size == b.size && abs(a.modified.timeIntervalSince(b.modified)) < 1
    }
}
