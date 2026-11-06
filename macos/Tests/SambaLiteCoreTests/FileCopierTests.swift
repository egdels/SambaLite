// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing
@testable import SambaLiteCore

struct FileCopierTests {
    let share = TempFolder("share")
    let local = TempFolder("local")
    var fs: DirectoryFileSystem { DirectoryFileSystem(root: share.url) }

    @Test func downloadCopiesContentAndTime() async throws {
        share.write("a/b.txt", "hello", modified: day0)
        let copier = FileCopier(remote: fs)
        try await copier.download("a/b.txt", to: local.file("x/b.txt"))
        #expect(local.read("x/b.txt") == "hello")
        #expect(local.modified("x/b.txt") == day0)
        #expect(local.tree() == ["x/", "x/b.txt"])
    }

    @Test func downloadReplacesExistingFile() async throws {
        share.write("b.txt", "new", modified: day0)
        local.write("b.txt", "old old old")
        try await FileCopier(remote: fs).download("b.txt", to: local.file("b.txt"))
        #expect(local.read("b.txt") == "new")
    }

    @Test func interruptedDownloadResumesAtThePartFile() async throws {
        let data = randomData(300_000)
        share.write("big.bin", data, modified: day0)
        let remote = fs
        remote.chunkSize = 50_000
        let chunks = Locked(0)
        remote.failing.set { operation, _ in
            if operation == "readChunk" {
                let n = chunks.update { value -> Int in value += 1; return value }
                if n == 3 { throw Failure(.serverUnreachable, "gone") }
            }
        }
        let copier = FileCopier(remote: remote)
        await #expect(throws: Failure.self) {
            try await copier.download("big.bin", to: local.file("big.bin"))
        }
        #expect(!local.exists("big.bin"))
        let parts = local.tree().filter { $0.hasSuffix(Names.partSuffix) }
        #expect(parts.count == 1)

        remote.failing.set(nil)
        let started = Locked<Int64>(-1)
        try await copier.download("big.bin", to: local.file("big.bin")) { done, _ in
            started.update { if $0 < 0 { $0 = done } }
        }
        #expect(started.get() == 100_000)
        #expect(try Data(contentsOf: local.file("big.bin")) == data)
        #expect(local.tree() == ["big.bin"])
    }

    @Test func aChangedSourceStartsAFreshPartFile() async throws {
        share.write("f.bin", randomData(1000), modified: day0)
        let stale = Names.partName("f.bin", size: 1000, modified: day0)
        local.write(stale, "stale")
        share.write("f.bin", "now different", modified: day0.addingTimeInterval(60))
        try await FileCopier(remote: fs).download("f.bin", to: local.file("f.bin"))
        #expect(local.read("f.bin") == "now different")
        // the stale part of the old version is another name; it stays for
        // the sync's cleanup, and the new download did not append to it
        #expect(local.read(stale) == "stale")
    }

    @Test func downloadFailsWhenTheShareFileChangesMeanwhile() async throws {
        share.write("c.txt", "aaaaaaaaaa", modified: day0)
        let remote = fs
        remote.chunkSize = 4
        let root = share
        remote.failing.set { operation, _ in
            if operation == "readChunk" {
                root.write("c.txt", "bbbbbbbbbb", modified: day0.addingTimeInterval(100))
            }
        }
        await #expect(throws: Failure(.fileChanged, "c.txt changed on the share during the download")) {
            try await FileCopier(remote: remote).download("c.txt", to: local.file("c.txt"))
        }
        #expect(local.tree().isEmpty)
    }

    @Test func uploadCopiesContentAndTime() async throws {
        let file = local.write("ü.txt", "hallo", modified: day0)
        let item = try await FileCopier(remote: fs).upload(file, to: "deep/er/ü.txt")
        #expect(item.size == 5)
        #expect(share.read("deep/er/ü.txt") == "hallo")
        #expect(share.modified("deep/er/ü.txt") == day0)
        #expect(share.tree() == ["deep/", "deep/er/", "deep/er/ü.txt"])
    }

    @Test func uploadReplacesAndKeepsOldFileWhenTheSwapFails() async throws {
        share.write("r.txt", "old")
        let file = local.write("r.txt", "new")
        let remote = fs
        remote.failing.set { operation, path in
            if operation == "move", path.hasSuffix(Names.partSuffix) { throw Failure(.accessDenied, "no") }
        }
        await #expect(throws: Failure.self) { try await FileCopier(remote: remote).upload(file, to: "r.txt") }
        #expect(share.read("r.txt") == "old")
        remote.failing.set(nil)
        try await FileCopier(remote: remote).upload(file, to: "r.txt")
        #expect(share.read("r.txt") == "new")
        #expect(share.tree() == ["r.txt"])
    }

    @Test func interruptedUploadResumes() async throws {
        let data = randomData(250_000)
        let file = local.write("u.bin", data, modified: day0)
        let remote = fs
        let writes = Locked(0)
        remote.failing.set { operation, _ in
            if operation == "write" {
                let n = writes.update { value -> Int in value += 1; return value }
                if n == 3 { throw Failure(.serverUnreachable, "gone") }
            }
        }
        var copier = FileCopier(remote: remote)
        copier.chunkSize = 60_000
        await #expect(throws: Failure.self) { try await copier.upload(file, to: "u.bin") }
        remote.failing.set(nil)
        let started = Locked<Int64>(-1)
        try await copier.upload(file, to: "u.bin") { done, _ in started.update { if $0 < 0 { $0 = done } } }
        #expect(started.get() == 120_000)
        #expect(try Data(contentsOf: share.file("u.bin")) == data)
        #expect(share.tree() == ["u.bin"])
    }

    @Test func uploadRefusesNamesSMBCannotStore() async throws {
        let file = local.write("a:b.txt", "x")
        await #expect(throws: Failure.self) { try await FileCopier(remote: fs).upload(file, to: "a:b.txt") }
    }

    @Test func emptyFilesTravelBothWays() async throws {
        let file = local.write("empty", Data(), modified: day0)
        try await FileCopier(remote: fs).upload(file, to: "empty")
        #expect(share.read("empty") == "")
        try await FileCopier(remote: fs).download("empty", to: local.file("back"))
        #expect(local.read("back") == "")
    }

    @Test func discardRemovesPartFiles() async throws {
        local.write(Names.partName("d.txt", size: 1, modified: day0), "x")
        FileCopier.discardDownload(to: local.file("d.txt"))
        #expect(local.tree().isEmpty)
        share.write(Names.partName("d.txt", size: 1, modified: day0), "x")
        await FileCopier(remote: fs).discardUpload(to: "d.txt")
        #expect(share.tree().isEmpty)
    }
}

/// The same against the Samba container (SAMBALITE_SMB_TEST=1).
@Suite(.enabled(if: smbTestsEnabled), .serialized)
struct SMBFileCopierTests {
    let local = TempFolder("smb-local")

    func makeFS() -> SMBFileSystem {
        let (connection, password) = testServer()!
        return SMBFileSystem(connection: connection, password: password)
    }

    @Test func roundTripWithResumeAndUmlauts() async throws {
        let fs = makeFS()
        let base = "copier-\(UUID().uuidString)"
        let data = randomData(9_000_000)
        let file = local.write("Grüße.bin", data, modified: day0)
        var configured = FileCopier(remote: fs)
        configured.chunkSize = 1_000_000
        let copier = configured
        let target = "\(base)/Grüße.bin"
        // first attempt stopped after about 3 MB
        let stop = Task { @Sendable in
            try await copier.upload(file, to: target) { done, _ in
                if done >= 3_000_000 { withUnsafeCurrentTask { $0?.cancel() } }
            }
        }
        _ = await stop.result
        let parts = try await fs.list(base).filter { $0.name.hasSuffix(Names.partSuffix) }
        #expect(parts.count == 1)
        let started = Locked<Int64>(-1)
        let item = try await copier.upload(file, to: "\(base)/Grüße.bin") { done, _ in
            started.update { if $0 < 0 { $0 = done } }
        }
        #expect(started.get() >= 3_000_000)
        #expect(item.size == 9_000_000)
        #expect(abs(item.modified.timeIntervalSince(day0)) < 1)
        #expect(try await fs.list(base).map(\.name) == ["Grüße.bin"])

        try await copier.download("\(base)/Grüße.bin", to: local.file("back/Grüße.bin"))
        #expect(try Data(contentsOf: local.file("back/Grüße.bin")) == data)
        #expect(local.modified("back/Grüße.bin").map { abs($0.timeIntervalSince(day0)) < 1 } == true)

        // replace an existing file
        let small = local.write("small.txt", "v2")
        try await copier.upload(small, to: "\(base)/Grüße.bin")
        #expect(try await fs.item(at: "\(base)/Grüße.bin")?.size == 2)
        #expect(try await fs.list(base).map(\.name) == ["Grüße.bin"])
        try await fs.remove(base)
        await fs.disconnect()
    }

    @Test func wrongPasswordIsALoginError() async throws {
        let (connection, _) = testServer()!
        let fs = SMBFileSystem(connection: connection, password: "wrong")
        do {
            _ = try await fs.list("")
            Issue.record("listed with a wrong password")
        } catch let failure as Failure {
            #expect(failure.category == .loginRejected)
        }
    }

    @Test func closedPortIsUnreachable() async throws {
        var (connection, password) = testServer()!
        connection.port = 4451
        let fs = SMBFileSystem(connection: connection, password: password, timeout: 5)
        do {
            _ = try await fs.list("")
            Issue.record("listed on a closed port")
        } catch let failure as Failure {
            #expect(failure.category == .serverUnreachable)
        }
    }

    @Test func listsShares() async throws {
        let (connection, password) = testServer()!
        #expect(try await SMBFileSystem.shares(of: connection, password: password) == ["Data"])
    }
}

/// A server that stops answering in the middle of a download, then comes
/// back: the copy fails with a timeout, the next one resumes, the process
/// survives the late replies. Needs SAMBALITE_SMB_STALL_TEST=1 and pauses
/// the `sambalite-mac-test` container with the docker CLI.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["SAMBALITE_SMB_STALL_TEST"] == "1"), .serialized)
struct SMBStallTests {
    let local = TempFolder("stall")

    func docker(_ args: String...) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["docker"] + args
        try? process.run()
        process.waitUntilExit()
    }

    @Test func downloadSurvivesAStalledServer() async throws {
        let (connection, password) = testServer()!
        let fs = SMBFileSystem(connection: connection, password: password, timeout: 5)
        let data = randomData(120_000_000)
        let source = local.write("source.bin", data)
        try await FileCopier(remote: fs).upload(source, to: "stall-test.bin")

        let paused = Locked(false)
        let copier = FileCopier(remote: fs)
        let target = local.file("back.bin")
        let started = Date()
        do {
            try await copier.download("stall-test.bin", to: target) { done, _ in
                if done > 20_000_000 && !paused.get() {
                    paused.set(true)
                    self.docker("pause", "sambalite-mac-test")
                }
            }
            Issue.record("the download finished although the server was paused")
        } catch let failure as Failure {
            #expect(failure.category == .serverUnreachable)
        }
        #expect(Date().timeIntervalSince(started) < 30)
        docker("unpause", "sambalite-mac-test")
        try await Task.sleep(nanoseconds: 2_000_000_000)

        // the same file system object goes on, on a fresh session
        let resumedAt = Locked<Int64>(-1)
        try await copier.download("stall-test.bin", to: target) { done, _ in
            resumedAt.update { if $0 < 0 { $0 = done } }
        }
        #expect(resumedAt.get() > 20_000_000)
        #expect(try Data(contentsOf: target) == data)
        try await fs.remove("stall-test.bin")
        await fs.disconnect()
    }
}
