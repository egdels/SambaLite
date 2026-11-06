// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AppKit
import Network
import SambaLiteCore
import SwiftUI
import UniformTypeIdentifiers
import UserNotifications

/// What the sidebar selects.
enum SidebarItem: Hashable {
    case connection(UUID)
    case transfers
    case syncs
}

/// A connection being edited in the sheet, with its password.
struct ConnectionDraft: Identifiable {
    var id: UUID { connection.id }
    var connection: Connection
    var password: String
    var isNew: Bool
}

/// A sync being set up or edited in the sheet.
struct SyncDraft: Identifiable {
    var id: UUID { config.id }
    var config: SyncConfig
    var isNew: Bool
}

/// Uploads waiting for the user's answer: some names exist already.
struct UploadConflict: Identifiable {
    let id = UUID()
    var connectionID: UUID
    var plan: [(url: URL, remote: String)]
    /// Remote paths that exist.
    var existing: Set<String>
    /// Every name in the target folders (lower-cased key), for "Keep Both".
    var taken: [String: Set<String>]
}

/// The app's one piece of UI state. Views never talk to the queue, the
/// coordinator or the keychain directly, only to this.
@MainActor
final class AppModel: ObservableObject {
    @Published var connections: [Connection] = []
    @Published var selection: SidebarItem?
    @Published var transfers = TransferQueue.Snapshot(transfers: [], speeds: [:])
    @Published var syncs = SyncCoordinator.Snapshot(configs: [], history: [:], running: [:], queued: [])
    @Published var lastError: String?
    @Published var notice: String?
    @Published var editingConnection: ConnectionDraft?
    @Published var editingSync: SyncDraft?
    @Published var uploadConflict: UploadConflict?
    @Published var networkAvailable = true
    @Published var syncDetail: UUID?

    let credentials: any CredentialStore
    let queue: TransferQueue
    let coordinator: SyncCoordinator
    private let folder: URL
    private let shared: Locked<[Connection]>
    private let passwords: Locked<[UUID: String]>
    private var browsers: [UUID: BrowserModel] = [:]
    private var monitor: NWPathMonitor?
    private var started = false
    private var openTransfersBefore = 0
    private var finishedSince: [Transfer] = []

    init(folder: URL, credentials: any CredentialStore) {
        self.folder = folder
        self.credentials = credentials
        let shared = Locked<[Connection]>([])
        let passwords = Locked<[UUID: String]>([:])
        self.shared = shared
        self.passwords = passwords
        let resolve: TransferQueue.Resolver = { id in
            guard let connection = shared.get().first(where: { $0.id == id }) else { return nil }
            if connection.guest { return (connection, "") }
            if let cached = passwords.get()[id] { return (connection, cached) }
            let password = credentials.password(for: id) ?? ""
            passwords.update { $0[id] = password }
            return (connection, password)
        }
        let make: TransferQueue.FileSystemFactory = { connection, password in
            SMBFileSystem(connection: connection, password: password)
        }
        queue = TransferQueue(
            file: JSONFile(folder.appendingPathComponent("transfers.json")), resolve: resolve, makeFileSystem: make)
        coordinator = SyncCoordinator(folder: folder, resolve: resolve, makeFileSystem: make)
    }

    /// The real app: Application Support and the keychain. For automated
    /// UI checks, SAMBALITE_DATA moves the data folder and
    /// SAMBALITE_PASSWORDS=memory|file keeps passwords out of the keychain
    /// (a rebuilt ad-hoc signed binary would get a keychain prompt each time).
    static func live() -> AppModel {
        let env = ProcessInfo.processInfo.environment
        let folder = env["SAMBALITE_DATA"].map { URL(fileURLWithPath: $0, isDirectory: true) } ?? AppFolder.url
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let credentials: any CredentialStore
        switch env["SAMBALITE_PASSWORDS"] {
        case "memory": credentials = MemoryCredentialStore()
        case "file": credentials = FileCredentialStore(url: folder.appendingPathComponent("test-passwords.json"))
        default: credentials = KeychainCredentialStore()
        }
        return AppModel(folder: folder, credentials: credentials)
    }

    private var connectionsFile: JSONFile<[Connection]> { JSONFile(folder.appendingPathComponent("connections.json")) }

    func start() async {
        guard !started else { return }
        started = true
        connections = connectionsFile.loadOrSetAside() ?? []
        shared.set(connections)
        if selection == nil {
            selection = connections.first.map { .connection($0.id) } ?? nil
        }
        await queue.setObserver { snapshot in
            Task { @MainActor [weak self] in self?.transfersChanged(snapshot) }
        }
        await coordinator.setObserver(
            { snapshot in Task { @MainActor [weak self] in self?.syncs = snapshot } },
            runEnded: { config, before, after, _ in
                Task { @MainActor [weak self] in self?.notifyIfNeeded(config, before, after) }
            })
        await queue.start()
        await coordinator.start()
        startNetworkMonitor()
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                await self.coordinator.systemDidWake()
                await self.queue.setNetworkAvailable(self.networkAvailable)
            }
        }
    }

    /// The app quits: everything saved where it stands.
    func shutdown() async {
        await queue.flush()
        await coordinator.stopAll()
    }

    private func startNetworkMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in
                guard let self, self.networkAvailable != up || !up else { return }
                self.networkAvailable = up
                await self.queue.setNetworkAvailable(up)
                await self.coordinator.setNetworkAvailable(up)
            }
        }
        monitor.start(queue: DispatchQueue(label: "network"))
        self.monitor = monitor
    }

    // MARK: Messages

    func show(_ error: any Error) {
        lastError = Failure.from(error).message
    }

    func show(error text: String) {
        lastError = text
    }

    func show(notice text: String) {
        notice = text
    }

    // MARK: Connections

    func connection(_ id: UUID) -> Connection? {
        connections.first { $0.id == id }
    }

    func password(for connection: Connection) -> String {
        if connection.guest { return "" }
        if let cached = passwords.get()[connection.id] { return cached }
        let password = credentials.password(for: connection.id) ?? ""
        passwords.update { $0[connection.id] = password }
        return password
    }

    func newConnection() {
        editingConnection = ConnectionDraft(connection: Connection(), password: "", isNew: true)
    }

    func edit(_ connection: Connection) {
        editingConnection = ConnectionDraft(connection: connection, password: password(for: connection), isNew: false)
    }

    func save(_ draft: ConnectionDraft) {
        var connection = draft.connection
        connection.normalizeServerField()
        connection.initialPath = RemotePath.normalized(connection.initialPath)
        if connection.name.trimmingCharacters(in: .whitespaces).isEmpty { connection.name = connection.displayName }
        connection.credentialsRequired = false
        do {
            if connection.guest {
                credentials.removePassword(for: connection.id)
            } else {
                try credentials.setPassword(draft.password, for: connection.id)
            }
        } catch {
            show(error)
            return
        }
        passwords.update { $0[connection.id] = connection.guest ? "" : draft.password }
        if let index = connections.firstIndex(where: { $0.id == connection.id }) {
            connections[index] = connection
        } else {
            connections.append(connection)
        }
        saveConnections()
        browsers[connection.id] = nil  // new settings, new session
        selection = .connection(connection.id)
        editingConnection = nil
    }

    func delete(_ connection: Connection) {
        let alert = NSAlert()
        alert.messageText = "Delete “\(connection.displayName)”?"
        let syncCount = syncs.configs.filter { $0.connectionID == connection.id }.count
        alert.informativeText =
            syncCount > 0
            ? "Its \(Format.count(syncCount, "sync")) will be removed too. Files on the share and on this Mac stay."
            : "Files on the share stay."
        alert.addButton(withTitle: "Delete")
        alert.addButton(withTitle: "Cancel")
        alert.buttons[0].hasDestructiveAction = true
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        connections.removeAll { $0.id == connection.id }
        saveConnections()
        credentials.removePassword(for: connection.id)
        passwords.update { $0[connection.id] = nil }
        browsers[connection.id] = nil
        Task { await coordinator.removeSyncs(of: connection.id) }
        if selection == .connection(connection.id) {
            selection = connections.first.map { .connection($0.id) } ?? .syncs
        }
    }

    private func saveConnections() {
        shared.set(connections)
        do { try connectionsFile.save(connections) } catch { show(error) }
    }

    func browser(for connection: Connection) -> BrowserModel {
        if let existing = browsers[connection.id], existing.connection == connection { return existing }
        let browser = BrowserModel(connection: connection, password: password(for: connection), app: self)
        browsers[connection.id] = browser
        return browser
    }

    // MARK: Connection files

    func importConnections() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.message = "Choose a SambaLite connection file"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let entries = try ConnectionFile.read(Data(contentsOf: url))
            var added = 0, duplicates = 0, skipped: [String] = [], withPasswords = 0
            for entry in entries {
                if let reason = entry.skipReason {
                    skipped.append("\(entry.connection.server.isEmpty ? "?" : entry.connection.server): \(reason)")
                    continue
                }
                if connections.contains(where: { $0.isSameTarget(as: entry.connection) }) {
                    duplicates += 1
                    continue
                }
                if let password = entry.password {
                    try credentials.setPassword(password, for: entry.connection.id)
                    withPasswords += 1
                }
                connections.append(entry.connection)
                added += 1
            }
            saveConnections()
            var text = "Imported \(Format.count(added, "connection"))."
            if duplicates > 0 { text += " \(Format.count(duplicates, "connection")) already existed." }
            if !skipped.isEmpty { text += " Skipped: " + skipped.joined(separator: "; ") }
            if withPasswords > 0 { text += " The file contains passwords: delete it now." }
            show(notice: text)
        } catch {
            show(error)
        }
    }

    func exportConnections() {
        guard !connections.isEmpty else { return }
        let alert = NSAlert()
        alert.messageText = "Export \(Format.count(connections.count, "connection"))"
        alert.informativeText = "Passwords are never exported. Include the usernames?"
        alert.addButton(withTitle: "Without Usernames")
        alert.addButton(withTitle: "With Usernames")
        alert.addButton(withTitle: "Cancel")
        let answer = alert.runModal()
        guard answer != .alertThirdButtonReturn else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "sambalite-connections.json"
        panel.allowedContentTypes = [.json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try ConnectionFile.write(connections, includeUsernames: answer == .alertSecondButtonReturn)
                .write(to: url, options: .atomic)
            show(notice: "Exported to \(url.lastPathComponent).")
        } catch {
            show(error)
        }
    }

    // MARK: Transfers

    private func transfersChanged(_ snapshot: TransferQueue.Snapshot) {
        let previous = Dictionary(transfers.transfers.map { ($0.id, $0.status) }, uniquingKeysWith: { a, _ in a })
        transfers = snapshot
        for transfer in snapshot.transfers
        where (transfer.status == .completed || transfer.status == .failed) && previous[transfer.id] != transfer.status
            && previous[transfer.id] != nil
        {
            finishedSince.append(transfer)
            if transfer.status == .completed { browserDidChange(transfer) }
        }
        let open = snapshot.transfers.filter { $0.status.isOpen }.count
        if open == 0, openTransfersBefore > 0, !finishedSince.isEmpty {
            let done = finishedSince.filter { $0.status == .completed }
            let failed = finishedSince.count - done.count
            if failed > 0 {
                show(error: "\(Format.count(failed, "transfer")) failed. See Transfers.")
            } else if done.count == 1, let one = done.first {
                show(notice: "\(one.name) \(one.kind == .upload ? "uploaded" : "downloaded").")
            } else {
                show(notice: "\(done.count) transfers completed.")
            }
            finishedSince = []
        }
        openTransfersBefore = open
    }

    /// A finished upload shows up in the folder the browser shows.
    private func browserDidChange(_ transfer: Transfer) {
        guard transfer.kind == .upload, let browser = browsers[transfer.connectionID] else { return }
        // the file itself, or the folder it went into, is in view
        if browser.path.isEmpty || transfer.remotePath.hasPrefix(browser.path + "/") { browser.refreshSoon() }
    }

    var activeTransferCount: Int { transfers.transfers.filter { $0.status.isOpen }.count }

    func enqueue(_ new: [Transfer]) async {
        guard !new.isEmpty else { return }
        let duplicates = await queue.enqueue(new)
        if !duplicates.isEmpty {
            show(notice: "\(Format.count(duplicates.count, "file")) already in the queue: \(duplicates.prefix(3).map(\.name).joined(separator: ", "))")
        }
    }

    func cancelTransfers(_ ids: Set<UUID>) { Task { await queue.cancel(ids) } }
    func retryTransfers(_ ids: Set<UUID>) { Task { await queue.retry(ids) } }
    func removeTransfers(_ ids: Set<UUID>) { Task { await queue.remove(ids) } }
    func clearFinishedTransfers() { Task { await queue.clearFinished() } }

    // MARK: Downloads and uploads

    /// Files and folders from the share into `folder`; a name that exists
    /// there gets a number, as Finder does.
    func download(_ items: [RemoteItem], from connection: Connection, to folder: URL) {
        let fs = SMBFileSystem(connection: connection, password: password(for: connection))
        Task {
            defer { Task { await fs.disconnect() } }
            var transfers: [Transfer] = []
            let batch = UUID()
            do {
                var reserved: Set<String> = []
                for item in items {
                    var name = LocalFiles.freeName(item.name, in: folder)
                    var number = 2
                    while reserved.contains(name.lowercased()) {
                        name = LocalFiles.freeName(Names.numbered(item.name, number), in: folder)
                        number += 1
                    }
                    reserved.insert(name.lowercased())
                    let target = folder.appendingPathComponent(name)
                    if item.isDirectory {
                        try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
                        let (all, skipped) = try await fs.walk(item.path, skip: { $0 == ".DS_Store" || $0.hasSuffix(Names.partSuffix) })
                        for entry in all {
                            let relative = String(entry.path.dropFirst(item.path.count + 1))
                            let local = target.appendingPathComponent(relative)
                            if entry.isDirectory {
                                try FileManager.default.createDirectory(at: local, withIntermediateDirectories: true)
                            } else {
                                transfers.append(Transfer(
                                    kind: .download, connectionID: connection.id, localPath: local.path,
                                    remotePath: entry.path, size: entry.size, batchID: batch))
                            }
                        }
                        if !skipped.isEmpty {
                            show(error: "\(Format.count(skipped.count, "folder")) in \(item.name) could not be read and were left out.")
                        }
                    } else {
                        transfers.append(Transfer(
                            kind: .download, connectionID: connection.id, localPath: target.path,
                            remotePath: item.path, size: item.size, batchID: batch))
                    }
                }
            } catch {
                show(error)
            }
            await enqueue(transfers)
            if !transfers.isEmpty {
                show(notice: "Downloading \(Format.count(transfers.count, "file")) to \(folder.lastPathComponent).")
            }
        }
    }

    func chooseDownloadFolder() -> URL? {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.prompt = "Download Here"
        panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        return panel.runModal() == .OK ? panel.url : nil
    }

    var downloadsFolder: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser
    }

    /// Files and folders from this Mac into `remoteFolder`. Names that
    /// exist there are asked about first (Replace, Keep Both, Skip).
    func upload(_ urls: [URL], to remoteFolder: String, connection: Connection) {
        let fs = SMBFileSystem(connection: connection, password: password(for: connection))
        Task {
            defer { Task { await fs.disconnect() } }
            var plan: [(url: URL, remote: String)] = []
            var emptyFolders: [String] = []
            var symlinks = 0
            for url in urls {
                let name = Names.key(url.lastPathComponent)
                let target = RemotePath.join(remoteFolder, name)
                if LocalFiles.isFolder(url) {
                    do {
                        let (items, _, links) = try LocalFiles.walk(url)
                        symlinks += links.count
                        let folders = Set(items.filter(\.isDirectory).map(\.path))
                        let parents = Set(items.filter { !$0.isDirectory }.map { RemotePath.parent($0.path) })
                        for item in items where !item.isDirectory {
                            plan.append((url.appendingPathComponent(item.path), RemotePath.join(target, Names.key(item.path))))
                        }
                        emptyFolders.append(target)
                        for folder in folders where !parents.contains(folder) && !parents.contains(where: { $0.hasPrefix(folder + "/") }) {
                            emptyFolders.append(RemotePath.join(target, Names.key(folder)))
                        }
                    } catch {
                        show(error)
                    }
                } else if LocalFiles.stat(url) != nil {
                    plan.append((url, target))
                }
            }
            if symlinks > 0 { show(notice: "\(Format.count(symlinks, "symbolic link")) left out.") }
            // what exists already? one listing per target folder
            var taken: [String: Set<String>] = [:]
            var existing: Set<String> = []
            for parent in Set(plan.map { RemotePath.parent($0.remote) }) {
                guard let items = try? await fs.list(parent) else { continue }
                taken[parent] = Set(items.map { Names.foldedKey($0.name) })
                for entry in plan where RemotePath.parent(entry.remote) == parent {
                    if taken[parent]!.contains(Names.foldedKey(RemotePath.name(entry.remote))) { existing.insert(entry.remote) }
                }
            }
            for folder in emptyFolders { try? await fs.createDirectories(folder) }
            if !emptyFolders.isEmpty { browsers[connection.id]?.refreshSoon() }
            if existing.isEmpty {
                await startUploads(plan, connectionID: connection.id)
            } else {
                uploadConflict = UploadConflict(connectionID: connection.id, plan: plan, existing: existing, taken: taken)
            }
        }
    }

    enum ConflictChoice { case replace, keepBoth, skip, cancel }

    func resolve(_ conflict: UploadConflict, _ choice: ConflictChoice) {
        uploadConflict = nil
        var plan = conflict.plan
        switch choice {
        case .cancel:
            return
        case .replace:
            break
        case .skip:
            plan.removeAll { conflict.existing.contains($0.remote) }
        case .keepBoth:
            var taken = conflict.taken
            plan = plan.map { entry in
                guard conflict.existing.contains(entry.remote) else { return entry }
                let parent = RemotePath.parent(entry.remote)
                let name = RemotePath.name(entry.remote)
                var number = 2
                var candidate = Names.numbered(name, number)
                while taken[parent, default: []].contains(Names.foldedKey(candidate)) {
                    number += 1
                    candidate = Names.numbered(name, number)
                }
                taken[parent, default: []].insert(Names.foldedKey(candidate))
                return (entry.url, RemotePath.join(parent, candidate))
            }
        }
        Task { await startUploads(plan, connectionID: conflict.connectionID) }
    }

    private func startUploads(_ plan: [(url: URL, remote: String)], connectionID: UUID) async {
        let batch = UUID()
        let transfers = plan.map { entry in
            Transfer(
                kind: .upload, connectionID: connectionID, localPath: entry.url.path, remotePath: entry.remote,
                size: LocalFiles.stat(entry.url)?.size ?? 0, batchID: batch)
        }
        await enqueue(transfers)
        if !transfers.isEmpty { show(notice: "Uploading \(Format.count(transfers.count, "file")).") }
    }

    // MARK: Syncs

    func setUpSync(connection: Connection, remotePath: String) {
        if let existing = syncs.configs.first(where: { $0.connectionID == connection.id && $0.remotePath == remotePath }) {
            editingSync = SyncDraft(config: existing, isNew: false)
            return
        }
        editingSync = SyncDraft(
            config: SyncConfig(connectionID: connection.id, localPath: "", remotePath: remotePath), isNew: true)
    }

    func save(_ draft: SyncDraft) {
        var config = draft.config
        if config.direction == .bidirectional { config.mirror = false }
        Task {
            if draft.isNew {
                await coordinator.add(config)
            } else {
                await coordinator.update(config)
            }
        }
        editingSync = nil
        selection = .syncs
        requestNotificationPermission()
    }

    func removeSync(_ config: SyncConfig) {
        let alert = NSAlert()
        alert.messageText = "Remove the sync of “\(config.name)”?"
        alert.informativeText = "The files on both sides stay where they are."
        alert.addButton(withTitle: "Remove")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        Task { await coordinator.remove(config.id) }
    }

    func runSync(_ id: UUID) { Task { await coordinator.runNow(id) } }
    func stopSync(_ id: UUID) { Task { await coordinator.stop(id) } }

    func runAllSyncs() {
        for config in syncs.configs where config.enabled { runSync(config.id) }
    }

    func setPaused(_ config: SyncConfig, _ paused: Bool) {
        var changed = config
        changed.enabled = !paused
        Task {
            if paused { await coordinator.stop(config.id) }
            await coordinator.update(changed)
        }
    }

    func health(of config: SyncConfig) -> SyncHealth {
        SyncHealth.of(
            config, runs: syncs.history[config.id] ?? [], running: syncs.running[config.id] != nil,
            localExists: LocalFiles.isFolder(config.localURL))
    }

    func syncs(of connection: Connection) -> [SyncConfig] {
        syncs.configs.filter { $0.connectionID == connection.id }
    }

    /// The worst state over all syncs, for the sidebar and the menu bar.
    var overallSeverity: Int {
        syncs.configs.map { health(of: $0).severity }.max() ?? 0
    }

    // MARK: Notifications

    private var canNotify: Bool { Bundle.main.bundleIdentifier != nil }

    private func requestNotificationPermission() {
        guard canNotify else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func notifyIfNeeded(_ config: SyncConfig, _ before: SyncHealth, _ after: SyncHealth) {
        guard canNotify, let message = SyncNotice.message(for: config, before: before, after: after) else { return }
        let content = UNMutableNotificationContent()
        content.title = message.title
        content.body = message.body
        let request = UNNotificationRequest(identifier: config.id.uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
