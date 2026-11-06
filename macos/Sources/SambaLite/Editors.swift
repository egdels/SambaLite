// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AppKit
import SambaLiteCore
import SwiftUI

struct ConnectionEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State var draft: ConnectionDraft
    @State private var testing = false
    @State private var testResult: (ok: Bool, text: String)?
    @State private var shares: [String] = []
    @State private var loadingShares = false
    @FocusState private var serverFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(draft.isNew ? "New Connection" : "Edit Connection").font(.title2.weight(.semibold))
            Form {
                TextField("Name", text: $draft.connection.name, prompt: Text("e.g. NAS Documents"))
                TextField("Server", text: $draft.connection.server, prompt: Text("nas.local, 192.168.1.5 or smb://nas/Share"))
                    .onSubmit { draft.connection.normalizeServerField() }
                    .focused($serverFocused)
                    .onChange(of: serverFocused) { focused in
                        // "nas:4450" or a pasted smb:// link fills port and share
                        // once the field is left
                        if !focused { draft.connection.normalizeServerField() }
                    }
                HStack {
                    TextField("Share", text: $draft.connection.share, prompt: Text("Share name"))
                    if shares.isEmpty {
                        Button(loadingShares ? "Looking…" : "Find Shares") { loadShares() }
                            .disabled(loadingShares || cleaned.validationError != nil)
                            .help("Ask the server which shares it offers")
                    } else {
                        Picker("", selection: $draft.connection.share) {
                            ForEach(shares, id: \.self) { Text($0).tag($0) }
                        }
                        .labelsHidden()
                        .fixedSize()
                    }
                }
                TextField("Folder", text: $draft.connection.initialPath, prompt: Text("optional, opens first"))
                Toggle("Guest access (no login)", isOn: $draft.connection.guest)
                if !draft.connection.guest {
                    TextField("Username", text: $draft.connection.username)
                    SecureField("Password", text: $draft.password)
                    TextField("Domain", text: $draft.connection.domain, prompt: Text("optional, e.g. WORKGROUP"))
                }
                TextField("Port", value: $draft.connection.port, formatter: Self.portFormatter)
                    .frame(maxWidth: 160)
                Toggle("Require encryption (SMB 3)", isOn: $draft.connection.encryptData)
            }
            if let error = draft.connection.validationError, !draft.connection.server.isEmpty {
                Text(error).font(.callout).foregroundStyle(Color.problem)
            }
            if let testResult {
                Label(testResult.text, systemImage: testResult.ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                    .font(.callout)
                    .foregroundStyle(testResult.ok ? Color.healthy : Color.problem)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("The password is kept in your keychain.")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Button("Test Connection") { test() }
                    .disabled(testing || cleaned.validationError != nil || cleaned.share.isEmpty)
                if testing { ProgressView().controlSize(.small) }
                Spacer()
                Button("Cancel") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(draft.isNew ? "Add" : "Save") {
                    var final = draft
                    final.connection = cleaned
                    model.save(final)
                }
                .keyboardShortcut(.defaultAction)
                .disabled(cleaned.validationError != nil || cleaned.share.isEmpty)
            }
        }
        .padding(20)
        .frame(width: 480)
    }

    private static let portFormatter: NumberFormatter = {
        let f = NumberFormatter()
        f.allowsFloats = false
        f.minimum = 1
        f.maximum = 65535
        f.usesGroupingSeparator = false
        return f
    }()

    private var cleaned: Connection {
        var connection = draft.connection
        connection.normalizeServerField()
        connection.share = connection.share.trimmingCharacters(in: CharacterSet(charactersIn: "/\\ "))
        return connection
    }

    private func test() {
        testing = true
        testResult = nil
        let connection = cleaned
        let password = draft.password
        Task {
            do {
                try await SMBFileSystem.test(connection, password: password)
                testResult = (true, "Connected to \(connection.share) on \(connection.server).")
            } catch {
                let failure = Failure.from(error)
                testResult = (false, failure.message + (failure.category.advice.isEmpty ? "" : " " + failure.category.advice))
            }
            testing = false
        }
    }

    private func loadShares() {
        let connection = cleaned
        guard !loadingShares, connection.validationError == nil else { return }
        loadingShares = true
        let password = draft.password
        Task {
            defer { loadingShares = false }
            do {
                shares = try await SMBFileSystem.shares(of: connection, password: password)
                if shares.isEmpty {
                    testResult = (false, "The server lists no shares; enter the name by hand.")
                } else if draft.connection.share.isEmpty || !shares.contains(draft.connection.share) {
                    draft.connection.share = shares[0]
                }
            } catch {
                testResult = (false, "Could not list the shares: \(Failure.from(error).message)")
            }
        }
    }
}

struct SyncEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State var draft: SyncDraft

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(draft.isNew ? "Sync a Folder" : "Edit Sync").font(.title2.weight(.semibold))
            VStack(alignment: .leading, spacing: 12) {
                FormRow("On the share") {
                    Text(remoteText).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                }
                FormRow("On this Mac") {
                    HStack {
                        Text(draft.config.localPath.isEmpty ? "No folder chosen" : (draft.config.localPath as NSString).abbreviatingWithTildeInPath)
                            .foregroundStyle(draft.config.localPath.isEmpty ? Color.problem : .primary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Button("Choose…") { chooseFolder() }
                    }
                }
                FormRow("Direction") {
                    VStack(alignment: .leading, spacing: 4) {
                        Picker("", selection: $draft.config.direction) {
                            ForEach(SyncDirection.allCases, id: \.self) { direction in
                                Text(direction.title).tag(direction)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                        Text(directionHelp).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                }
                FormRow("Runs") {
                    Picker("", selection: $draft.config.intervalMinutes) {
                        ForEach(SyncConfig.intervals, id: \.self) { minutes in
                            Text(Format.interval(minutes)).tag(minutes)
                        }
                    }
                    .labelsHidden()
                    .fixedSize()
                }
                if draft.config.direction != .bidirectional {
                    FormRow("Deletions") {
                        VStack(alignment: .leading, spacing: 6) {
                            Toggle("Mirror deletions", isOn: $draft.config.mirror)
                            if draft.config.mirror {
                                Text("What disappears from the \(draft.config.direction == .upload ? "Mac" : "share") is removed from the \(draft.config.direction == .upload ? "share" : "Mac") too. Only files this sync copied itself, and a run that would remove more than half of them (and over 100) removes nothing.")
                                    .font(.caption)
                                    .foregroundStyle(Color.problem)
                                    .fixedSize(horizontal: false, vertical: true)
                                Toggle(draft.config.direction == .upload ? "Move into .sambalite-trash instead of deleting" : "Move to the Trash instead of deleting", isOn: $draft.config.mirrorUseTrash)
                            }
                        }
                    }
                }
                FormRow("") {
                    Toggle("Notify when it stops or starts working", isOn: $draft.config.notify)
                }
            }
            Text("Deleted files are not deleted on the other side unless mirroring is on: they come back on the next run. When a file changed on both sides, the newer version wins and the other is kept as a conflict copy.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(draft.isNew ? "Create and Sync" : "Save") { model.save(draft) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(draft.config.localPath.isEmpty)
            }
        }
        .padding(20)
        .frame(width: 520)
    }

    private var remoteText: String {
        let name = model.connection(draft.config.connectionID)?.displayName ?? "?"
        return draft.config.remotePath.isEmpty ? "\(name) (whole share)" : "\(name)/\(draft.config.remotePath)"
    }

    private var directionHelp: String {
        switch draft.config.direction {
        case .upload: "New and changed files on this Mac are copied to the share. The share's own changes stay there."
        case .download: "New and changed files on the share are copied to this Mac. Changes on the Mac stay here."
        case .bidirectional: "Changes travel both ways."
        }
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.prompt = "Choose"
        panel.message = "The folder on this Mac to keep in sync with \(remoteText)"
        if !draft.config.localPath.isEmpty { panel.directoryURL = URL(fileURLWithPath: draft.config.localPath) }
        if panel.runModal() == .OK, let url = panel.url {
            draft.config.localPath = url.path
        }
    }
}

/// A row with its label in a right-aligned column, as System Settings
/// lays out forms (Form on macOS 12 cannot put a label beside a custom row).
struct FormRow<Content: View>: View {
    let label: String
    let content: Content

    init(_ label: String, @ViewBuilder content: () -> Content) {
        self.label = label
        self.content = content()
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(label.isEmpty ? "" : label + ":")
                .frame(width: 96, alignment: .trailing)
            content
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct UploadConflictSheet: View {
    @EnvironmentObject private var model: AppModel
    let conflict: UploadConflict

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(conflict.existing.count == 1
                 ? "“\(RemotePath.name(conflict.existing.first!))” exists already."
                 : "\(conflict.existing.count) items exist already.")
                .font(.title3.weight(.semibold))
            Text("Of \(Format.count(conflict.plan.count, "file")) to upload, these are on the share already:")
                .foregroundStyle(.secondary)
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(conflict.existing.sorted().prefix(200), id: \.self) { path in
                        Text(path).font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 160)
            HStack {
                Button("Cancel") { model.resolve(conflict, .cancel) }
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button("Skip These") { model.resolve(conflict, .skip) }
                Button("Keep Both") { model.resolve(conflict, .keepBoth) }
                Button("Replace") { model.resolve(conflict, .replace) }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 480)
    }
}
