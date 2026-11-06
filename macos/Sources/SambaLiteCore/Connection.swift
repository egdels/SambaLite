// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// A saved server and share. The password is not part of it: it lives in
/// the keychain under the connection's id (`CredentialStore`).
public struct Connection: Codable, Sendable, Identifiable, Hashable {
    public var id: UUID
    public var name: String
    public var server: String
    public var port: Int
    public var share: String
    /// Folder inside the share the browser opens first.
    public var initialPath: String
    public var domain: String
    public var username: String
    /// Guest access: no username or password is sent.
    public var guest: Bool
    /// Require SMB3 encryption; the connection fails if the server cannot.
    public var encryptData: Bool
    /// Imported without credentials: they have to be entered first.
    public var credentialsRequired: Bool

    public init(
        id: UUID = UUID(), name: String = "", server: String = "", port: Int = 445, share: String = "",
        initialPath: String = "", domain: String = "", username: String = "", guest: Bool = false,
        encryptData: Bool = false, credentialsRequired: Bool = false
    ) {
        self.id = id
        self.name = name
        self.server = server
        self.port = port
        self.share = share
        self.initialPath = initialPath
        self.domain = domain
        self.username = username
        self.guest = guest
        self.encryptData = encryptData
        self.credentialsRequired = credentialsRequired
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        server = try c.decode(String.self, forKey: .server)
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 445
        share = try c.decodeIfPresent(String.self, forKey: .share) ?? ""
        initialPath = try c.decodeIfPresent(String.self, forKey: .initialPath) ?? ""
        domain = try c.decodeIfPresent(String.self, forKey: .domain) ?? ""
        username = try c.decodeIfPresent(String.self, forKey: .username) ?? ""
        guest = try c.decodeIfPresent(Bool.self, forKey: .guest) ?? false
        encryptData = try c.decodeIfPresent(Bool.self, forKey: .encryptData) ?? false
        credentialsRequired = try c.decodeIfPresent(Bool.self, forKey: .credentialsRequired) ?? false
    }

    /// The name shown in the sidebar.
    public var displayName: String {
        if !name.trimmingCharacters(in: .whitespaces).isEmpty { return name }
        return share.isEmpty ? server : "\(share) on \(server)"
    }

    /// smb://server:port, what AMSMB2 takes. IPv6 literals get brackets.
    public var serverURL: URL? {
        var host = server
        if host.contains(":") && !host.hasPrefix("[") { host = "[\(host)]" }
        let portPart = port == 445 ? "" : ":\(port)"
        return URL(string: "smb://\(host)\(portPart)")
    }

    /// Same server, port and share: the import's duplicate rule.
    public func isSameTarget(as other: Connection) -> Bool {
        server.lowercased() == other.server.lowercased() && port == other.port
            && share.lowercased() == other.share.lowercased()
    }

    /// Why the connection cannot be saved, or nil.
    public var validationError: String? {
        let host = server.trimmingCharacters(in: .whitespaces)
        if host.isEmpty { return "Enter the server's name or address." }
        if host.contains("/") || host.contains(" ") || host.contains("\\") {
            return "Enter only the server's name or address, without share or path."
        }
        if !(1...65535).contains(port) { return "The port must lie between 1 and 65535." }
        if share.contains(where: { "/\\:*?\"<>|".contains($0) }) {
            return "Enter the share's name only, without slashes."
        }
        return nil
    }

    /// Cleans what users paste: "\\nas\Data" or "smb://nas/Data/x" in the
    /// server field become server "nas", share "Data", path "x".
    public mutating func normalizeServerField() {
        var text = server.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.lowercased().hasPrefix("smb://") { text = String(text.dropFirst(6)) }
        text = text.replacingOccurrences(of: "\\", with: "/")
        let parts = text.split(separator: "/").map(String.init)
        guard let first = parts.first else { server = text; return }
        var host = first
        if let at = host.lastIndex(of: "@") { host = String(host[host.index(after: at)...]) }
        if host.filter({ $0 == ":" }).count == 1, let colon = host.firstIndex(of: ":"),
           let parsed = Int(host[host.index(after: colon)...])
        {
            port = parsed
            host = String(host[..<colon])
        }
        server = host
        if parts.count > 1, share.isEmpty { share = parts[1] }
        if parts.count > 2, initialPath.isEmpty { initialPath = parts[2...].joined(separator: "/") }
    }
}
