// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing
@testable import SambaLiteCore

struct SyncCoordinatorTests {
    let share = TempFolder("c-share")
    let local = TempFolder("c-local")
    let data = TempFolder("c-data")
    let connection = Connection(name: "T", server: "x", share: "S")

    func make() -> SyncCoordinator {
        let connection = connection
        let fs = DirectoryFileSystem(root: share.url)
        return SyncCoordinator(
            folder: data.url, resolve: { $0 == connection.id ? (connection, "") : nil },
            makeFileSystem: { _, _ in fs })
    }

    @Test func runsKeepsHistoryAndLogAndSurvivesARestart() async {
        share.write("a.txt", "a", modified: day0)
        let coordinator = make()
        await coordinator.start(tick: 3600)
        let config = SyncConfig(connectionID: connection.id, localPath: local.url.path, remotePath: "", intervalMinutes: 0)
        await coordinator.add(config)
        await coordinator.waitUntilIdle()
        #expect(local.read("a.txt") == "a")
        let runs = await coordinator.snapshot().history[config.id] ?? []
        #expect(runs.map(\.status) == [.completed])
        #expect(await coordinator.log(of: config.id).contains("downloaded\ta.txt"))
        #expect(await coordinator.state(of: config.id).keys.sorted() == ["a.txt"])

        let again = make()
        await again.start(tick: 3600)
        #expect(await again.snapshot().configs == [config])
        #expect(await again.snapshot().history[config.id]?.count == 1)
        await again.runNow(config.id)
        await again.waitUntilIdle()
        let after = await again.snapshot().history[config.id] ?? []
        #expect(after.count == 2)
        #expect(after.last?.counts.transferred == 0)
    }

    @Test func historyIsCappedAtTwenty() async {
        let coordinator = make()
        await coordinator.start(tick: 3600)
        let config = SyncConfig(connectionID: connection.id, localPath: local.url.path, remotePath: "", intervalMinutes: 0)
        await coordinator.add(config)
        for _ in 0..<22 {
            await coordinator.waitUntilIdle()
            await coordinator.runNow(config.id)
        }
        await coordinator.waitUntilIdle()
        #expect(await coordinator.snapshot().history[config.id]?.count == SyncCoordinator.historyLength)
    }

    @Test func aChangedFolderStartsOverAndRemovalCleansUp() async {
        share.write("a.txt", "a", modified: day0)
        let coordinator = make()
        await coordinator.start(tick: 3600)
        var config = SyncConfig(connectionID: connection.id, localPath: local.url.path, remotePath: "", intervalMinutes: 0)
        await coordinator.add(config)
        await coordinator.waitUntilIdle()
        #expect(await coordinator.state(of: config.id).count == 1)
        config.localPath = local.file("elsewhere").path
        await coordinator.update(config)
        #expect(await coordinator.state(of: config.id).isEmpty)
        await coordinator.remove(config.id)
        #expect(await coordinator.snapshot().configs.isEmpty)
        #expect(await coordinator.log(of: config.id).isEmpty)
    }

    @Test func dueTimes() {
        let config = SyncConfig(connectionID: UUID(), localPath: "/x", remotePath: "", intervalMinutes: 60)
        let t = day0
        func run(_ status: SyncRunStatus, _ started: Date, _ failure: Failure? = nil) -> SyncRunResult {
            SyncRunResult(
                id: UUID(), configID: config.id, started: started, ended: started, status: status, counts: SyncCounts(),
                failure: failure, errors: [], errorCount: 0, checked: 0, mirrorNote: nil)
        }
        #expect(SyncCoordinator.nextRun(of: config, runs: []) == .distantPast)
        #expect(SyncCoordinator.nextRun(of: config, runs: [run(.completed, t)]) == t.addingTimeInterval(3600))
        let down = Failure(.serverUnreachable, "x")
        #expect(SyncCoordinator.nextRun(of: config, runs: [run(.failed, t, down)]) == t.addingTimeInterval(120))
        #expect(SyncCoordinator.nextRun(of: config, runs: [run(.failed, t, down), run(.failed, t, down), run(.failed, t, down)]) == t.addingTimeInterval(480))
        #expect(SyncCoordinator.nextRun(of: config, runs: [run(.completed, t), run(.stopped, t.addingTimeInterval(10))]) == t.addingTimeInterval(3600))
        var manual = config
        manual.intervalMinutes = 0
        #expect(SyncCoordinator.nextRun(of: manual, runs: []) == nil)
    }

    @Test func noticesOnlyOnChange() {
        let config = SyncConfig(connectionID: UUID(), localPath: "/x/Docs", remotePath: "")
        #expect(SyncNotice.message(for: config, before: .upToDate, after: .failed(.serverUnreachable))?.title == "Docs does not sync")
        #expect(SyncNotice.message(for: config, before: .failed(.serverUnreachable), after: .failed(.loginRejected)) == nil)
        #expect(SyncNotice.message(for: config, before: .failed(.serverUnreachable), after: .upToDate)?.title == "Docs syncs again")
        #expect(SyncNotice.message(for: config, before: .neverRun, after: .upToDate) == nil)
        var muted = config
        muted.notify = false
        #expect(SyncNotice.message(for: muted, before: .upToDate, after: .failed(.other)) == nil)
    }
}

extension SyncCoordinatorTests {
    @Test func scheduledRunsWaitOnAnExpensiveNetwork() async {
        let coordinator = make()
        await coordinator.setNetworkAvailable(true, expensive: true)
        await coordinator.start(tick: 3600)
        let careful = SyncConfig(connectionID: connection.id, localPath: local.url.path, remotePath: "", intervalMinutes: 15, avoidExpensiveNetworks: true)
        await coordinator.add(careful)  // a new sync runs at once, by hand
        await coordinator.waitUntilIdle()
        #expect(await coordinator.snapshot().history[careful.id]?.count == 1)
        await coordinator.runDue()
        await coordinator.waitUntilIdle()
        #expect(await coordinator.snapshot().history[careful.id]?.count == 1)
    }

    @Test func oldConfigFilesStillLoad() throws {
        let json = #"[{"id":"\#(UUID().uuidString)","connectionID":"\#(UUID().uuidString)","localPath":"/x","remotePath":"","direction":"upload","intervalMinutes":60,"mirror":false,"mirrorUseTrash":true,"enabled":true}]"#
        let configs = try JSONDecoder().decode([SyncConfig].self, from: Data(json.utf8))
        #expect(configs[0].notify && !configs[0].avoidExpensiveNetworks)
    }
}

extension SyncCoordinatorTests {
    /// A run still finishing after its folder was changed must not write
    /// the old baseline back (the review's first finding).
    @Test func aChangedFolderIsNotRefilledByTheOldRun() async throws {
        for i in 0..<120 { share.write("f\(i).txt", "\(i)", modified: day0) }
        let connection = connection
        let fs = DirectoryFileSystem(root: share.url)
        fs.failing.set { op, _ in if op == "read" { Thread.sleep(forTimeInterval: 0.005) } }
        let coordinator = SyncCoordinator(
            folder: data.url, resolve: { $0 == connection.id ? (connection, "") : nil },
            makeFileSystem: { _, _ in fs })
        await coordinator.start(tick: 3600)
        var config = SyncConfig(connectionID: connection.id, localPath: local.url.path, remotePath: "", intervalMinutes: 0)
        await coordinator.add(config)
        try await Task.sleep(nanoseconds: 300_000_000)  // mid-run, after the first saves
        config.localPath = local.file("other").path
        await coordinator.update(config)
        await coordinator.waitUntilIdle()
        #expect(await coordinator.state(of: config.id).isEmpty)
    }
}
