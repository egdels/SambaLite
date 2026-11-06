// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing
@testable import SambaLiteCore

/// A sync between two temp folders, the "share" behind DirectoryFileSystem.
final class SyncFixture: @unchecked Sendable {
    let share = TempFolder("sync-share")
    let local = TempFolder("sync-local")
    let fs: DirectoryFileSystem
    var config: SyncConfig
    var state: SyncState = [:]
    var last: SyncRunResult?
    var log = SyncLog()

    init(_ direction: SyncDirection, mirror: Bool = false, remotePath: String = "") {
        fs = DirectoryFileSystem(root: share.url)
        config = SyncConfig(
            connectionID: UUID(), localPath: local.url.path, remotePath: remotePath, direction: direction,
            mirror: mirror, mirrorUseTrash: false)
    }

    @discardableResult
    func run() async -> SyncRunResult {
        log = SyncLog()
        let engine = SyncEngine(config: config, remote: fs)
        let (result, newState) = await engine.run(state: state, previous: last, log: log)
        state = newState
        last = result
        return result
    }
}

struct SyncEngineTests {
    let t1 = day0
    let t2 = day0.addingTimeInterval(3600)
    let t3 = day0.addingTimeInterval(7200)

    @Test func firstBidirectionalRunBringsBothSidesTogether() async {
        let f = SyncFixture(.bidirectional)
        f.local.write("mac.txt", "from mac", modified: t1)
        f.local.write("both.txt", "same", modified: t1)
        f.local.mkdir("emptyhere")
        f.share.write("nas/deep/share.txt", "from share", modified: t1)
        f.share.write("both.txt", "same", modified: t1)
        let run = await f.run()
        #expect(run.status == .completed)
        #expect(run.counts.uploaded == 1)
        #expect(run.counts.downloaded == 1)
        #expect(f.local.read("nas/deep/share.txt") == "from share")
        #expect(f.share.read("mac.txt") == "from mac")
        #expect(f.share.exists("emptyhere"))
        #expect(f.local.modified("nas/deep/share.txt") == t1)
        #expect(f.share.modified("mac.txt") == t1)

        let again = await f.run()
        #expect(again.status == .completed)
        #expect(again.counts.transferred == 0)
        #expect(again.checked == 3)
    }

    @Test func changesTravelTheRightWay() async {
        let f = SyncFixture(.bidirectional)
        f.local.write("a.txt", "a1", modified: t1)
        f.local.write("b.txt", "b1", modified: t1)
        await f.run()
        f.local.write("a.txt", "a2 from mac", modified: t2)
        f.share.write("b.txt", "b2 from share", modified: t2)
        let run = await f.run()
        #expect(run.counts.uploaded == 1 && run.counts.downloaded == 1)
        #expect(f.share.read("a.txt") == "a2 from mac")
        #expect(f.local.read("b.txt") == "b2 from share")
    }

    @Test func aConflictKeepsTheOlderVersionAsACopy() async {
        let f = SyncFixture(.bidirectional)
        f.local.write("doc.txt", "v1", modified: t1)
        await f.run()
        f.local.write("doc.txt", "mac edit", modified: t2)
        f.share.write("doc.txt", "share edit, later", modified: t3)
        let run = await f.run()
        #expect(run.counts.conflicts == 1)
        #expect(f.local.read("doc.txt") == "share edit, later")
        let copies = f.local.tree().filter { $0.contains("(conflict") }
        #expect(copies.count == 1)
        #expect(copies.first.flatMap { f.local.read($0) } == "mac edit")
        // the copy goes up on the next run, as a new file
        let next = await f.run()
        #expect(next.counts.uploaded == 1)
        #expect(f.share.tree().filter { $0.contains("(conflict") }.count == 1)
    }

    @Test func deletionsAreNotCarriedOverWithoutMirror() async {
        let f = SyncFixture(.bidirectional)
        f.local.write("keep.txt", "x", modified: t1)
        await f.run()
        try? FileManager.default.removeItem(at: f.local.file("keep.txt"))
        let run = await f.run()
        #expect(run.counts.downloaded == 1)
        #expect(f.local.read("keep.txt") == "x")
    }

    @Test func oneWayRestoresAnOlderSourceVersion() async {
        let f = SyncFixture(.upload)
        f.local.write("r.txt", "new", modified: t2)
        await f.run()
        f.local.write("r.txt", "restored older", modified: t1)
        let run = await f.run()
        #expect(run.counts.uploaded == 1)
        #expect(f.share.read("r.txt") == "restored older")
    }

    @Test func oneWayLeavesTheOtherSideAlone() async {
        let f = SyncFixture(.upload)
        f.share.write("only-there.txt", "x", modified: t1)
        f.local.write("up.txt", "u", modified: t1)
        let run = await f.run()
        #expect(run.counts.uploaded == 1 && run.counts.downloaded == 0)
        #expect(!f.local.exists("only-there.txt"))
    }

    @Test func mirrorRemovesWhatTheSourceLost() async {
        let f = SyncFixture(.upload, mirror: true)
        f.config.mirrorUseTrash = true
        f.local.write("a/1.txt", "1", modified: t1)
        f.local.write("a/2.txt", "2", modified: t1)
        f.local.write("b.txt", "b", modified: t1)
        await f.run()
        try? FileManager.default.removeItem(at: f.local.file("a"))
        let run = await f.run()
        #expect(run.counts.deleted == 3)  // two files and the emptied folder
        #expect(f.share.read("b.txt") == "b")
        #expect(!f.share.exists("a"))
        let trashed = f.share.tree().filter { $0.hasPrefix(Names.trashFolder) && $0.hasSuffix(".txt") }
        #expect(trashed.count == 2)
        #expect(f.state.keys.sorted() == ["b.txt"])
    }

    @Test func mirrorNeverTouchesFilesItDidNotSync() async {
        let f = SyncFixture(.download, mirror: true)
        f.share.write("synced.txt", "s", modified: t1)
        await f.run()
        f.local.write("mine.txt", "mine", modified: t1)
        try? FileManager.default.removeItem(at: f.share.file("synced.txt"))
        f.share.write("other.txt", "o", modified: t1)
        let run = await f.run()
        #expect(run.counts.deleted == 1)
        #expect(f.local.read("mine.txt") == "mine")
        #expect(!f.local.exists("synced.txt"))
    }

    @Test func mirrorKeepsATargetThatChangedSince() async {
        let f = SyncFixture(.download, mirror: true)
        f.share.write("s.txt", "s", modified: t1)
        await f.run()
        f.local.write("s.txt", "edited here", modified: t2)
        try? FileManager.default.removeItem(at: f.share.file("s.txt"))
        let run = await f.run()
        #expect(run.counts.deleted == 0)
        #expect(f.local.read("s.txt") == "edited here")
    }

    @Test func mirrorSkipsAnEmptySource() async {
        let f = SyncFixture(.download, mirror: true)
        f.share.write("x.txt", "x", modified: t1)
        await f.run()
        try? FileManager.default.removeItem(at: f.share.file("x.txt"))
        let run = await f.run()
        #expect(run.counts.deleted == 0)
        #expect(run.mirrorNote?.contains("empty") == true)
        #expect(f.local.exists("x.txt"))
    }

    @Test func mirrorAbortsAboveTheThreshold() async {
        let f = SyncFixture(.upload, mirror: true)
        for i in 0..<150 { f.local.write("f\(i).txt", "\(i)", modified: t1) }
        await f.run()
        for i in 0..<120 { try? FileManager.default.removeItem(at: f.local.file("f\(i).txt")) }
        let run = await f.run()
        #expect(run.counts.deleted == 0)
        #expect(run.mirrorNote?.contains("120 of 150") == true)
        #expect(f.share.tree().count == 150)
    }

    @Test func mirrorSkipsAnIncompleteSourceListing() async {
        let f = SyncFixture(.download, mirror: true)
        f.share.write("a/1.txt", "1", modified: t1)
        f.share.write("b/2.txt", "2", modified: t1)
        await f.run()
        try? FileManager.default.removeItem(at: f.share.file("b/2.txt"))
        f.fs.failing.set { op, path in if op == "list", path == "a" { throw Failure(.accessDenied, "no") } }
        let run = await f.run()
        #expect(run.status == .completedWithErrors)
        #expect(run.counts.deleted == 0)
        #expect(f.local.exists("a/1.txt") && f.local.exists("b/2.txt"))
    }

    @Test func anUnreadableShareFolderIsNotTakenForEmpty() async {
        let f = SyncFixture(.upload)
        f.local.write("sub/a.txt", "local a", modified: t1)
        f.share.write("sub/a.txt", "share a, newer", modified: t2)
        f.fs.failing.set { op, path in if op == "list", path == "sub" { throw Failure(.accessDenied, "no") } }
        let run = await f.run()
        #expect(run.status == .completedWithErrors)
        #expect(run.errors.first?.category == .folderSkipped)
        #expect(f.share.read("sub/a.txt") == "share a, newer")
    }

    @Test func decomposedAndComposedUmlautsAreOneFile() async {
        let f = SyncFixture(.bidirectional)
        f.local.write("Gru\u{0308}sse/A\u{0308}pfel.txt", "same", modified: t1)
        f.share.write("Grüsse/Äpfel.txt", "same", modified: t1)
        let run = await f.run()
        #expect(run.counts.transferred == 0)
        #expect(run.counts.folders == 0)
        #expect(f.share.tree().count == 2)
        #expect(f.local.tree().count == 2)
    }

    /// The temp "share" is on this Mac's case-insensitive disk and cannot
    /// hold both names, so this goes to the planner directly.
    @Test func namesDifferingOnlyInCaseAreReported() {
        let plan = SyncPlanner.plan(.init(
            direction: .download, mirror: false, local: [],
            remote: [
                RemoteItem(path: "Report.txt", isDirectory: false, size: 3, modified: t1),
                RemoteItem(path: "report.txt", isDirectory: false, size: 3, modified: t1),
            ], state: [:]))
        let downloads = plan.actions.filter { if case .download = $0 { true } else { false } }
        let errors = plan.actions.compactMap { if case .error(_, let f) = $0 { f.category } else { nil } }
        // neither twin is taken: which one belongs here is unknown
        #expect(downloads.isEmpty)
        #expect(errors == [.nameCollision, .nameCollision])
        // a case-sensitive disk takes both
        let both = SyncPlanner.plan(.init(
            direction: .download, mirror: false, local: [],
            remote: [
                RemoteItem(path: "Report.txt", isDirectory: false, size: 3, modified: t1),
                RemoteItem(path: "report.txt", isDirectory: false, size: 3, modified: t1),
            ], state: [:], localCaseSensitive: true))
        #expect(both.actions.filter { if case .download = $0 { true } else { false } }.count == 2)
    }

    @Test func caseOnlyDifferencesMatchTheSameFile() async {
        let f = SyncFixture(.bidirectional)
        f.local.write("photo.JPG", "img", modified: t1)
        f.share.write("photo.jpg", "img", modified: t1)
        let run = await f.run()
        #expect(run.counts.transferred == 0)
    }

    @Test func macBookkeepingFilesStayHome() async {
        let f = SyncFixture(.upload)
        f.local.write(".DS_Store", "x")
        f.local.write("._doc.txt", "x")
        f.local.write("doc.txt", "d", modified: t1)
        await f.run()
        #expect(f.share.tree() == ["doc.txt"])
    }

    @Test func namesTheShareCannotStoreAreErrors() async {
        let f = SyncFixture(.upload)
        f.local.write("a:b.txt", "x", modified: t1)
        f.local.write("ok.txt", "x", modified: t1)
        let run = await f.run()
        #expect(run.status == .completedWithErrors)
        #expect(run.errors.map(\.category) == [.invalidName])
        #expect(f.share.tree() == ["ok.txt"])
        let again = await f.run()
        #expect(again.errors.first?.streak == 2)
    }

    @Test func aLostConnectionStopsTheRunAndKeepsWhatWasDone() async {
        let f = SyncFixture(.upload)
        for i in 0..<5 { f.local.write("f\(i).txt", "\(i)", modified: t1) }
        let uploads = Locked(0)
        f.fs.failing.set { op, path in
            if op == "move" {
                let n = uploads.update { value -> Int in value += 1; return value }
                if n == 3 { throw Failure(.serverUnreachable, "gone") }
            }
        }
        let run = await f.run()
        #expect(run.status == .failed)
        #expect(run.failure?.category == .serverUnreachable)
        #expect(run.counts.uploaded == 2)
        #expect(f.state.count == 2)
        f.fs.failing.set(nil)
        let next = await f.run()
        #expect(next.status == .completed)
        #expect(next.counts.uploaded == 3)
    }

    @Test func aMissingLocalFolderTouchesNothing() async {
        let f = SyncFixture(.download, mirror: true)
        f.share.write("a.txt", "a", modified: t1)
        await f.run()
        f.config.localPath = f.local.url.appendingPathComponent("gone").path
        let run = await f.run()
        #expect(run.status == .failed)
        #expect(run.failure?.category == .localFolderMissing)
        #expect(f.share.exists("a.txt"))
        #expect(f.state.count == 1)
    }

    @Test func aMissingShareFolderFails() async {
        let f = SyncFixture(.upload, remotePath: "not/there")
        f.local.write("a.txt", "a", modified: t1)
        let run = await f.run()
        #expect(run.failure?.category == .remoteFolderMissing)
        #expect(f.share.tree().isEmpty)
    }

    @Test func syncsIntoASubfolderOfTheShare() async {
        let f = SyncFixture(.bidirectional, remotePath: "Backups/Mac")
        f.share.mkdir("Backups/Mac")
        f.share.write("Backups/other.txt", "not mine", modified: t1)
        f.share.write("Backups/Mac/in.txt", "in", modified: t1)
        f.local.write("out.txt", "out", modified: t1)
        let run = await f.run()
        #expect(run.counts.transferred == 2)
        #expect(f.local.tree() == ["in.txt", "out.txt"])
        #expect(f.share.read("Backups/Mac/out.txt") == "out")
    }

    @Test func healthFollowsTheRuns() {
        let config = SyncConfig(connectionID: UUID(), localPath: "/x", remotePath: "", intervalMinutes: 60)
        func run(_ status: SyncRunStatus, ended: Date, errors: Int = 0) -> SyncRunResult {
            SyncRunResult(
                id: UUID(), configID: config.id, started: ended, ended: ended, status: status, counts: SyncCounts(),
                failure: status == .failed ? Failure(.serverUnreachable, "x") : nil, errors: [], errorCount: errors,
                checked: 0, mirrorNote: nil)
        }
        let now = Date()
        #expect(SyncHealth.of(config, runs: [], running: false) == .neverRun)
        #expect(SyncHealth.of(config, runs: [run(.completed, ended: now)], running: false, now: now) == .upToDate)
        #expect(SyncHealth.of(config, runs: [run(.completedWithErrors, ended: now, errors: 2)], running: false, now: now) == .withErrors(2))
        #expect(SyncHealth.of(config, runs: [run(.completed, ended: now.addingTimeInterval(-3 * 3600 - 60))], running: false, now: now) == .overdue)
        #expect(SyncHealth.of(config, runs: [run(.completed, ended: now), run(.failed, ended: now)], running: false, now: now) == .failed(.serverUnreachable))
        var paused = config
        paused.enabled = false
        #expect(SyncHealth.of(paused, runs: [], running: false) == .paused)
    }
}

/// The engine against the Samba container (SAMBALITE_SMB_TEST=1).
@Suite(.enabled(if: smbTestsEnabled), .serialized)
struct SMBSyncTests {
    let local = TempFolder("smb-sync")

    @Test func bidirectionalWithUmlautsThenMirror() async throws {
        let (connection, password) = testServer()!
        let fs = SMBFileSystem(connection: connection, password: password)
        let base = "sync-\(UUID().uuidString)"
        try await fs.createDirectories("\(base)/Grüße")
        try await fs.write("\(base)/Grüße/vom Server.txt", at: 0, data: Data("server".utf8))
        local.write("Gru\u{0308}ße/vom Mac.txt", "mac")  // decomposed on purpose
        local.write("Ordner/Ünter/tief.txt", "deep")

        var config = SyncConfig(connectionID: connection.id, localPath: local.url.path, remotePath: base, direction: .bidirectional)
        var state: SyncState = [:]
        let first = await SyncEngine(config: config, remote: fs).run(state: state, previous: nil, log: SyncLog())
        state = first.1
        #expect(first.0.status == .completed)
        #expect(first.0.counts.uploaded == 2 && first.0.counts.downloaded == 1)
        // one folder on the share, not a decomposed twin of it
        #expect(try await fs.list(base).map(\.name).sorted() == ["Grüße", "Ordner"])
        #expect(try await fs.list("\(base)/Grüße").count == 2)

        let second = await SyncEngine(config: config, remote: fs).run(state: state, previous: first.0, log: SyncLog())
        state = second.1
        #expect(second.0.counts.transferred == 0)
        #expect(second.0.checked == 3)

        // one way with mirror: a file gone from the Mac goes to .sambalite-trash
        config.direction = .upload
        config.mirror = true
        try FileManager.default.removeItem(at: local.file("Ordner/Ünter/tief.txt"))
        let third = await SyncEngine(config: config, remote: fs).run(state: state, previous: second.0, log: SyncLog())
        #expect(third.0.counts.deleted >= 1)
        #expect(try await fs.item(at: "\(base)/Ordner/Ünter/tief.txt") == nil)
        let trash = try await fs.walk("\(base)/\(Names.trashFolder)", skip: { _ in false }).items
        #expect(trash.contains { $0.name == "tief.txt" })

        try await fs.remove(base)
        await fs.disconnect()
    }
}

/// The cases of the independent review (2026-11-06).
struct ReviewFindingTests {
    let t1 = day0
    let t2 = day0.addingTimeInterval(3600)

    @Test func aFileEditedOnThisMacDuringTheRunIsNotOverwritten() async {
        let f = SyncFixture(.bidirectional)
        f.local.write("doc.txt", "v1", modified: t1)
        await f.run()
        f.share.write("doc.txt", "share v2 changed", modified: t2)
        let local = f.local
        f.fs.failing.set { op, _ in
            if op == "readChunk" { local.write("doc.txt", "edited here meanwhile", modified: Date()) }
        }
        let run = await f.run()
        #expect(run.errors.map(\.category) == [.fileChanged])
        #expect(f.local.read("doc.txt") == "edited here meanwhile")
        #expect(f.local.tree() == ["doc.txt"])
        // next run sees both changed: newer (here) wins, the share's kept as a copy
        f.fs.failing.set(nil)
        let next = await f.run()
        #expect(next.counts.conflicts == 1)
        #expect(f.share.read("doc.txt") == "edited here meanwhile")
    }

    @Test func aFileEditedOnTheShareDuringTheRunIsNotOverwritten() async {
        let f = SyncFixture(.upload)
        f.local.write("doc.txt", "v1", modified: t1)
        await f.run()
        f.local.write("doc.txt", "mac v2", modified: t2)
        let share = f.share
        f.fs.failing.set { op, path in
            if op == "write", path.hasSuffix(Names.partSuffix) { share.write("doc.txt", "someone else", modified: Date()) }
        }
        let run = await f.run()
        #expect(run.errors.map(\.category) == [.fileChanged])
        #expect(f.share.read("doc.txt") == "someone else")
        #expect(f.share.tree() == ["doc.txt"])
    }

    @Test func caseTwinsOnTheShareDoNotGrabThisMacsFile() {
        let plan = SyncPlanner.plan(.init(
            direction: .bidirectional, mirror: false,
            local: [LocalItem(path: "report.pdf", isDirectory: false, size: 9, modified: t2)],
            remote: [
                RemoteItem(path: "Report.pdf", isDirectory: false, size: 5, modified: t1),
                RemoteItem(path: "report.pdf", isDirectory: false, size: 3, modified: t1),
            ], state: [:]))
        let transfers = plan.actions.filter {
            switch $0 { case .upload, .download: true; default: false }
        }
        #expect(transfers.isEmpty)
    }

    @Test func aFirstRunKeepsTheOlderVersionToo() async {
        let f = SyncFixture(.bidirectional)
        f.local.write("both.txt", "old mac text", modified: t1)
        f.share.write("both.txt", "newer share text", modified: t2)
        let run = await f.run()
        #expect(run.counts.conflicts == 1)
        #expect(f.local.read("both.txt") == "newer share text")
        #expect(f.local.tree().filter { $0.contains("(conflict") }.count == 1)
    }

    @Test func fileCopierTargetChecks() async throws {
        let share = TempFolder("tc-share"), local = TempFolder("tc-local")
        let fs = DirectoryFileSystem(root: share.url)
        share.write("a.txt", "new", modified: t2)
        local.write("a.txt", "mine")
        let copier = FileCopier(remote: fs)
        await #expect(throws: Failure.self) {
            try await copier.download("a.txt", to: local.file("a.txt"), target: .absent)
        }
        await #expect(throws: Failure.self) {
            try await copier.download("a.txt", to: local.file("a.txt"), target: .unchanged(size: 99, modified: t1))
        }
        #expect(local.read("a.txt") == "mine")
        let kept = try await copier.download("a.txt", to: local.file("a.txt"), target: .keepBoth)
        #expect(kept.path == "a 2.txt")
        #expect(local.read("a.txt") == "mine" && local.read("a 2.txt") == "new")
        let up = local.write("up.txt", "up")
        share.write("up.txt", "theirs")
        await #expect(throws: Failure.self) { try await copier.upload(up, to: "up.txt", target: .absent) }
        #expect(share.read("up.txt") == "theirs")
        #expect(share.tree().sorted() == ["a.txt", "up.txt"])
    }
}
