// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AppKit
import Combine
import SambaLiteCore
import ServiceManagement
import SwiftUI

/// Keeps the app alive without its window (syncs run on schedule), puts
/// an icon into the menu bar that shows how the syncs are doing, and
/// saves the queue on quit.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    weak var model: AppModel? {
        didSet { observe() }
    }
    private var statusItem: NSStatusItem?
    private var cancellable: AnyCancellable?
    private var quitting = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName: "externaldrive.connected.to.line.below", accessibilityDescription: "SambaLite")
        let menu = NSMenu()
        menu.delegate = self
        item.menu = menu
        statusItem = item
        // the WindowGroup's window arrives asynchronously; a launch at
        // login without it would show nothing, which is right then
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            if !NSApp.windows.contains(where: { $0.canBecomeMain && $0.isVisible }) && !Self.launchedAtLogin {
                self.showWindow()
            }
        }
    }

    /// Started by "Open at Login": stay in the menu bar.
    static var launchedAtLogin: Bool {
        guard let event = NSAppleEventManager.shared().currentAppleEvent else { return false }
        return event.eventID == kAEOpenApplication
            && event.paramDescriptor(forKeyword: keyAEPropData)?.enumCodeValue == keyAELaunchedAsLogInItem
    }

    private func observe() {
        cancellable = model?.objectWillChange
            .throttle(for: .seconds(1), scheduler: RunLoop.main, latest: true)
            .sink { [weak self] _ in
                DispatchQueue.main.async { self?.updateIcon() }
            }
        updateIcon()
    }

    private func updateIcon() {
        guard let model, let button = statusItem?.button else { return }
        let symbol: String
        if !model.syncs.running.isEmpty || model.activeTransferCount > 0 {
            symbol = "arrow.triangle.2.circlepath.circle"
        } else {
            switch model.overallSeverity {
            case 2: symbol = "exclamationmark.triangle"
            case 1: symbol = "exclamationmark.circle"
            default: symbol = "externaldrive.connected.to.line.below"
            }
        }
        button.image = NSImage(systemSymbolName: symbol, accessibilityDescription: "SambaLite")
        button.image?.isTemplate = true
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        guard let model else { return }
        if model.syncs.configs.isEmpty {
            menu.addItem(withTitle: "No syncs set up", action: nil, keyEquivalent: "")
        }
        for config in model.syncs.configs {
            let health = model.health(of: config)
            let item = ClosureMenuItem(title: "\(config.name) — \(health.title)") { [weak self] in
                model.selection = .syncs
                model.syncDetail = config.id
                self?.showWindow()
            }
            item.image = NSImage(
                systemSymbolName: health.severity == 2 ? "xmark.octagon" : health.severity == 1 ? "exclamationmark.triangle" : "checkmark.circle",
                accessibilityDescription: nil)
            menu.addItem(item)
        }
        if model.activeTransferCount > 0 {
            menu.addItem(.separator())
            menu.addItem(ClosureMenuItem(title: "\(Format.count(model.activeTransferCount, "transfer")) running…") { [weak self] in
                model.selection = .transfers
                self?.showWindow()
            })
        }
        menu.addItem(.separator())
        let all = ClosureMenuItem(title: "Sync All Now") { model.runAllSyncs() }
        all.isEnabled = !model.syncs.configs.isEmpty
        menu.addItem(all)
        menu.addItem(ClosureMenuItem(title: "Open SambaLite") { [weak self] in self?.showWindow() })
        menu.addItem(.separator())
        menu.addItem(ClosureMenuItem(title: "Quit SambaLite") { NSApp.terminate(nil) })
    }

    func showWindow() {
        NSApp.activate(ignoringOtherApps: true)
        if let window = NSApp.windows.first(where: { $0.canBecomeMain && $0.title != "" }) {
            window.makeKeyAndOrderFront(nil)
        } else {
            NSWorkspace.shared.open(URL(string: "sambalite://main")!)
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        if !hasVisibleWindows { showWindow() }
        return false
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard let model, !quitting else { return .terminateNow }
        quitting = true
        Task {
            await model.shutdown()
            NSApp.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}

@main
struct SambaLiteApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    // Held, not observed (Crate's lesson): an App that observes the model
    // rebuilds its scenes and menus on every progress tick.
    @State private var model = AppModel.live()

    init() {
        // `swift run` starts a bare executable: no Dock icon, no menu bar
        // and no key window without this.
        NSApplication.shared.setActivationPolicy(.regular)
    }

    var body: some Scene {
        WindowGroup("SambaLite") {
            ContentView()
                .environmentObject(model)
                .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
                // the ideal size is the first window's size (SwiftUI keeps
                // the user's frame after that)
                .frame(minWidth: 820, idealWidth: 1120, minHeight: 500, idealHeight: 700)
                .onOpenURL { _ in }  // sambalite://main: showing the window is all it means
                .task {
                    delegate.model = model
                    await model.start()
                }
        }
        .commands { AppCommands(model: model) }
    }
}

struct AppCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("New Connection…") { model.newConnection() }
                .keyboardShortcut("n")
            Divider()
            Button("Import Connections…") { model.importConnections() }
            Button("Export Connections…") { model.exportConnections() }
        }
        CommandGroup(after: .appSettings) {
            LoginItemToggle()
        }
        CommandMenu("Go") {
            Button("Back") { model.currentBrowser?.back() }
                .keyboardShortcut("[")
            Button("Forward") { model.currentBrowser?.forward() }
                .keyboardShortcut("]")
            Button("Enclosing Folder") { model.currentBrowser?.up() }
                .keyboardShortcut(.upArrow)
            Button("Refresh") { model.currentBrowser?.reload() }
                .keyboardShortcut("r")
            Divider()
            Button("Transfers") { model.selection = .transfers }
                .keyboardShortcut("t", modifiers: [.command, .option])
            Button("Syncs") { model.selection = .syncs }
                .keyboardShortcut("s", modifiers: [.command, .option])
        }
        CommandMenu("File Actions") {
            Button("Upload…") { model.currentBrowser?.uploadPanel() }
                .keyboardShortcut("u")
            Button("Download") { if let b = model.currentBrowser { b.download(b.selectedItems) } }
                .keyboardShortcut("d")
            Button("Download To…") { if let b = model.currentBrowser { b.downloadAsking(b.selectedItems) } }
                .keyboardShortcut("d", modifiers: [.command, .shift])
            Button("New Folder…") { model.currentBrowser?.newFolder() }
                .keyboardShortcut("n", modifiers: [.command, .shift])
            Divider()
            Button("Sync This Folder…") { model.currentBrowser?.setUpSync() }
            Button("Sync All Now") { model.runAllSyncs() }
                .keyboardShortcut("s", modifiers: [.command, .shift])
        }
    }
}

/// "Open at Login" with its check mark; macOS 13 has the API for it.
struct LoginItemToggle: View {
    @State private var enabled = Self.isEnabled

    var body: some View {
        if #available(macOS 13, *) {
            Toggle("Open at Login", isOn: Binding(
                get: { enabled },
                set: { value in
                    do {
                        if value { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                    } catch {
                        NSSound.beep()
                    }
                    enabled = Self.isEnabled
                }))
        }
    }

    static var isEnabled: Bool {
        if #available(macOS 13, *) { return SMAppService.mainApp.status == .enabled }
        return false
    }
}

extension AppModel {
    /// The browser of the share the window shows, for the menus.
    var currentBrowser: BrowserModel? {
        guard case .connection(let id) = selection, let connection = connection(id) else { return nil }
        return browser(for: connection)
    }
}
