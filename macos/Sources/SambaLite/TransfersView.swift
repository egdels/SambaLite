// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AppKit
import SambaLiteCore
import SwiftUI

struct TransfersView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selection: Set<UUID> = []
    @AppStorage("hideCompletedTransfers") private var hideCompleted = false

    var body: some View {
        VStack(spacing: 0) {
            TransferSummary()
            Divider()
            if shown.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "arrow.up.arrow.down")
                        .font(.system(size: 44))
                        .foregroundStyle(.secondary)
                    Text("No transfers").font(.title3)
                    Text("Downloads and uploads from the shares appear here. They continue after a restart and resume where they stopped.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 380)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(selection: $selection) {
                    ForEach(shown) { transfer in
                        TransferRow(transfer: transfer, speed: model.transfers.speeds[transfer.id])
                            .tag(transfer.id)
                            .contextMenu { menu(for: transfer) }
                    }
                }
                .listStyle(.inset)
                .modifier(ContentBackgroundList())
            }
        }
        .navigationTitle("Transfers")
        .toolbar { toolbarItems }
    }

    private var canRetry: Bool { targetTransfers.contains { $0.status == .failed || $0.status == .cancelled } }
    private var canCancel: Bool { targetTransfers.contains { $0.status.isOpen || $0.status == .failed } }
    private var canClear: Bool { model.transfers.transfers.contains { $0.status.isFinished } }

    @ToolbarContentBuilder
    private var toolbarItems: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            Button(action: { model.retryTransfers(targets) }) {
                Label("Retry", systemImage: "arrow.clockwise")
            }
            .disabled(!canRetry)
            .help("Try the selected transfers again")
            Button(action: { model.cancelTransfers(targets) }) {
                Label("Cancel", systemImage: "xmark.circle")
            }
            .disabled(!canCancel)
            .help("Stop the selected transfers")
            Toggle(isOn: $hideCompleted) {
                Label("Hide Completed", systemImage: "eye.slash")
            }
            .help("Hide completed transfers")
            Button(action: { model.clearFinishedTransfers() }) {
                Label("Clear Finished", systemImage: "trash")
            }
            .disabled(!canClear)
            .help("Remove completed and cancelled transfers from the list")
        }
    }

    /// Running first, then waiting in their order, then failed, then done.
    private var shown: [Transfer] {
        let rank: [TransferStatus: Int] = [.active: 0, .pending: 1, .failed: 2, .completed: 3, .cancelled: 4]
        return model.transfers.transfers
            .filter { !(hideCompleted && $0.status == .completed) && $0.status != .cancelled }
            .enumerated()
            .sorted { a, b in
                let ra = rank[a.element.status]!, rb = rank[b.element.status]!
                if ra != rb { return ra < rb }
                if a.element.status.isFinished || a.element.status == .failed { return a.element.updated > b.element.updated }
                return a.offset < b.offset
            }
            .map(\.element)
    }

    private var targets: Set<UUID> { selection.isEmpty ? Set(shown.map(\.id)) : selection }
    private var targetTransfers: [Transfer] { model.transfers.transfers.filter { targets.contains($0.id) } }

    @ViewBuilder
    private func menu(for transfer: Transfer) -> some View {
        let ids = selection.contains(transfer.id) ? selection : [transfer.id]
        if transfer.status == .failed || transfer.status == .cancelled {
            Button("Retry") { model.retryTransfers(ids) }
        }
        if transfer.status.isOpen || transfer.status == .failed {
            Button("Cancel") { model.cancelTransfers(ids) }
        }
        if transfer.kind == .download, transfer.status == .completed {
            Button("Show in Finder") {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: transfer.localPath)])
            }
        }
        if transfer.kind == .upload {
            Button("Show Source in Finder") {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: transfer.localPath)])
            }
        }
        if let error = transfer.error {
            Button("Copy Error") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(error.message, forType: .string)
            }
        }
        Divider()
        Button("Remove from List") { model.removeTransfers(ids) }
    }
}

/// Counts and the overall speed above the list.
struct TransferSummary: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let all = model.transfers.transfers
        let active = all.filter { $0.status == .active }
        let pending = all.filter { $0.status == .pending }
        let failed = all.filter { $0.status == .failed }.count
        let completed = all.filter { $0.status == .completed }.count
        let speed = model.transfers.speeds.values.reduce(0, +)
        let remaining = (active + pending).reduce(Int64(0)) { $0 + max(0, $1.size - $1.done) }
        HStack(spacing: 18) {
            stat("\(active.count)", "running")
            stat("\(pending.count)", "waiting")
            stat("\(failed)", "failed", color: failed > 0 ? Color.problem : nil)
            stat("\(completed)", "completed")
            Spacer()
            if speed > 0 {
                VStack(alignment: .trailing, spacing: 1) {
                    Text(Format.speed(speed)).font(.callout.monospacedDigit())
                    if remaining > 0 {
                        Text("\(Format.bytes(remaining)) left · about \(Format.duration(Double(remaining) / speed))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else if !model.networkAvailable && !pending.isEmpty {
                Label("Waiting for the network", systemImage: "wifi.slash")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func stat(_ value: String, _ label: String, color: Color? = nil) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value).font(.title3.monospacedDigit().weight(.semibold)).foregroundStyle(color ?? .primary)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct TransferRow: View {
    @EnvironmentObject private var model: AppModel
    let transfer: Transfer
    let speed: Double?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: transfer.kind == .upload ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .font(.title2)
                .foregroundStyle(iconColor)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(transfer.name).lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Text(status)
                        .font(.caption)
                        .foregroundStyle(transfer.status == .failed ? Color.problem : .secondary)
                }
                if transfer.status == .active || (transfer.status == .pending && transfer.done > 0) {
                    ProgressView(value: fraction)
                        .progressViewStyle(.linear)
                        .controlSize(.small)
                }
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .padding(.vertical, 3)
    }

    private var fraction: Double {
        transfer.size > 0 ? min(1, Double(transfer.done) / Double(transfer.size)) : 0
    }

    private var iconColor: Color {
        switch transfer.status {
        case .failed: .problem
        case .completed: .healthy
        case .active: .accentColor
        default: .secondary
        }
    }

    private var status: String {
        switch transfer.status {
        case .active:
            var text = "\(Format.bytes(transfer.done)) of \(Format.bytes(transfer.size))"
            if let speed {
                text += speed > 0 ? " · \(Format.speed(speed))" : " · stalled"
                if speed > 0 { text += " · \(Format.duration(Double(transfer.size - transfer.done) / speed)) left" }
            }
            return text
        case .pending:
            if let retryAt = transfer.retryAt, retryAt > Date() {
                return "Retrying \(Format.ago(retryAt).replacingOccurrences(of: "in ", with: "in "))"
            }
            if transfer.error?.category == .serverUnreachable { return "Waiting for the server" }
            return transfer.done > 0 ? "Waiting · resumes at \(Format.bytes(transfer.done))" : "Waiting"
        case .completed:
            return "\(Format.bytes(transfer.size)) · \(Format.ago(transfer.updated))"
        case .failed:
            return transfer.error?.category.title ?? "Failed"
        case .cancelled:
            return "Cancelled"
        }
    }

    private var detail: String {
        if transfer.status == .failed || transfer.status == .pending, let error = transfer.error {
            return error.message
        }
        let server = model.connection(transfer.connectionID)?.displayName ?? "deleted connection"
        let remote = "\(server): \(transfer.remotePath)"
        let local = (transfer.localPath as NSString).abbreviatingWithTildeInPath
        return transfer.kind == .upload ? "\(local) → \(remote)" : "\(remote) → \(local)"
    }
}
