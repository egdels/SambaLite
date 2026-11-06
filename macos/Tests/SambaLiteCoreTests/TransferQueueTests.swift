// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing
@testable import SambaLiteCore

struct TransferQueueTests {
    let share = TempFolder("q-share")
    let local = TempFolder("q-local")
    let state = TempFolder("q-state")
    let connection = Connection(name: "T", server: "x", share: "S")

    func makeQueue(
        _ fs: DirectoryFileSystem, backoff: [TimeInterval] = [0.05, 0.05, 0.05, 0.05], parallel: Int = 2
    ) -> TransferQueue {
        let connection = connection
        return TransferQueue(
            file: JSONFile(state.file("transfers.json")), maxParallel: parallel, backoff: backoff,
            resolve: { id in id == connection.id ? (connection, "pw") : nil },
            makeFileSystem: { _, _ in fs })
    }

    func download(_ remote: String, _ localName: String) -> Transfer {
        Transfer(kind: .download, connectionID: connection.id, localPath: local.file(localName).path, remotePath: remote, size: 0)
    }

    func upload(_ localName: String, _ remote: String) -> Transfer {
        Transfer(kind: .upload, connectionID: connection.id, localPath: local.file(localName).path, remotePath: remote, size: 0)
    }

    @Test func runsUploadsAndDownloads() async throws {
        share.write("in/a.txt", "A")
        share.write("in/b.txt", "B")
        local.write("c.txt", "C")
        let queue = makeQueue(DirectoryFileSystem(root: share.url))
        await queue.start()
        await queue.enqueue([download("in/a.txt", "a.txt"), download("in/b.txt", "sub/b.txt"), upload("c.txt", "out/c.txt")])
        await queue.waitUntilIdle()
        let transfers = await queue.snapshot().transfers
        #expect(transfers.map(\.status) == [.completed, .completed, .completed])
        #expect(local.read("a.txt") == "A")
        #expect(local.read("sub/b.txt") == "B")
        #expect(share.read("out/c.txt") == "C")
    }

    @Test func transientErrorsAreRetriedThenFail() async throws {
        share.write("a.txt", "A")
        let fs = DirectoryFileSystem(root: share.url)
        let calls = Locked(0)
        fs.failing.set { op, _ in
            if op == "item" {
                calls.update { $0 += 1 }
                throw Failure(.serverUnreachable, "down")
            }
        }
        let queue = makeQueue(fs)
        await queue.start()
        await queue.enqueue([download("a.txt", "a.txt")])
        await queue.waitUntilIdle()
        let transfer = await queue.snapshot().transfers[0]
        #expect(transfer.status == .failed)
        #expect(transfer.attempts == TransferQueue.maxAttempts)
        #expect(transfer.error?.category == .serverUnreachable)
        #expect(calls.get() == TransferQueue.maxAttempts)

        fs.failing.set(nil)
        await queue.retry([transfer.id])
        await queue.waitUntilIdle()
        #expect(await queue.snapshot().transfers[0].status == .completed)
    }

    @Test func permanentErrorsFailAtOnce() async throws {
        let queue = makeQueue(DirectoryFileSystem(root: share.url))
        await queue.start()
        await queue.enqueue([download("missing.txt", "m.txt")])
        await queue.waitUntilIdle()
        let transfer = await queue.snapshot().transfers[0]
        #expect(transfer.status == .failed)
        #expect(transfer.attempts == 1)
        #expect(transfer.error?.category == .notFound)
    }

    @Test func withoutNetworkNothingIsCountedAndItResumesWhenBack() async throws {
        share.write("a.txt", "A")
        let fs = DirectoryFileSystem(root: share.url)
        let queue = makeQueue(fs)
        await queue.setNetworkAvailable(false)
        await queue.start()
        await queue.enqueue([download("a.txt", "a.txt")])
        try await Task.sleep(nanoseconds: 200_000_000)
        #expect(await queue.snapshot().transfers[0].status == .pending)
        await queue.setNetworkAvailable(true)
        await queue.waitUntilIdle()
        #expect(await queue.snapshot().transfers[0].status == .completed)
    }

    @Test func duplicatesAreLeftOut() async throws {
        local.write("c.txt", "C")
        let queue = makeQueue(DirectoryFileSystem(root: share.url))
        await queue.setNetworkAvailable(false)  // keep them pending
        await queue.start()
        #expect(await queue.enqueue([upload("c.txt", "c.txt")]).isEmpty)
        #expect(await queue.enqueue([upload("c.txt", "c.txt")]).count == 1)
    }

    @Test func cancelStopsARunningTransferAndCleansUp() async throws {
        share.write("big.bin", randomData(2_000_000))
        let fs = DirectoryFileSystem(root: share.url)
        fs.chunkSize = 10_000
        fs.failing.set { op, _ in if op == "readChunk" { Thread.sleep(forTimeInterval: 0.005) } }
        let queue = makeQueue(fs)
        await queue.start()
        let transfer = download("big.bin", "big.bin")
        await queue.enqueue([transfer])
        try await Task.sleep(nanoseconds: 150_000_000)
        #expect(await queue.snapshot().transfers[0].status == .active)
        await queue.cancel([transfer.id])
        await queue.waitUntilIdle()
        #expect(await queue.snapshot().transfers[0].status == .cancelled)
        #expect(local.tree().isEmpty)
    }

    @Test func activeTransfersResumeAfterARestart() async throws {
        let data = randomData(500_000)
        share.write("big.bin", data, modified: day0)
        // an earlier run got 200 KB into the part file and the app ended
        let part = Names.partName("big.bin", size: 500_000, modified: day0)
        local.write(part, data.prefix(200_000))
        var interrupted = download("big.bin", "big.bin")
        interrupted.status = .active
        try JSONFile<[Transfer]>(state.file("transfers.json")).save([interrupted])

        let fs = DirectoryFileSystem(root: share.url)
        fs.chunkSize = 100_000
        let chunks = Locked(0)
        fs.failing.set { op, _ in if op == "readChunk" { chunks.update { $0 += 1 } } }
        let queue = makeQueue(fs)
        await queue.start()
        await queue.waitUntilIdle()
        #expect(await queue.snapshot().transfers[0].status == .completed)
        #expect(try Data(contentsOf: local.file("big.bin")) == data)
        // 300 KB left to read: three chunks and the empty end, not six
        #expect(chunks.get() <= 4)
    }

    @Test func finishedEntriesExpireAfterAWeek() async throws {
        var old = download("a", "a")
        old.status = .completed
        old.updated = Date().addingTimeInterval(-8 * 24 * 3600)
        var recent = download("b", "b")
        recent.status = .completed
        try JSONFile<[Transfer]>(state.file("transfers.json")).save([old, recent])
        let queue = makeQueue(DirectoryFileSystem(root: share.url))
        await queue.start()
        #expect(await queue.snapshot().transfers.map(\.remotePath) == ["b"])
    }

    @Test func manyTransfersEachRunOnce() async throws {
        for i in 0..<40 { share.write("f\(i).bin", randomData(20_000)) }
        let fs = DirectoryFileSystem(root: share.url)
        let opened = Locked<[String: Int]>([:])
        fs.failing.set { op, path in if op == "read" { opened.update { $0[path, default: 0] += 1 } } }
        let queue = makeQueue(fs, parallel: 3)
        await queue.start()
        await queue.enqueue((0..<40).map { download("f\($0).bin", "f\($0).bin") })
        await queue.waitUntilIdle()
        #expect(await queue.snapshot().transfers.allSatisfy { $0.status == .completed })
        #expect(opened.get().count == 40 && opened.get().values.allSatisfy { $0 == 1 })
        // queued after the workers went idle: picked up all the same
        share.write("late.bin", "late")
        await queue.enqueue([download("late.bin", "late.bin")])
        await queue.waitUntilIdle()
        #expect(local.read("late.bin") == "late")
    }

    @Test func downloadsNeverReplaceALocalFile() async throws {
        share.write("x.txt", "from share")
        local.write("x.txt", "mine")
        let queue = makeQueue(DirectoryFileSystem(root: share.url))
        await queue.start()
        await queue.enqueue([download("x.txt", "x.txt")])
        await queue.waitUntilIdle()
        #expect(local.read("x.txt") == "mine")
        #expect(local.read("x 2.txt") == "from share")
    }

    @Test func rateTrackerMeasuresOverThreeSeconds() {
        var rate = RateTracker()
        let t0 = Date()
        rate.add(0, at: t0)
        #expect(rate.bytesPerSecond(at: t0) == nil)
        rate.add(1_000_000, at: t0.addingTimeInterval(1))
        rate.add(2_000_000, at: t0.addingTimeInterval(2))
        #expect(rate.bytesPerSecond(at: t0.addingTimeInterval(2)) == 1_000_000)
        #expect(rate.bytesPerSecond(at: t0.addingTimeInterval(13)) == 0)
    }
}
