// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import AppKit
import SambaLiteCore
import SwiftUI

/// The sync monitor: do my syncs work, and if not, what do I do?
struct SyncsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            if model.syncs.configs.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 44))
                        .foregroundStyle(.secondary)
                    Text("No syncs yet").font(.title3)
                    Text("Open a share, choose a folder and click Sync to keep it in step with a folder on this Mac.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 380)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        OverallLine()
                        ForEach(sorted) { config in
                            SyncCard(config: config)
                        }
                    }
                    .padding(18)
                }
            }
        }
        .navigationTitle("Syncs")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { model.runAllSyncs() } label: { Label("Sync All Now", systemImage: "arrow.triangle.2.circlepath") }
                    .disabled(model.syncs.configs.isEmpty)
                    .help("Run every sync that is not paused")
            }
        }
        .sheet(item: Binding(
            get: { model.syncDetail.flatMap { id in model.syncs.configs.first { $0.id == id } } },
            set: { model.syncDetail = $0?.id }
        )) { config in
            SyncDetail(config: config)
        }
    }

    /// Problems first, then by name.
    private var sorted: [SyncConfig] {
        model.syncs.configs.sorted { a, b in
            let sa = model.health(of: a).severity, sb = model.health(of: b).severity
            if sa != sb { return sa > sb }
            return a.name.localizedStandardCompare(b.name) == .orderedAscending
        }
    }
}

/// The most urgent problem in one line, or that all is well.
struct OverallLine: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let worst = model.syncs.configs.map { ($0, model.health(of: $0)) }.max { $0.1.severity < $1.1.severity }
        HStack(spacing: 8) {
            if let (config, health) = worst, health.severity > 0 {
                Image(systemName: health.severity == 2 ? "xmark.octagon.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(health.severity == 2 ? Color.problem : .warning)
                Text(problemText(config, health))
            } else if !model.syncs.running.isEmpty {
                ProgressView().controlSize(.small)
                Text(Format.count(model.syncs.running.count, "sync") + " running")
            } else {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.healthy)
                Text("All syncs are up to date.")
            }
        }
        .font(.headline)
    }

    private func problemText(_ config: SyncConfig, _ health: SyncHealth) -> String {
        switch health {
        case .failed, .localFolderMissing: "\(config.name) does not sync."
        case .withErrors(let n): "\(config.name) has \(Format.count(n, "file")) with errors."
        case .overdue: "\(config.name) has not synced for a while."
        default: health.title
        }
    }
}

struct SyncCard: View {
    @EnvironmentObject private var model: AppModel
    let config: SyncConfig

    var body: some View {
        let health = model.health(of: config)
        let runs = model.syncs.history[config.id] ?? []
        let progress = model.syncs.running[config.id]
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                Image(systemName: directionIcon)
                    .font(.title2)
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(config.name).font(.headline)
                        if config.mirrors {
                            Text("Mirror")
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Color.orange.opacity(0.2), in: Capsule())
                        }
                    }
                    Text(pathLine).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                    Text("\(config.direction.title) · \(Format.interval(config.intervalMinutes))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                HealthBadge(health: model.syncs.queued.contains(config.id) && progress == nil ? .running : health,
                            queued: model.syncs.queued.contains(config.id) && progress == nil)
            }

            if let progress {
                VStack(alignment: .leading, spacing: 3) {
                    if let expected = progress.expected, expected > 0, progress.phase != .listing {
                        ProgressView(value: min(1, Double(progress.checked) / Double(expected)))
                    } else {
                        ProgressView().progressViewStyle(.linear)
                    }
                    Text(progressText(progress))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            } else if let last = runs.last {
                LastRunLine(run: last)
            }

            HStack(alignment: .center) {
                RunDots(runs: runs)
                Spacer()
                if progress != nil || model.syncs.queued.contains(config.id) {
                    Button("Stop") { model.stopSync(config.id) }
                } else {
                    Button("Sync Now") { model.runSync(config.id) }
                        .disabled(!config.enabled)
                }
                Button("Details") { model.syncDetail = config.id }
                Menu {
                    Button(config.enabled ? "Pause" : "Resume") { model.setPaused(config, config.enabled) }
                    Button("Edit Sync…") { model.editingSync = SyncDraft(config: config, isNew: false) }
                    Button("Show in Finder") { NSWorkspace.shared.open(config.localURL) }
                    if let connection = model.connection(config.connectionID) {
                        Button("Open on the Share") {
                            model.selection = .connection(connection.id)
                            model.browser(for: connection).go(to: config.remotePath)
                        }
                    }
                    Divider()
                    Button("Remove Sync…") { model.removeSync(config) }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
            }
        }
        .padding(14)
        .background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .strokeBorder(health.severity == 2 ? Color.problem.opacity(0.5) : Color.primary.opacity(0.08)))
        .opacity(config.enabled ? 1 : 0.65)
    }

    private var directionIcon: String {
        switch config.direction {
        case .upload: "arrow.up.circle"
        case .download: "arrow.down.circle"
        case .bidirectional: "arrow.up.arrow.down.circle"
        }
    }

    private var pathLine: String {
        let local = (config.localPath as NSString).abbreviatingWithTildeInPath
        let connection = model.connection(config.connectionID)
        let remote = "\(connection?.displayName ?? "?")/\(config.remotePath)"
        return "\(local) ⇄ \(remote)"
    }

    private func progressText(_ progress: SyncProgress) -> String {
        switch progress.phase {
        case .listing: return "Comparing both sides…"
        case .mirroring: return "Mirroring: \(progress.current ?? "")"
        case .syncing:
            let count = progress.expected.map { "\(progress.checked) of about \($0)" } ?? "\(progress.checked)"
            return "\(count) files checked" + (progress.current.map { " · \($0)" } ?? "")
        }
    }
}

struct HealthBadge: View {
    let health: SyncHealth
    var queued = false

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(queued ? "Waiting to run" : health.title)
        }
        .font(.caption.weight(.medium))
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(color.opacity(0.12), in: Capsule())
    }

    private var color: Color {
        switch health {
        case .running: .accentColor
        case .paused, .neverRun: .secondary
        default: [Color.healthy, .warning, .problem][health.severity]
        }
    }
}

/// Uploaded, downloaded, skipped and errors are always all four there, so
/// "0 errors" is a statement.
struct LastRunLine: View {
    let run: SyncRunResult

    var body: some View {
        HStack(spacing: 14) {
            Label("\(run.counts.uploaded)", systemImage: "arrow.up").help("Uploaded")
            Label("\(run.counts.downloaded)", systemImage: "arrow.down").help("Downloaded")
            Label("\(run.counts.skipped)", systemImage: "equal").help("Skipped (unchanged or not to be synced)")
            Label("\(run.errorCount)", systemImage: "exclamationmark.triangle")
                .foregroundStyle(run.errorCount > 0 ? Color.warning : .secondary)
                .help("Errors")
            if run.counts.deleted > 0 {
                Label("\(run.counts.deleted)", systemImage: "trash").help("Removed by mirror")
            }
            if run.counts.conflicts > 0 {
                Label("\(run.counts.conflicts)", systemImage: "doc.on.doc").help("Conflicts: the other version was kept as a copy")
            }
            Spacer()
            Text("Last run \(Format.ago(run.ended))")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .labelStyle(CompactLabel())
    }
}

struct CompactLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 3) {
            configuration.icon
            configuration.title.monospacedDigit()
        }
    }
}

/// The last 20 runs as dots: green completed, yellow with errors, red
/// failed, grey stopped.
struct RunDots: View {
    let runs: [SyncRunResult]

    var body: some View {
        HStack(spacing: 3) {
            ForEach(runs.suffix(20)) { run in
                Circle()
                    .fill(color(run.status))
                    .frame(width: 7, height: 7)
                    .help("\(Format.date(run.ended)): \(text(run))")
            }
        }
    }

    private func color(_ status: SyncRunStatus) -> Color {
        switch status {
        case .completed: .healthy
        case .completedWithErrors: .warning
        case .failed: .problem
        case .stopped: .secondary.opacity(0.5)
        }
    }

    private func text(_ run: SyncRunResult) -> String {
        switch run.status {
        case .completed: "completed, \(run.counts.transferred) transferred"
        case .completedWithErrors: "\(run.errorCount) errors"
        case .failed: run.failure?.message ?? "failed"
        case .stopped: "stopped"
        }
    }
}

/// Errors of the last run grouped by cause, with what to do, and a search
/// through the last run's log: "why was this file not synced?".
struct SyncDetail: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let config: SyncConfig
    @State private var log = ""
    @State private var query = ""

    var body: some View {
        let runs = model.syncs.history[config.id] ?? []
        let last = runs.last
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(config.name).font(.title2.weight(.semibold))
                HealthBadge(health: model.health(of: config))
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            if let last {
                LastRunLine(run: last)
                if let failure = last.failure {
                    GroupBox {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(failure.category.title).font(.headline)
                            Text(failure.message).font(.callout)
                            Text(failure.category.advice).font(.callout).foregroundStyle(.secondary)
                            if failure.category == .localFolderMissing {
                                Button("Edit Sync…") {
                                    dismiss()
                                    model.editingSync = SyncDraft(config: config, isNew: false)
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                if let note = last.mirrorNote {
                    Label(note, systemImage: "exclamationmark.triangle").foregroundStyle(Color.warning)
                }
                if !last.errors.isEmpty {
                    Text("Errors of the last run").font(.headline)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(groups(last.errors), id: \.0) { category, errors in
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("\(category.title) (\(errors.count))").font(.subheadline.weight(.semibold))
                                    Text(category.advice).font(.caption).foregroundStyle(.secondary)
                                    ForEach(errors, id: \.path) { error in
                                        HStack(alignment: .firstTextBaseline) {
                                            Text(error.path.isEmpty ? "(whole run)" : error.path)
                                                .font(.callout.monospaced())
                                                .textSelection(.enabled)
                                            if error.streak > 1 {
                                                Text("for \(error.streak) runs")
                                                    .font(.caption2)
                                                    .padding(.horizontal, 4)
                                                    .background(Color.warning.opacity(0.2), in: Capsule())
                                            }
                                        }
                                        .help(error.message)
                                    }
                                }
                            }
                            if last.errorCount > last.errors.count {
                                Text("… and \(last.errorCount - last.errors.count) more; see the log.")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 180)
                }
            } else {
                Text("This sync has not run yet.").foregroundStyle(.secondary)
            }
            Divider()
            HStack {
                Text("Why was a file not synced?").font(.headline)
                Spacer()
                Toggle("Notify", isOn: Binding(
                    get: { config.notify },
                    set: { value in
                        var changed = config
                        changed.notify = value
                        Task { await model.coordinator.update(changed) }
                    }))
                .toggleStyle(.switch)
                .controlSize(.small)
                .help("Notify when this sync starts failing or works again")
            }
            TextField("Search the last run by name or path", text: $query)
                .textFieldStyle(.roundedBorder)
            ScrollView {
                Text(matches.isEmpty ? (query.isEmpty ? "The last run's log is empty." : "Not in the last run's log.") : matches)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 160)
            .padding(6)
            .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 6))
            HStack {
                Button("Copy Diagnostics") { copyDiagnostics(runs) }
                    .help("A text report for bug reports: settings, runs and the end of the log; no usernames or passwords")
                Spacer()
                Button("Show in Finder") { NSWorkspace.shared.open(config.localURL) }
            }
        }
        .padding(20)
        .frame(width: 640, height: 600)
        .task { log = await model.coordinator.log(of: config.id) }
    }

    private var matches: String {
        let lines = log.split(separator: "\n").map(String.init)
        let needle = query.trimmingCharacters(in: .whitespaces)
        let hits = needle.isEmpty ? Array(lines.suffix(300)) : lines.filter {
            $0.range(of: needle, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
        return hits.prefix(500).map { line in
            // drop the date, keep the time
            let parts = line.split(separator: "\t", maxSplits: 1)
            guard parts.count == 2, let t = parts[0].firstIndex(of: "T") else { return line }
            return String(parts[0][parts[0].index(after: t)...].prefix(8)) + "  " + parts[1].replacingOccurrences(of: "\t", with: "  ")
        }.joined(separator: "\n")
    }

    private func groups(_ errors: [SyncFileError]) -> [(FailureCategory, [SyncFileError])] {
        Dictionary(grouping: errors, by: \.category).sorted { $0.value.count > $1.value.count }
    }

    private func copyDiagnostics(_ runs: [SyncRunResult]) {
        var text = "SambaLite for Mac \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev")"
        text += ", macOS \(ProcessInfo.processInfo.operatingSystemVersionString)\n"
        let connection = model.connection(config.connectionID)
        text += "Sync \(config.name): \(config.direction.rawValue), every \(config.intervalMinutes) min, mirror \(config.mirrors), trash \(config.mirrorUseTrash), enabled \(config.enabled)\n"
        text += "Server \(connection?.server ?? "?"):\(connection?.port ?? 0), share \(connection?.share ?? "?"), encrypted \(connection?.encryptData ?? false)\n"
        text += "Remote \(config.remotePath), local \(config.localPath)\n\nRuns:\n"
        for run in runs {
            text += "\(Format.date(run.started)) \(run.status.rawValue) up \(run.counts.uploaded) down \(run.counts.downloaded) skipped \(run.counts.skipped) errors \(run.errorCount)"
            if let failure = run.failure { text += " [\(failure.category.rawValue): \(failure.message)]" }
            text += "\n"
        }
        text += "\nEnd of the last log:\n" + log.split(separator: "\n").suffix(200).joined(separator: "\n")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        model.show(notice: "Diagnostics copied.")
    }
}
