// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AMSMB2
import Foundation

/// A share on an SMB server, through AMSMB2 (libsmb2). One instance holds
/// one SMB session; libsmb2 sends one request at a time on it, so the
/// transfer queue and each sync open their own instance and the browser
/// keeps one of its own.
///
/// The session is opened on the first call and opened again when the
/// server dropped it (sleep, Wi-Fi change, server restart): every call
/// that is safe to repeat is tried a second time on a fresh session
/// before its error goes up.
public final class SMBFileSystem: RemoteFileSystem, @unchecked Sendable {
    public let connection: Connection
    private let password: String
    /// Seconds a single request may take before the session counts as
    /// dead. AMSMB2's default is 60; a NAS waking its disks takes up to
    /// about 15.
    private let timeout: TimeInterval
    private let lock = NSLock()
    private var manager: SMB2Manager?
    private var connected = false

    public init(connection: Connection, password: String, timeout: TimeInterval = 30) {
        self.connection = connection
        self.password = password
        self.timeout = timeout
    }

    // MARK: Session

    private func makeManager() throws -> SMB2Manager {
        guard let url = connection.serverURL else {
            throw Failure(.other, "Invalid server address \(connection.server)")
        }
        let credential: URLCredential? =
            connection.guest
            ? nil : URLCredential(user: connection.username, password: password, persistence: .none)
        guard let manager = SMB2Manager(url: url, domain: connection.domain, credential: credential) else {
            throw Failure(.other, "Invalid server address \(connection.server)")
        }
        manager.timeout = timeout
        return manager
    }

    private func session() async throws -> SMB2Manager {
        let existing: SMB2Manager? = lock.sync { connected ? manager : nil }
        if let existing { return existing }
        let fresh = try lock.sync { () throws -> SMB2Manager in
            if let manager { return manager }
            let made = try makeManager()
            manager = made
            return made
        }
        do {
            try await fresh.connectShare(name: connection.share, encrypted: connection.encryptData)
        } catch {
            throw await connectFailure(error)
        }
        lock.sync { connected = true }
        return fresh
    }

    /// libsmb2 reports a wrong password and a closed port alike (EPERM,
    /// no text), so a failed connect is followed by a plain TCP probe:
    /// a server that answers on the port has turned the login down.
    func connectFailure(_ error: any Error) async -> Failure {
        let failure = Failure.from(error)
        switch failure.category {
        case .notFound:
            return Failure(.notFound, "The share \(connection.share) does not exist on \(connection.server).")
        case .cancelled, .loginRejected:
            return failure
        default:
            break
        }
        let reachable = await TCPProbe.canConnect(host: connection.server, port: connection.port, timeout: 5)
        if !reachable {
            return Failure(
                .serverUnreachable, "\(connection.server) does not answer on port \(connection.port).")
        }
        if failure.category == .serverUnreachable, (error as NSError).code == Int(POSIXErrorCode.ETIMEDOUT.rawValue) {
            return failure
        }
        return Failure(
            .loginRejected,
            connection.encryptData
                ? "The server rejected the login. Check username and password, and whether the server supports encryption."
                : "The server rejected the username or password.")
    }

    private func reset() async {
        let old: SMB2Manager? = lock.sync {
            let old = manager
            manager = nil
            connected = false
            return old
        }
        try? await old?.disconnectShare(gracefully: false)
    }

    public func disconnect() async {
        let old: SMB2Manager? = lock.sync {
            let old = manager
            manager = nil
            connected = false
            return old
        }
        try? await old?.disconnectShare(gracefully: true)
    }

    /// Runs `body` on the session; a dropped session is reopened once.
    private func run<T>(retry: Bool = true, _ body: (SMB2Manager) async throws -> T) async throws -> T {
        let manager = try await session()
        do {
            return try await body(manager)
        } catch {
            let failure = Failure.from(error)
            guard failure.category == .serverUnreachable else { throw failure }
            // a session that timed out is never used again (see the patch in
            // Vendor/AMSMB2): the next call opens a fresh one
            await reset()
            guard retry else { throw failure }
            let again = try await session()
            do {
                return try await body(again)
            } catch {
                let failure = Failure.from(error)
                if failure.category == .serverUnreachable { await reset() }
                throw failure
            }
        }
    }

    // MARK: RemoteFileSystem

    public func list(_ path: String) async throws -> [RemoteItem] {
        try await run { manager in
            let entries = try await manager.contentsOfDirectory(atPath: path)
            return entries.compactMap { entry -> RemoteItem? in
                guard let name = entry[.nameKey] as? String, name != ".", name != ".." else { return nil }
                return Self.item(RemotePath.join(path, name), entry)
            }
        }
    }

    public func item(at path: String) async throws -> RemoteItem? {
        if path.isEmpty {
            _ = try await session()
            return RemoteItem(path: "", isDirectory: true, size: 0, modified: Date(timeIntervalSince1970: 0))
        }
        do {
            return try await run { manager in
                Self.item(path, try await manager.attributesOfItem(atPath: path))
            }
        } catch let failure as Failure where failure.category == .notFound {
            return nil
        }
    }

    public func read(
        _ path: String, from offset: Int64, chunk: @escaping @Sendable (Data) throws -> Bool
    ) async throws {
        // Not repeated on a new session here: chunks already handed over
        // would be handed over twice. The caller resumes from its offset.
        try await run(retry: false) { manager in
            let box = ErrorBox()
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                    manager.contents(
                        atPath: path, offset: offset,
                        fetchedData: { _, _, data in
                            if box.cancelled { return false }
                            do {
                                return try chunk(data)
                            } catch {
                                box.error = error
                                return false
                            }
                        },
                        completionHandler: { error in
                            if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                        })
                }
            } onCancel: {
                box.cancelled = true
            }
            if let error = box.error { throw error }
            if box.cancelled { throw CancellationError() }
        }
    }

    public func write(_ path: String, at offset: Int64, data: Data) async throws {
        try await run { manager in
            if offset == 0 {
                // append does not create a file; write creates or empties it
                try await manager.write(data: data, toPath: path, progress: nil)
            } else {
                try await manager.append(data: data, toPath: path, offset: offset, progress: nil)
            }
        }
    }

    public func truncate(_ path: String, to size: Int64) async throws {
        try await run { manager in
            try await manager.truncateFile(atPath: path, atOffset: UInt64(max(0, size)))
        }
    }

    public func createDirectory(_ path: String) async throws {
        try await run(retry: false) { manager in try await manager.createDirectory(atPath: path) }
    }

    /// Folders are emptied here, entry by entry: AMSMB2's own recursive
    /// removal lost files with umlauts in their names (ENOENT, verified
    /// against Samba 4.23).
    public func remove(_ path: String) async throws {
        guard let target = try await item(at: path) else {
            throw Failure(.notFound, "\(path) is not on the share")
        }
        if target.isDirectory {
            for child in try await list(path) {
                try await remove(child.path)
            }
            try await run(retry: false) { manager in try await manager.removeDirectory(atPath: path, recursive: false) }
        } else {
            try await run(retry: false) { manager in try await manager.removeFile(atPath: path) }
        }
    }

    public func move(_ from: String, to: String) async throws {
        try await run(retry: false) { manager in try await manager.moveItem(atPath: from, toPath: to) }
    }

    public func setModified(_ path: String, to date: Date) async throws {
        try await run { manager in
            try await manager.setAttributes(
                attributes: [.contentModificationDateKey: date], ofItemAtPath: path)
        }
    }

    // MARK: Server level

    /// The shares the server offers (disk shares only, no IPC$ or
    /// printers, no hidden "$" shares).
    public static func shares(of connection: Connection, password: String) async throws -> [String] {
        var probe = connection
        probe.share = "IPC$"
        let fs = SMBFileSystem(connection: probe, password: password, timeout: 15)
        let manager = try fs.makeManager()
        do {
            let shares = try await manager.listShares(enumerateHidden: false)
            try? await manager.disconnectShare()
            return shares.map(\.name).filter { !$0.hasSuffix("$") }.sorted {
                $0.localizedStandardCompare($1) == .orderedAscending
            }
        } catch {
            throw await fs.connectFailure(error)
        }
    }

    /// Opens the share once and lists its root: what "Test" in the
    /// connection editor does.
    public static func test(_ connection: Connection, password: String) async throws {
        let fs = SMBFileSystem(connection: connection, password: password, timeout: 15)
        defer { Task { await fs.disconnect() } }
        _ = try await fs.list(RemotePath.normalized(connection.initialPath))
    }

    private static func item(_ path: String, _ entry: [URLResourceKey: Any]) -> RemoteItem {
        RemoteItem(
            path: path,
            isDirectory: (entry[.isDirectoryKey] as? NSNumber)?.boolValue ?? false,
            size: (entry[.fileSizeKey] as? NSNumber)?.int64Value ?? 0,
            modified: entry[.contentModificationDateKey] as? Date ?? Date(timeIntervalSince1970: 0))
    }
}

/// Carries an error and a cancel request across AMSMB2's callback queue.
final class ErrorBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _error: (any Error)?
    private var _cancelled = false

    var error: (any Error)? {
        get { lock.sync { _error } }
        set { lock.sync { _error = newValue } }
    }

    var cancelled: Bool {
        get { lock.sync { _cancelled } }
        set { lock.sync { _cancelled = newValue } }
    }
}
