// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// The connection file of the Android app (docs/connection_file.md):
/// `{"format":"sambalite-connections","version":1,"connections":[...]}`.
/// Read and written the same way here, so a file moves between phone and
/// Mac in both directions.
public enum ConnectionFile {
    public static let format = "sambalite-connections"
    public static let version = 1
    public static let maxConnections = 500

    /// One connection of an import, with the password it carried (if any)
    /// or the reason it was skipped.
    public struct Entry: Sendable {
        public var connection: Connection
        public var password: String?
        public var skipReason: String?
    }

    private struct Raw: Decodable {
        var server: String?
        var name: String?
        var port: Int?
        var share: String?
        var initialPath: String?
        var domain: String?
        var username: String?
        var password: String?
        var guest: Bool?
        var encryptData: Bool?
    }

    private struct Envelope: Decodable {
        var format: String?
        var version: Int?
        var connections: [Lenient]
    }

    /// A connection entry that may fail to decode alone.
    private struct Lenient: Decodable {
        var raw: Raw?
        init(from decoder: any Decoder) throws {
            raw = try? Raw(from: decoder)
        }
    }

    public static func read(_ data: Data) throws -> [Entry] {
        let envelope: Envelope
        do {
            envelope = try JSONDecoder().decode(Envelope.self, from: data)
        } catch {
            throw Failure(.other, "This is not a SambaLite connection file.")
        }
        if let format = envelope.format, format != Self.format {
            throw Failure(.other, "This is not a SambaLite connection file.")
        }
        if let version = envelope.version, version > Self.version {
            throw Failure(.other, "The file was written by a newer SambaLite.")
        }
        if envelope.connections.count > maxConnections {
            throw Failure(.other, "The file holds more than \(maxConnections) connections.")
        }
        return envelope.connections.map { lenient in
            guard let raw = lenient.raw else {
                return Entry(connection: Connection(), password: nil, skipReason: "unreadable entry")
            }
            var connection = Connection(
                name: raw.name ?? "", server: (raw.server ?? "").trimmingCharacters(in: .whitespaces),
                port: raw.port ?? 445, share: raw.share ?? "",
                initialPath: RemotePath.normalized(raw.initialPath ?? ""), domain: raw.domain ?? "",
                username: raw.username ?? "", guest: raw.guest ?? false, encryptData: raw.encryptData ?? false)
            if connection.server.hasPrefix("\\\\") { connection.server = String(connection.server.dropFirst(2)) }
            if connection.name.isEmpty { connection.name = connection.server }
            var password = raw.password
            if connection.guest {
                connection.username = ""
                password = nil
            } else if connection.username.isEmpty || (password ?? "").isEmpty {
                // an empty template never logs in anonymously by accident
                connection.credentialsRequired = true
            }
            if password?.isEmpty == true { password = nil }
            return Entry(connection: connection, password: password, skipReason: connection.validationError)
        }
    }

    /// Writes `connections` without passwords; usernames only when asked.
    public static func write(_ connections: [Connection], includeUsernames: Bool) throws -> Data {
        let list: [[String: Any]] = connections.map { c in
            var entry: [String: Any] = [
                "name": c.name, "server": c.server, "port": c.port, "share": c.share,
                "encryptData": c.encryptData,
            ]
            if !c.initialPath.isEmpty { entry["initialPath"] = c.initialPath }
            if !c.domain.isEmpty { entry["domain"] = c.domain }
            if c.guest {
                entry["guest"] = true
            } else if includeUsernames, !c.username.isEmpty {
                entry["username"] = c.username
            }
            return entry
        }
        let root: [String: Any] = ["format": format, "version": version, "connections": list]
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }
}
