// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing
@testable import SambaLiteCore

struct StorageTests {
    @Test func keychainStoresReplacesAndRemoves() throws {
        let store = KeychainCredentialStore(service: "de.schliweb.sambalite.tests")
        let id = UUID()
        defer { store.removePassword(for: id) }
        #expect(store.password(for: id) == nil)
        try store.setPassword("geheim ü", for: id)
        #expect(store.password(for: id) == "geheim ü")
        try store.setPassword("neu", for: id)
        #expect(store.password(for: id) == "neu")
        store.removePassword(for: id)
        #expect(store.password(for: id) == nil)
    }

    @Test func connectionFileRoundTripsWithTheAndroidFormat() throws {
        let json = """
        {"format":"sambalite-connections","version":1,"connections":[
          {"name":"NAS Data","server":"nas.local","port":445,"share":"Data","initialPath":"/Projects/2026/",
           "domain":"WORKGROUP","username":"","password":"","encryptData":true,"signingRequired":false,"asyncTransport":false},
          {"name":"Public folder","server":"192.168.2.5","share":"public","guest":true},
          {"server":"\\\\\\\\nas2","username":"anna","password":"pw"},
          {"server":"bad host"},
          {"name":"no server"}
        ]}
        """
        let entries = try ConnectionFile.read(Data(json.utf8))
        #expect(entries.count == 5)
        #expect(entries[0].connection.initialPath == "Projects/2026")
        #expect(entries[0].connection.credentialsRequired)
        #expect(entries[0].connection.encryptData)
        #expect(entries[1].connection.guest && !entries[1].connection.credentialsRequired)
        #expect(entries[2].connection.server == "nas2")
        #expect(entries[2].password == "pw" && !entries[2].connection.credentialsRequired)
        #expect(entries[3].skipReason != nil)
        #expect(entries[4].skipReason != nil)

        let written = try ConnectionFile.write([entries[0].connection, entries[2].connection], includeUsernames: false)
        let again = try ConnectionFile.read(written)
        #expect(again.map(\.connection.server) == ["nas.local", "nas2"])
        #expect(again[1].connection.username == "")
        #expect(String(decoding: written, as: UTF8.self).contains("password") == false)
        #expect(throws: Failure.self) { try ConnectionFile.read(Data(#"{"format":"other","connections":[]}"#.utf8)) }
    }

    @Test func serverFieldAcceptsWhatPeoplePaste() {
        var c = Connection(server: "smb://nas.local:4450/Data/Fotos/2026")
        c.normalizeServerField()
        #expect(c.server == "nas.local" && c.port == 4450 && c.share == "Data" && c.initialPath == "Fotos/2026")
        var d = Connection(server: "\\\\nas\\Public")
        d.normalizeServerField()
        #expect(d.server == "nas" && d.share == "Public")
        var e = Connection(server: "fe80::1")
        e.normalizeServerField()
        #expect(e.server == "fe80::1" && e.port == 445)
        #expect(Connection(server: "fe80::1", share: "x").serverURL?.absoluteString == "smb://[fe80::1]")
    }

    @Test func namesFollowTheRules() {
        #expect(Names.isIgnored(".DS_Store") && Names.isIgnored("._x") && Names.isIgnored("a.b1c2.sambalite-part"))
        #expect(!Names.isIgnored(".hidden"))
        #expect(Names.invalidSMBNameReason("a:b") != nil)
        #expect(Names.invalidSMBNameReason("end.") != nil)
        #expect(Names.invalidSMBNameReason("fine name.txt") == nil)
        #expect(Names.numbered("photo.jpg", 2) == "photo 2.jpg")
        #expect(Names.key("A\u{0308}") == "Ä")
        let long = String(repeating: "x", count: 300)
        #expect(Names.partName(long, size: 1, modified: Date()).utf8.count <= 250)
    }

    @Test func errorsAreSorted() {
        #expect(Failure.from(POSIXError(.ETIMEDOUT)).category == .serverUnreachable)
        #expect(Failure.from(POSIXError(.ENOENT)).category == .notFound)
        #expect(Failure.from(POSIXError(.EACCES)).category == .accessDenied)
        #expect(Failure.from(POSIXError(.ENOSPC)).category == .remoteFull)
        #expect(Failure.from(CancellationError()).category == .cancelled)
        #expect(FailureCategory.serverUnreachable.isTransient && !FailureCategory.loginRejected.isTransient)
    }
}
