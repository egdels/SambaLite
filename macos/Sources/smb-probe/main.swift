// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

// Developer tool: pokes the Samba test container (port 4452) and prints
// what the core does there, for checking assumptions step by step.

import Foundation
import SambaLiteCore

let connection = Connection(name: "probe", server: "127.0.0.1", port: 4452, share: "Data", username: "smbuser")
let fs = SMBFileSystem(connection: connection, password: "secret123")
let copier = FileCopier(remote: fs)

func step(_ label: String, _ body: () async throws -> Void) async {
    do {
        try await body()
        print(label, "ok")
    } catch {
        print(label, "FAILED", Failure.from(error), (error as NSError).userInfo)
    }
}

let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("smb-probe-\(UUID())")
try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
let file = tmp.appendingPathComponent("Grüße.bin")
try Data(repeating: 7, count: 3_000_000).write(to: file)
await step("upload new") { try await copier.upload(file, to: "probe/Grüße.bin") }
await step("list") { print(try await fs.list("probe").map(\.name)) }
await step("upload replace") { try await copier.upload(file, to: "probe/Grüße.bin") }
await step("download") { try await copier.download("probe/Grüße.bin", to: tmp.appendingPathComponent("back.bin")) }
await step("remove file") { try await fs.remove("probe/Grüße.bin") }
await step("remove dir") { try await fs.remove("probe") }
await step("ascii") {
    try await copier.upload(file, to: "probe2/plain.bin")
    try await fs.remove("probe2")
}
