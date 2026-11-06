// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AppKit
import SambaLiteCore
import SwiftUI
import UniformTypeIdentifiers

enum SortKey: String, CaseIterable {
    case name, modified, size, kind
}

/// One connection's file browser: where it stands, what it shows, and
/// the file operations on it. It keeps an SMB session of its own.
@MainActor
final class BrowserModel: ObservableObject {
    let connection: Connection
    let fs: SMBFileSystem
    /// For the drag-out copies, which open sessions of their own.
    let passwordForPromises: String
    private weak var app: AppModel?

    @Published private(set) var path: String
    @Published private(set) var items: [RemoteItem] = []
    @Published private(set) var loading = false
    @Published private(set) var failure: Failure?
    @Published var selection: Set<String> = []
    @Published var filter = ""
    @Published var showHidden = false
    @Published var sortKey: SortKey = .name
    @Published var ascending = true
    @Published private(set) var busy: String?
    private var backStack: [String] = []
    private var forwardStack: [String] = []
    private var loadTask: Task<Void, Never>?
    private var loadedOnce = false

    init(connection: Connection, password: String, app: AppModel) {
        self.connection = connection
        self.fs = SMBFileSystem(connection: connection, password: password)
        self.passwordForPromises = password
        self.app = app
        self.path = RemotePath.normalized(connection.initialPath)
    }

    deinit {
        let fs = fs
        Task { await fs.disconnect() }
    }

    func appear() {
        if !loadedOnce { reload() }
    }

    // MARK: What is shown

    var visibleItems: [RemoteItem] {
        let needle = filter.trimmingCharacters(in: .whitespaces)
        let shown = items.filter { item in
            if !showHidden && (item.name.hasPrefix(".") || Names.isIgnored(item.name)) { return false }
            if !needle.isEmpty && item.name.range(of: needle, options: [.caseInsensitive, .diacriticInsensitive]) == nil {
                return false
            }
            return true
        }
        return shown.sorted { a, b in
            if a.isDirectory != b.isDirectory { return a.isDirectory }
            let order: ComparisonResult
            switch sortKey {
            case .name: order = a.name.localizedStandardCompare(b.name)
            case .modified: order = a.modified == b.modified ? a.name.localizedStandardCompare(b.name) : (a.modified < b.modified ? .orderedAscending : .orderedDescending)
            case .size: order = a.size == b.size ? a.name.localizedStandardCompare(b.name) : (a.size < b.size ? .orderedAscending : .orderedDescending)
            case .kind:
                let ka = Self.kind(of: a), kb = Self.kind(of: b)
                order = ka == kb ? a.name.localizedStandardCompare(b.name) : ka.localizedStandardCompare(kb)
            }
            return ascending ? order == .orderedAscending : order == .orderedDescending
        }
    }

    static func kind(of item: RemoteItem) -> String {
        if item.isDirectory { return "Folder" }
        let ext = (item.name as NSString).pathExtension
        if ext.isEmpty { return "Document" }
        return UTType(filenameExtension: ext)?.localizedDescription ?? ext.uppercased() + " file"
    }

    var breadcrumbs: [(name: String, path: String)] {
        var result = [(connection.share.isEmpty ? connection.server : connection.share, "")]
        var current = ""
        for component in RemotePath.components(path) {
            current = RemotePath.join(current, component)
            result.append((component, current))
        }
        return result
    }

    var title: String { path.isEmpty ? connection.displayName : RemotePath.name(path) }

    var selectedItems: [RemoteItem] { items.filter { selection.contains($0.path) } }

    var canGoBack: Bool { !backStack.isEmpty }
    var canGoForward: Bool { !forwardStack.isEmpty }
    var canGoUp: Bool { !path.isEmpty }

    // MARK: Navigation

    func go(to newPath: String) {
        guard newPath != path else { return reload() }
        backStack.append(path)
        forwardStack.removeAll()
        path = newPath
        selection = []
        filter = ""
        reload()
    }

    func back() {
        guard let previous = backStack.popLast() else { return }
        forwardStack.append(path)
        path = previous
        selection = []
        reload()
    }

    func forward() {
        guard let next = forwardStack.popLast() else { return }
        backStack.append(path)
        path = next
        selection = []
        reload()
    }

    func up() {
        guard canGoUp else { return }
        let child = path
        go(to: RemotePath.parent(path))
        selection = [child]
    }

    func reload() {
        loadTask?.cancel()
        loading = true
        let target = path
        loadTask = Task {
            do {
                let listed = try await fs.list(target)
                guard !Task.isCancelled, target == path else { return }
                items = listed
                failure = nil
                selection = selection.filter { selected in listed.contains { $0.path == selected } }
            } catch {
                guard !Task.isCancelled, target == path else { return }
                items = []
                failure = Failure.from(error)
            }
            loading = false
            loadedOnce = true
        }
    }

    /// After uploads: a quiet reload, at most twice a second.
    private var refreshPending = false
    func refreshSoon() {
        guard !refreshPending else { return }
        refreshPending = true
        Task {
            try? await Task.sleep(nanoseconds: 500_000_000)
            refreshPending = false
            if !loading { reload() }
        }
    }

    // MARK: Opening

    func open(_ item: RemoteItem) {
        if item.isDirectory {
            go(to: item.path)
        } else {
            openFile(item)
        }
    }

    /// Opens a file with its app: copied into the cache first (again only
    /// when it changed on the share).
    func openFile(_ item: RemoteItem) {
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("de.schliweb.sambalite/Open/\(connection.id.uuidString)", isDirectory: true)
        let target = cache.appendingPathComponent(item.path)
        if let cached = LocalFiles.stat(target), cached.size == item.size,
           abs(cached.modified.timeIntervalSince(item.modified)) < 1
        {
            NSWorkspace.shared.open(target)
            return
        }
        busy = "Opening \(item.name)…"
        let copier = FileCopier(remote: fs)
        Task {
            defer { busy = nil }
            do {
                try await copier.download(item.path, to: target) { done, total in
                    let percent = total > 0 ? Int(Double(done) / Double(total) * 100) : 0
                    Task { @MainActor in if self.busy != nil { self.busy = "Opening \(item.name)… \(percent) %" } }
                }
                NSWorkspace.shared.open(target)
            } catch {
                app?.show(error)
            }
        }
    }

    // MARK: Operations

    func download(_ items: [RemoteItem], to folder: URL? = nil) {
        guard let app, !items.isEmpty else { return }
        app.download(items, from: connection, to: folder ?? app.downloadsFolder)
    }

    func downloadAsking(_ items: [RemoteItem]) {
        guard let app, let folder = app.chooseDownloadFolder() else { return }
        download(items, to: folder)
    }

    func upload(_ urls: [URL], into folder: String? = nil) {
        app?.upload(urls, to: folder ?? path, connection: connection)
    }

    func uploadPanel() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        panel.prompt = "Upload"
        panel.message = "Choose files or folders to upload to \(title)"
        guard panel.runModal() == .OK else { return }
        upload(panel.urls)
    }

    func newFolder() {
        guard let name = ask("New Folder", "Name of the new folder:", default: "untitled folder", confirm: "Create") else { return }
        if let reason = Names.invalidSMBNameReason(name) { return app?.show(error: "“\(name)” \(reason).") ?? () }
        let target = RemotePath.join(path, name)
        Task {
            do {
                try await fs.createDirectory(target)
                reload()
                selection = [target]
            } catch {
                app?.show(error)
            }
        }
    }

    func rename(_ item: RemoteItem) {
        guard let name = ask("Rename", "New name for “\(item.name)”:", default: item.name, confirm: "Rename"),
              name != item.name
        else { return }
        if let reason = Names.invalidSMBNameReason(name) { return app?.show(error: "“\(name)” \(reason).") ?? () }
        let target = RemotePath.join(RemotePath.parent(item.path), name)
        Task {
            do {
                // a rename that only changes the case goes through a temporary name
                if Names.foldedKey(name) == Names.foldedKey(item.name) {
                    let temporary = item.path + ".sambalite-rename"
                    try await fs.move(item.path, to: temporary)
                    try await fs.move(temporary, to: target)
                } else {
                    if try await fs.item(at: target) != nil {
                        app?.show(error: "“\(name)” exists already.")
                        return
                    }
                    try await fs.move(item.path, to: target)
                }
                reload()
                selection = [target]
            } catch {
                app?.show(error)
            }
        }
    }

    func delete(_ items: [RemoteItem]) {
        guard !items.isEmpty else { return }
        let alert = NSAlert()
        alert.messageText = items.count == 1 ? "Delete “\(items[0].name)”?" : "Delete \(items.count) items?"
        alert.informativeText =
            items.contains(where: \.isDirectory)
            ? "Folders are deleted with everything in them. This cannot be undone." : "This cannot be undone."
        alert.addButton(withTitle: "Delete")
        alert.addButton(withTitle: "Cancel")
        alert.buttons[0].hasDestructiveAction = true
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        busy = items.count == 1 ? "Deleting \(items[0].name)…" : "Deleting \(items.count) items…"
        Task {
            defer { busy = nil }
            var failed: [String] = []
            for item in items {
                do { try await fs.remove(item.path) } catch { failed.append("\(item.name): \(Failure.from(error).message)") }
            }
            if !failed.isEmpty { app?.show(error: "Not deleted: " + failed.joined(separator: "; ")) }
            selection = []
            reload()
        }
    }

    func setUpSync(_ item: RemoteItem? = nil) {
        app?.setUpSync(connection: connection, remotePath: item?.path ?? path)
    }

    func copyPath(_ items: [RemoteItem]) {
        let host = connection.server
        let text = (items.isEmpty ? [path] : items.map(\.path)).map { p in
            "smb://\(host)\(connection.port == 445 ? "" : ":\(connection.port)")/\(connection.share)\(p.isEmpty ? "" : "/" + p)"
        }.joined(separator: "\n")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    func syncConfig(for item: RemoteItem) -> SyncConfig? {
        app?.syncs.configs.first { $0.connectionID == connection.id && $0.remotePath == item.path }
    }

    private func ask(_ title: String, _ text: String, default value: String, confirm: String) -> String? {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 280, height: 24))
        field.stringValue = value
        alert.accessoryView = field
        alert.addButton(withTitle: confirm)
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = field
        // select the name without its extension, as Finder does
        DispatchQueue.main.async {
            let base = (value as NSString).deletingPathExtension
            field.currentEditor()?.selectedRange = NSRange(location: 0, length: (base.isEmpty ? value : base).utf16.count)
        }
        guard alert.runModal() == .alertFirstButtonReturn else { return nil }
        let result = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        return result.isEmpty ? nil : result
    }
}
