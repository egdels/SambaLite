// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AppKit
import SwiftUI

// The window treatment of Crate (same author), where each piece was
// measured on window captures on macOS 12 and 26: the detail column and
// its toolbar in the content colour instead of the tinted materials.

/// The toolbar over the detail column in the content colour; macOS 13+.
struct ClearToolbar: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 13, *) {
            content
                .toolbarBackground(Color(nsColor: .textBackgroundColor), for: .windowToolbar)
                .toolbarBackground(.visible, for: .windowToolbar)
        } else {
            content
        }
    }
}

/// A transparent title bar over a window background in the content
/// colour, which also keeps the toolbar white in full screen.
struct TitlebarStyle: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { Probe() }
    func updateNSView(_ nsView: NSView, context: Context) {}

    final class Probe: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window else { return }
            window.titlebarAppearsTransparent = true
            window.backgroundColor = .textBackgroundColor
            // first launch: a window that fits four columns of files
            if !window.setFrameUsingName("SambaLiteMain") {
                window.setContentSize(NSSize(width: 1120, height: 700))
                window.center()
            }
            window.setFrameAutosaveName("SambaLiteMain")
        }
    }
}

/// Lists without their own (in dark mode blue-tinted) background.
struct ContentBackgroundList: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 13, *) {
            content.scrollContentBackground(.hidden)
        } else {
            content.background(ClearTableBackground())
        }
    }
}

private struct ClearTableBackground: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { NSView() }
    func updateNSView(_ nsView: NSView, context: Context) {
        DispatchQueue.main.async { [weak nsView] in
            MainActor.assumeIsolated { Self.clearTables(near: nsView) }
        }
    }

    @MainActor
    private static func clearTables(near nsView: NSView?) {
        do {
            guard let root = nsView?.superview?.superview else { return }
            func clear(_ view: NSView) {
                if let table = view as? NSTableView, !(table is FileTable) {
                    table.backgroundColor = .clear
                    table.enclosingScrollView?.drawsBackground = false
                }
                view.subviews.forEach(clear)
            }
            clear(root)
        }
    }
}

/// The colours of the health states, the same in every view.
extension Color {
    static let healthy = Color.green
    static let warning = Color.orange
    static let problem = Color.red
}
