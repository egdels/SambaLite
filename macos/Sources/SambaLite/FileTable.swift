// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AppKit
import SambaLiteCore
import SwiftUI
import UniformTypeIdentifiers

/// The table under the file list: an NSTableView, because SwiftUI's List
/// and Table on macOS 12 have no double-click with multiple selection,
/// no sortable columns with it, and no file promises for dragging files
/// out to Finder.
final class FileTable: NSTableView {
    var onOpen: (() -> Void)?
    var onDelete: (() -> Void)?
    var menuProvider: ((Int) -> NSMenu?)?

    override func keyDown(with event: NSEvent) {
        switch event.keyCode {
        case 36, 76:  // Return, Enter
            onOpen?()
        case 51 where event.modifierFlags.contains(.command):  // Cmd-Backspace
            onDelete?()
        case 125 where event.modifierFlags.contains(.command):  // Cmd-Down, as in Finder
            onOpen?()
        default:
            super.keyDown(with: event)
        }
    }

    override func menu(for event: NSEvent) -> NSMenu? {
        let row = self.row(at: convert(event.locationInWindow, from: nil))
        if row >= 0, !selectedRowIndexes.contains(row) {
            selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
        }
        return menuProvider?(row)
    }
}

struct FileTableView: NSViewRepresentable {
    @ObservedObject var browser: BrowserModel
    let items: [RemoteItem]
    /// Paths that are a sync's remote folder, marked in the list.
    let synced: Set<String>

    func makeCoordinator() -> Coordinator { Coordinator(browser: browser) }

    func makeNSView(context: Context) -> NSScrollView {
        let table = FileTable()
        table.style = .inset
        table.usesAlternatingRowBackgroundColors = false
        table.allowsMultipleSelection = true
        table.allowsColumnReordering = false
        table.rowHeight = 24
        table.intercellSpacing = NSSize(width: 6, height: 2)
        table.backgroundColor = .textBackgroundColor
        for (id, title, width, key) in [
            ("name", "Name", 320.0, SortKey.name), ("modified", "Date Modified", 160.0, .modified),
            ("size", "Size", 80.0, .size), ("kind", "Kind", 130.0, .kind),
        ] {
            let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(id))
            column.title = title
            column.width = width
            column.minWidth = id == "name" ? 160 : 60
            column.sortDescriptorPrototype = NSSortDescriptor(key: key.rawValue, ascending: true)
            if id == "size" { column.headerCell.alignment = .right }
            table.addTableColumn(column)
        }
        table.columnAutoresizingStyle = .firstColumnOnlyAutoresizingStyle
        table.delegate = context.coordinator
        table.dataSource = context.coordinator
        table.target = context.coordinator
        table.doubleAction = #selector(Coordinator.doubleClicked(_:))
        table.registerForDraggedTypes([.fileURL])
        table.setDraggingSourceOperationMask(.copy, forLocal: false)
        table.draggingDestinationFeedbackStyle = .regular
        table.onOpen = { [weak coordinator = context.coordinator] in coordinator?.openSelection() }
        table.onDelete = { [weak coordinator = context.coordinator] in coordinator?.deleteSelection() }
        table.menuProvider = { [weak coordinator = context.coordinator] row in coordinator?.menu(row: row) }
        table.autosaveName = "SambaLiteFileTable"
        table.autosaveTableColumns = true

        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        context.coordinator.table = table
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        let coordinator = context.coordinator
        coordinator.browser = browser
        coordinator.synced = synced
        guard let table = coordinator.table else { return }
        let descriptor = NSSortDescriptor(key: browser.sortKey.rawValue, ascending: browser.ascending)
        if table.sortDescriptors.first != descriptor { table.sortDescriptors = [descriptor] }
        if coordinator.items != items {
            coordinator.items = items
            table.reloadData()
        }
        let wanted = IndexSet(items.indices.filter { browser.selection.contains(items[$0].path) })
        if table.selectedRowIndexes != wanted {
            coordinator.updatingSelection = true
            table.selectRowIndexes(wanted, byExtendingSelection: false)
            if let first = wanted.first { table.scrollRowToVisible(first) }
            coordinator.updatingSelection = false
        }
    }

    @MainActor
    final class Coordinator: NSObject, NSTableViewDelegate, NSTableViewDataSource, NSFilePromiseProviderDelegate {
        var browser: BrowserModel
        var items: [RemoteItem] = []
        var synced: Set<String> = []
        weak var table: FileTable?
        var updatingSelection = false

        init(browser: BrowserModel) {
            self.browser = browser
        }

        func numberOfRows(in tableView: NSTableView) -> Int { items.count }

        func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
            guard let column = tableColumn, row < items.count else { return nil }
            let item = items[row]
            let id = column.identifier
            let cell = (tableView.makeView(withIdentifier: id, owner: nil) as? NSTableCellView) ?? makeCell(id)
            switch id.rawValue {
            case "name":
                cell.textField?.stringValue = item.name
                cell.imageView?.image = Self.icon(for: item)
                let badge = cell.viewWithTag(7) as? NSImageView
                badge?.isHidden = !synced.contains(item.path)
            case "modified":
                cell.textField?.stringValue = Format.date(item.modified)
            case "size":
                cell.textField?.stringValue = item.isDirectory ? "--" : Format.bytes(item.size)
            default:
                cell.textField?.stringValue = BrowserModel.kind(of: item)
            }
            return cell
        }

        private func makeCell(_ id: NSUserInterfaceItemIdentifier) -> NSTableCellView {
            let cell = NSTableCellView()
            cell.identifier = id
            let text = NSTextField(labelWithString: "")
            text.lineBreakMode = .byTruncatingMiddle
            text.translatesAutoresizingMaskIntoConstraints = false
            cell.addSubview(text)
            cell.textField = text
            if id.rawValue == "name" {
                let image = NSImageView()
                image.translatesAutoresizingMaskIntoConstraints = false
                cell.addSubview(image)
                cell.imageView = image
                let badge = NSImageView()
                badge.tag = 7
                badge.image = NSImage(systemSymbolName: "arrow.triangle.2.circlepath", accessibilityDescription: "Synced folder")
                badge.contentTintColor = .controlAccentColor
                badge.translatesAutoresizingMaskIntoConstraints = false
                cell.addSubview(badge)
                NSLayoutConstraint.activate([
                    image.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 2),
                    image.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
                    image.widthAnchor.constraint(equalToConstant: 18),
                    image.heightAnchor.constraint(equalToConstant: 18),
                    text.leadingAnchor.constraint(equalTo: image.trailingAnchor, constant: 6),
                    text.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
                    badge.leadingAnchor.constraint(equalTo: text.trailingAnchor, constant: 4),
                    badge.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
                    badge.widthAnchor.constraint(equalToConstant: 13),
                    badge.trailingAnchor.constraint(lessThanOrEqualTo: cell.trailingAnchor, constant: -2),
                ])
                text.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            } else {
                text.textColor = .secondaryLabelColor
                if id.rawValue == "size" { text.alignment = .right }
                NSLayoutConstraint.activate([
                    text.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 2),
                    text.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -2),
                    text.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
                ])
            }
            return cell
        }

        private static var iconCache: [String: NSImage] = [:]

        static func icon(for item: RemoteItem) -> NSImage {
            let key = item.isDirectory ? "/folder" : (item.name as NSString).pathExtension.lowercased()
            if let cached = iconCache[key] { return cached }
            let type: UTType = item.isDirectory ? .folder : (UTType(filenameExtension: key) ?? .data)
            let image = NSWorkspace.shared.icon(for: type)
            iconCache[key] = image
            return image
        }

        func tableViewSelectionDidChange(_ notification: Notification) {
            guard !updatingSelection, let table else { return }
            let paths = Set(table.selectedRowIndexes.compactMap { $0 < items.count ? items[$0].path : nil })
            if browser.selection != paths { browser.selection = paths }
        }

        func tableView(_ tableView: NSTableView, sortDescriptorsDidChange oldDescriptors: [NSSortDescriptor]) {
            guard let first = tableView.sortDescriptors.first, let key = first.key.flatMap(SortKey.init(rawValue:)) else { return }
            if browser.sortKey != key { browser.sortKey = key }
            if browser.ascending != first.ascending { browser.ascending = first.ascending }
        }

        @objc func doubleClicked(_ sender: Any?) {
            guard let table, table.clickedRow >= 0, table.clickedRow < items.count else { return }
            browser.open(items[table.clickedRow])
        }

        func openSelection() {
            let selected = browser.selectedItems
            if selected.count == 1 {
                browser.open(selected[0])
            } else {
                for item in selected where !item.isDirectory { browser.openFile(item) }
            }
        }

        func deleteSelection() {
            browser.delete(browser.selectedItems)
        }

        // MARK: Context menu

        func menu(row: Int) -> NSMenu? {
            let menu = NSMenu()
            let selected = row >= 0 ? browser.selectedItems : []
            func add(_ title: String, _ action: @escaping () -> Void) {
                menu.addItem(ClosureMenuItem(title: title, action: action))
            }
            if selected.isEmpty {
                add("Upload Files…") { [browser] in browser.uploadPanel() }
                add("New Folder…") { [browser] in browser.newFolder() }
                menu.addItem(.separator())
                add("Sync This Folder…") { [browser] in browser.setUpSync() }
                add("Copy Path") { [browser] in browser.copyPath([]) }
                add("Refresh") { [browser] in browser.reload() }
                return menu
            }
            if selected.count == 1 {
                add(selected[0].isDirectory ? "Open" : "Open with Default App") { [browser] in browser.open(selected[0]) }
            }
            add("Download") { [browser] in browser.download(selected) }
            add("Download To…") { [browser] in browser.downloadAsking(selected) }
            menu.addItem(.separator())
            if selected.count == 1 {
                add("Rename…") { [browser] in browser.rename(selected[0]) }
            }
            if selected.count == 1, selected[0].isDirectory {
                let configured = browser.syncConfig(for: selected[0]) != nil
                add(configured ? "Edit Sync…" : "Sync This Folder…") { [browser] in browser.setUpSync(selected[0]) }
                add("Upload Into…") { [browser] in
                    let panel = NSOpenPanel()
                    panel.canChooseDirectories = true
                    panel.allowsMultipleSelection = true
                    panel.prompt = "Upload"
                    if panel.runModal() == .OK { browser.upload(panel.urls, into: selected[0].path) }
                }
            }
            add("Copy Path") { [browser] in browser.copyPath(selected) }
            menu.addItem(.separator())
            add("Delete…") { [browser] in browser.delete(selected) }
            return menu
        }

        // MARK: Drop: upload

        func tableView(
            _ tableView: NSTableView, validateDrop info: any NSDraggingInfo, proposedRow row: Int,
            proposedDropOperation dropOperation: NSTableView.DropOperation
        ) -> NSDragOperation {
            if info.draggingSource as? NSTableView === tableView { return [] }  // our own rows
            if dropOperation == .on, row < items.count, items[row].isDirectory { return .copy }
            tableView.setDropRow(-1, dropOperation: .on)  // the whole list = this folder
            return .copy
        }

        func tableView(
            _ tableView: NSTableView, acceptDrop info: any NSDraggingInfo, row: Int,
            dropOperation: NSTableView.DropOperation
        ) -> Bool {
            let urls = info.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
            guard !urls.isEmpty else { return false }
            let folder = row >= 0 && row < items.count && items[row].isDirectory ? items[row].path : nil
            browser.upload(urls, into: folder)
            return true
        }

        // MARK: Drag out: download through file promises

        func tableView(_ tableView: NSTableView, pasteboardWriterForRow row: Int) -> (any NSPasteboardWriting)? {
            guard row < items.count else { return nil }
            let item = items[row]
            let type = item.isDirectory ? UTType.folder : (UTType(filenameExtension: (item.name as NSString).pathExtension) ?? .data)
            let provider = RemotePromise(fileType: type.identifier, delegate: self)
            provider.job = RemotePromise.Job(
                item: item, connection: browser.connection, password: browser.passwordForPromises)
            return provider
        }

        nonisolated func filePromiseProvider(_ provider: NSFilePromiseProvider, fileNameForType fileType: String) -> String {
            (provider as? RemotePromise)?.job?.item.name ?? "file"
        }

        nonisolated func operationQueue(for filePromiseProvider: NSFilePromiseProvider) -> OperationQueue {
            RemotePromise.queue
        }

        /// Finder waits for this; it runs on `RemotePromise.queue`, off the
        /// main thread, and copies straight to where the files were dropped.
        nonisolated func filePromiseProvider(
            _ provider: NSFilePromiseProvider, writePromiseTo url: URL, completionHandler: @escaping (Error?) -> Void
        ) {
            guard let job = (provider as? RemotePromise)?.job else { return completionHandler(nil) }
            completionHandler(RemotePromise.run(job, to: url))
        }
    }
}

/// A file promise for one entry of the share, with what is needed to
/// fetch it from any thread.
final class RemotePromise: NSFilePromiseProvider {
    struct Job: Sendable {
        let item: RemoteItem
        let connection: Connection
        let password: String
    }

    nonisolated(unsafe) var job: Job?

    nonisolated static let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.qualityOfService = .userInitiated
        queue.maxConcurrentOperationCount = 2
        return queue
    }()

    /// Copies the entry to `url`, blocking until done.
    nonisolated static func run(_ job: Job, to url: URL) -> (any Error)? {
        let semaphore = DispatchSemaphore(value: 0)
        let outcome = Locked<(any Error)?>(nil)
        Task.detached {
            let fs = SMBFileSystem(connection: job.connection, password: job.password)
            let copier = FileCopier(remote: fs)
            let item = job.item
            do {
                if item.isDirectory {
                    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
                    for entry in try await fs.walk(item.path).items {
                        let target = url.appendingPathComponent(String(entry.path.dropFirst(item.path.count + 1)))
                        if entry.isDirectory {
                            try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
                        } else {
                            try await copier.download(entry.path, to: target)
                        }
                    }
                } else {
                    try await copier.download(item.path, to: url)
                }
            } catch {
                outcome.set(error)
            }
            await fs.disconnect()
            semaphore.signal()
        }
        semaphore.wait()
        return outcome.get()
    }
}

/// An NSMenuItem that runs a closure.
final class ClosureMenuItem: NSMenuItem {
    private let handler: () -> Void

    init(title: String, action handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(run), keyEquivalent: "")
        target = self
    }

    required init(coder: NSCoder) { fatalError() }

    @objc private func run() { handler() }
}
