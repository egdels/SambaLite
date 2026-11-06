// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
@testable import SambaLiteCore

/// A fresh folder under the temporary directory, removed on deinit.
final class TempFolder: @unchecked Sendable {
    let url: URL

    init(_ label: String = "t") {
        url = FileManager.default.temporaryDirectory
            .appendingPathComponent("sambalite-tests-\(label)-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }

    func file(_ path: String) -> URL { url.appendingPathComponent(path) }

    @discardableResult
    func write(_ path: String, _ text: String, modified: Date? = nil) -> URL {
        write(path, Data(text.utf8), modified: modified)
    }

    @discardableResult
    func write(_ path: String, _ data: Data, modified: Date? = nil) -> URL {
        let target = file(path)
        try! FileManager.default.createDirectory(
            at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try! data.write(to: target)
        if let modified {
            try! FileManager.default.setAttributes([.modificationDate: modified], ofItemAtPath: target.path)
        }
        return target
    }

    func mkdir(_ path: String) {
        try! FileManager.default.createDirectory(at: file(path), withIntermediateDirectories: true)
    }

    func read(_ path: String) -> String? {
        (try? Data(contentsOf: file(path))).map { String(decoding: $0, as: UTF8.self) }
    }

    func exists(_ path: String) -> Bool {
        FileManager.default.fileExists(atPath: file(path).path)
    }

    func modified(_ path: String) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: file(path).path))?[.modificationDate] as? Date
    }

    /// Every path below the folder, sorted, folders with a trailing "/".
    func tree() -> [String] {
        var result: [String] = []
        let base = url.resolvingSymlinksInPath().path
        let enumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.isDirectoryKey])!
        for case let item as URL in enumerator {
            var path = String(item.resolvingSymlinksInPath().path.dropFirst(base.count + 1))
            if (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true { path += "/" }
            result.append(path)
        }
        return result.sorted()
    }
}

/// Random bytes, so a mixed-up offset shows in the content.
func randomData(_ count: Int) -> Data {
    var generator = SystemRandomNumberGenerator()
    return Data((0..<count).map { _ in UInt8.random(in: 0...255, using: &generator) })
}

let day0 = Date(timeIntervalSince1970: 1_700_000_000)

/// The Samba container of the integration tests, or nil when it is not
/// asked for. Start it with
/// `docker run -d --name sambalite-mac-test -p 4452:445 -e USER=smbuser
/// -e PASS=secret123 -e NAME=Data dockurr/samba:4.23.8`
/// and run the tests with SAMBALITE_SMB_TEST=1. No `-v` bind mount: Samba
/// on a folder shared from macOS into the VM refuses deletes
/// (STATUS_NOT_SUPPORTED), which looks like an app bug and is not.
func testServer() -> (Connection, String)? {
    guard ProcessInfo.processInfo.environment["SAMBALITE_SMB_TEST"] == "1" else { return nil }
    let connection = Connection(
        name: "Test", server: "127.0.0.1", port: 4452, share: "Data", username: "smbuser")
    return (connection, "secret123")
}

var smbTestsEnabled: Bool { testServer() != nil }
