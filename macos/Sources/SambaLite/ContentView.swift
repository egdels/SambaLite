// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import SambaLiteCore
import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    /// The navigation view is the window's root from the first frame on
    /// (Crate's finding: a split view that replaces another view later
    /// comes up without its toolbar on macOS 26). NavigationView, because
    /// the app runs on macOS 12.
    var body: some View {
        NavigationView {
            Sidebar()
                .frame(minWidth: 210, idealWidth: 230)
            Group {
                switch model.selection {
                case .connection(let id):
                    if let connection = model.connection(id) {
                        let browser = model.browser(for: connection)
                        // a new model (settings changed) is a new view, so it loads
                        BrowserView(browser: browser)
                            .id(ObjectIdentifier(browser))
                    } else {
                        Placeholder()
                    }
                case .transfers:
                    TransfersView()
                case .syncs:
                    SyncsView()
                case nil:
                    Placeholder()
                }
            }
            .frame(minWidth: 520, maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(nsColor: .textBackgroundColor), ignoresSafeAreaEdges: [])
        }
        .modifier(ClearToolbar())
        .background(TitlebarStyle())
        .overlay(alignment: .bottom) { MessageLine() }
        .sheet(item: $model.editingConnection) { draft in
            ConnectionEditor(draft: draft)
        }
        .sheet(item: $model.editingSync) { draft in
            SyncEditor(draft: draft)
        }
        .sheet(item: $model.uploadConflict) { conflict in
            UploadConflictSheet(conflict: conflict)
        }
    }
}

/// Shown when nothing is selected: the way to the first connection.
struct Placeholder: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "externaldrive.connected.to.line.below")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text(model.connections.isEmpty ? "Connect to a share." : "Choose a share in the sidebar.")
                .font(.title2)
            Text("SambaLite talks to SMB servers directly: a NAS, a Windows PC, a Samba server. Nothing is mounted, nothing goes to a cloud.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 420)
            if model.connections.isEmpty {
                HStack {
                    Button("Add Connection…") { model.newConnection() }
                        .keyboardShortcut(.defaultAction)
                    Button("Import Connections…") { model.importConnections() }
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Errors in red, notices in the accent colour, at the bottom of the
/// window; a click or ten seconds dismiss them.
struct MessageLine: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 6) {
            if let notice = model.notice {
                line(notice, color: Color.accentColor, icon: "checkmark.circle.fill") { model.notice = nil }
                    .task(id: notice) {
                        try? await Task.sleep(nanoseconds: 6_000_000_000)
                        if model.notice == notice { model.notice = nil }
                    }
            }
            if let error = model.lastError {
                line(error, color: .red, icon: "exclamationmark.triangle.fill") { model.lastError = nil }
                    .task(id: error) {
                        try? await Task.sleep(nanoseconds: 10_000_000_000)
                        if model.lastError == error { model.lastError = nil }
                    }
            }
        }
        .padding(.bottom, 14)
        .padding(.horizontal, 20)
        .animation(.easeOut(duration: 0.2), value: model.notice)
        .animation(.easeOut(duration: 0.2), value: model.lastError)
    }

    private func line(_ text: String, color: Color, icon: String, dismiss: @escaping () -> Void) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: icon)
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .font(.callout)
        .foregroundStyle(.white)
        .padding(.vertical, 8)
        .padding(.horizontal, 14)
        .background(color, in: Capsule())
        .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
        .frame(maxWidth: 560)
        .contentShape(Rectangle())
        .onTapGesture(perform: dismiss)
        .help("Click to dismiss")
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

struct Sidebar: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        List(selection: $model.selection) {
            Section("Shares") {
                ForEach(model.connections) { connection in
                    ConnectionRow(connection: connection)
                        .tag(SidebarItem.connection(connection.id))
                        .contextMenu {
                            Button("Edit Connection…") { model.edit(connection) }
                            Button("Sync a Folder of This Share…") {
                                model.setUpSync(connection: connection, remotePath: RemotePath.normalized(connection.initialPath))
                            }
                            Divider()
                            Button("Delete Connection…") { model.delete(connection) }
                        }
                }
            }
            Section("Activity") {
                HStack {
                    Label("Transfers", systemImage: "arrow.up.arrow.down")
                    Spacer()
                    if model.activeTransferCount > 0 {
                        Text("\(model.activeTransferCount)")
                            .font(.caption.monospacedDigit())
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(.quaternary, in: Capsule())
                    }
                }
                .tag(SidebarItem.transfers)
                HStack {
                    Label("Syncs", systemImage: "arrow.triangle.2.circlepath")
                    Spacer()
                    if !model.syncs.running.isEmpty {
                        ProgressView().controlSize(.mini)
                    } else if !model.syncs.configs.isEmpty {
                        Circle()
                            .fill([Color.healthy, .warning, .problem][model.overallSeverity])
                            .frame(width: 8, height: 8)
                    }
                }
                .tag(SidebarItem.syncs)
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom) {
            HStack {
                Button { model.newConnection() } label: { Image(systemName: "plus") }
                    .buttonStyle(.borderless)
                    .help("Add a connection")
                Spacer()
                if !model.networkAvailable {
                    Label("Offline", systemImage: "wifi.slash")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(10)
        }
    }
}

struct ConnectionRow: View {
    @EnvironmentObject private var model: AppModel
    let connection: Connection

    var body: some View {
        HStack(spacing: 6) {
            Label {
                VStack(alignment: .leading, spacing: 1) {
                    Text(connection.displayName).lineLimit(1)
                    Text(connection.server + (connection.share.isEmpty ? "" : "/" + connection.share))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            } icon: {
                Image(systemName: connection.encryptData ? "lock.shield" : "externaldrive")
            }
            Spacer(minLength: 0)
            if connection.credentialsRequired {
                Image(systemName: "key")
                    .foregroundStyle(.orange)
                    .help("Credentials needed: edit the connection")
            }
        }
    }
}
