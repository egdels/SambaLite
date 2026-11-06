// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import SambaLiteCore
import SwiftUI

struct BrowserView: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var browser: BrowserModel

    var body: some View {
        VStack(spacing: 0) {
            PathBar(browser: browser)
            Divider()
            ZStack {
                if browser.connection.credentialsRequired {
                    CredentialsNeeded(connection: browser.connection)
                } else if let failure = browser.failure, browser.items.isEmpty {
                    FailureView(failure: failure, browser: browser)
                } else {
                    FileTableView(browser: browser, items: browser.visibleItems, synced: syncedPaths)
                    if browser.visibleItems.isEmpty && !browser.loading {
                        Text(browser.filter.isEmpty ? "This folder is empty.\nDrop files here to upload them." : "Nothing matches “\(browser.filter)”.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .allowsHitTesting(false)
                    }
                }
            }
            Divider()
            StatusBar(browser: browser)
        }
        .navigationTitle(browser.title)
        .toolbar {
            ToolbarItemGroup(placement: .navigation) {
                Button { browser.back() } label: { Label("Back", systemImage: "chevron.left") }
                    .disabled(!browser.canGoBack)
                    .help("Back")
                Button { browser.forward() } label: { Label("Forward", systemImage: "chevron.right") }
                    .disabled(!browser.canGoForward)
                    .help("Forward")
            }
            ToolbarItemGroup(placement: .primaryAction) {
                Button { browser.uploadPanel() } label: { Label("Upload", systemImage: "square.and.arrow.up") }
                    .help("Upload files or folders here")
                Button { browser.download(browser.selectedItems) } label: {
                    Label("Download", systemImage: "square.and.arrow.down")
                }
                .disabled(browser.selection.isEmpty)
                .help("Download the selection to the Downloads folder")
                Button { browser.newFolder() } label: { Label("New Folder", systemImage: "folder.badge.plus") }
                    .help("New folder")
                Button {
                    let selected = browser.selectedItems
                    browser.setUpSync(selected.count == 1 && selected[0].isDirectory ? selected[0] : nil)
                } label: {
                    Label("Sync", systemImage: "arrow.triangle.2.circlepath")
                }
                .help("Keep this folder in sync with a folder on this Mac")
                Button { browser.reload() } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                    .help("Refresh")
            }
        }
        .searchable(text: $browser.filter, placement: .toolbar, prompt: "Filter")
        .onAppear { browser.appear() }
    }

    private var syncedPaths: Set<String> {
        Set(model.syncs(of: browser.connection).map(\.remotePath))
    }
}

/// The folders from the share's root to here; each is a button.
struct PathBar: View {
    @ObservedObject var browser: BrowserModel

    var body: some View {
        HStack(spacing: 2) {
            Button { browser.up() } label: { Image(systemName: "arrow.up") }
                .buttonStyle(.borderless)
                .disabled(!browser.canGoUp)
                .help("Enclosing folder")
                .padding(.trailing, 6)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 2) {
                    ForEach(Array(browser.breadcrumbs.enumerated()), id: \.offset) { index, crumb in
                        if index > 0 {
                            Image(systemName: "chevron.compact.right")
                                .foregroundStyle(.tertiary)
                        }
                        Button {
                            browser.go(to: crumb.path)
                        } label: {
                            HStack(spacing: 4) {
                                if index == 0 { Image(systemName: "externaldrive") }
                                Text(crumb.name)
                            }
                            .padding(.horizontal, 5)
                            .padding(.vertical, 2)
                            .background(
                                index == browser.breadcrumbs.count - 1 ? Color.primary.opacity(0.06) : .clear,
                                in: RoundedRectangle(cornerRadius: 5))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(index == browser.breadcrumbs.count - 1 ? .primary : .secondary)
                    }
                }
            }
            if browser.loading {
                ProgressView().controlSize(.small).scaleEffect(0.7)
            }
        }
        .font(.callout)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }
}

struct StatusBar: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var browser: BrowserModel

    var body: some View {
        HStack(spacing: 12) {
            Text(summary)
            Spacer()
            if let busy = browser.busy {
                ProgressView().controlSize(.small).scaleEffect(0.6)
                Text(busy)
            } else if let config = model.syncs(of: browser.connection).first(where: { $0.remotePath == browser.path }) {
                Image(systemName: "arrow.triangle.2.circlepath")
                Text("Synced with \(config.name) · \(model.health(of: config).title)")
            }
            Toggle("Hidden Files", isOn: $browser.showHidden)
                .toggleStyle(.checkbox)
                .controlSize(.small)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
    }

    private var summary: String {
        let visible = browser.visibleItems
        let selected = browser.selectedItems
        if !selected.isEmpty {
            let bytes = selected.filter { !$0.isDirectory }.reduce(Int64(0)) { $0 + $1.size }
            return "\(selected.count) of \(visible.count) selected" + (bytes > 0 ? ", \(Format.bytes(bytes))" : "")
        }
        return Format.count(visible.count, "item")
    }
}

struct FailureView: View {
    @EnvironmentObject private var model: AppModel
    let failure: Failure
    @ObservedObject var browser: BrowserModel

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text(failure.category.title).font(.title2)
            Text(failure.message)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 440)
            Text(failure.category.advice)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 440)
            HStack {
                Button("Try Again") { browser.reload() }
                    .keyboardShortcut(.defaultAction)
                if failure.category == .loginRejected || failure.category == .notFound {
                    Button("Edit Connection…") { model.edit(browser.connection) }
                }
                if failure.category == .notFound, !browser.path.isEmpty {
                    Button("Go to Share Root") { browser.go(to: "") }
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var icon: String {
        switch failure.category {
        case .serverUnreachable: "wifi.exclamationmark"
        case .loginRejected: "person.crop.circle.badge.exclamationmark"
        case .accessDenied: "lock"
        default: "exclamationmark.triangle"
        }
    }
}

struct CredentialsNeeded: View {
    @EnvironmentObject private var model: AppModel
    let connection: Connection

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "key")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("Credentials needed").font(.title2)
            Text("This connection was imported without username or password.")
                .foregroundStyle(.secondary)
            Button("Enter Credentials…") { model.edit(connection) }
                .keyboardShortcut(.defaultAction)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
