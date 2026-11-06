// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Security

/// Where the app keeps its files: ~/Library/Application Support/SambaLite.
public enum AppFolder {
    public static var url: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let folder = base.appendingPathComponent("SambaLite", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
}

/// A Codable value in a JSON file, written atomically (a crash leaves
/// the old file or the new one, never a torn one).
public struct JSONFile<Value: Codable>: Sendable {
    public let url: URL

    public init(_ url: URL) {
        self.url = url
    }

    public func load() throws -> Value? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return try decoder.decode(Value.self, from: data)
    }

    public func save(_ value: Value) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }

    /// Loads, and keeps a copy of a file that does not decode instead of
    /// overwriting it with defaults later.
    public func loadOrSetAside() -> Value? {
        do {
            return try load()
        } catch {
            let aside = url.deletingPathExtension().appendingPathExtension("broken-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: url, to: aside)
            return nil
        }
    }
}

/// Passwords by connection id.
public protocol CredentialStore: Sendable {
    func password(for id: UUID) -> String?
    func setPassword(_ password: String, for id: UUID) throws
    func removePassword(for id: UUID)
}

/// The login keychain, one generic password per connection.
public struct KeychainCredentialStore: CredentialStore {
    public let service: String

    public init(service: String = "de.schliweb.sambalite") {
        self.service = service
    }

    private func query(_ id: UUID) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: id.uuidString,
        ]
    }

    public func password(for id: UUID) -> String? {
        var query = query(id)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func setPassword(_ password: String, for id: UUID) throws {
        let data = Data(password.utf8)
        let status = SecItemUpdate(query(id) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = query(id)
            add[kSecValueData as String] = data
            add[kSecAttrLabel as String] = "SambaLite connection"
            let added = SecItemAdd(add as CFDictionary, nil)
            guard added == errSecSuccess else { throw Failure(.other, "Keychain error \(added)") }
        } else if status != errSecSuccess {
            throw Failure(.other, "Keychain error \(status)")
        }
    }

    public func removePassword(for id: UUID) {
        SecItemDelete(query(id) as CFDictionary)
    }
}

/// For tests.
public final class MemoryCredentialStore: CredentialStore, @unchecked Sendable {
    private let values = Locked<[UUID: String]>([:])
    public init() {}
    public func password(for id: UUID) -> String? { values.get()[id] }
    public func setPassword(_ password: String, for id: UUID) throws { values.update { $0[id] = password } }
    public func removePassword(for id: UUID) { values.update { $0[id] = nil } }
}

/// Passwords in a plain JSON file: only for automated UI checks
/// (SAMBALITE_PASSWORDS=file), where a rebuilt, ad-hoc signed binary
/// would get a keychain prompt every time. Never the default.
public final class FileCredentialStore: CredentialStore, @unchecked Sendable {
    private let file: JSONFile<[String: String]>
    private let lock = NSLock()

    public init(url: URL) {
        file = JSONFile(url)
    }

    public func password(for id: UUID) -> String? {
        lock.sync { (try? file.load())??[id.uuidString] }
    }

    public func setPassword(_ password: String, for id: UUID) throws {
        try lock.sync {
            var all = (try? file.load()) ?? nil ?? [:]
            all[id.uuidString] = password
            try file.save(all)
        }
    }

    public func removePassword(for id: UUID) {
        lock.sync {
            var all = (try? file.load()) ?? nil ?? [:]
            all[id.uuidString] = nil
            try? file.save(all)
        }
    }
}
