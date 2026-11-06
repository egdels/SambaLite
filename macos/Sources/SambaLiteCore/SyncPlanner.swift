// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// One step of a sync run. Paths are relative to the sync's two folders.
public enum SyncAction: Equatable, Sendable {
    case createRemoteFolder(remote: String, local: String)
    case createLocalFolder(local: String, remote: String)
    /// `conflictCopy`: the share's version is kept under a conflict name first.
    case upload(local: LocalItem, remote: String, conflictCopy: Bool)
    /// `conflictCopy`: this Mac's version is kept under a conflict name first.
    case download(remote: RemoteItem, local: String, conflictCopy: Bool)
    /// Both sides agree; the baseline is written down.
    case record(key: String, entry: SyncEntry)
    case skip(path: String, reason: String)
    case error(path: String, failure: Failure)
    /// Mirror: removes from the target. `isDirectory` removes a folder
    /// only when it is empty by then.
    case deleteRemote(path: String, isDirectory: Bool)
    case deleteLocal(path: String, isDirectory: Bool)
    case forget(key: String)
}

/// The decisions of a sync run, made from the two listings and the
/// baseline, before anything is touched.
///
/// Rules (the Android app's, plus what it lacked):
/// - Same size and modification time within `Names.timeTolerance` =
///   in step; the baseline is written down.
/// - With a baseline, a side counts as changed when it differs from it.
///   One-way: a changed source wins over an unchanged target even when it
///   is older (a restored backup). Otherwise the newer side wins.
/// - Both ways: the changed side wins; when both changed, the newer one
///   wins and the other version is kept as a conflict copy on its side
///   (the Android app lost it).
/// - Deletions are never carried over, except by mirror mode one way.
/// - A folder that could not be listed on one side is left alone on the
///   other: nothing there is taken for new or deleted (the Android app
///   took an unlistable remote folder for empty and uploaded everything).
/// - Mirror: only entries in the baseline, only when unchanged on the
///   target since, and not at all when the source listing is incomplete,
///   empty while the baseline is not, or when it would remove more than
///   100 entries and more than half of the tracked ones.
public struct SyncPlanner: Sendable {
    public static let mirrorAbsoluteLimit = 100
    public static let mirrorRelativeLimit = 0.5

    public struct Input: Sendable {
        public var direction: SyncDirection
        public var mirror: Bool
        public var local: [LocalItem]
        public var remote: [RemoteItem]
        /// Folders (relative) that could not be listed.
        public var localSkipped: [String]
        public var remoteSkipped: [String]
        public var state: SyncState
        /// Whether this Mac's volume tells "a" from "A" (APFS default: no).
        public var localCaseSensitive: Bool
        public var now: Date

        public init(
            direction: SyncDirection, mirror: Bool, local: [LocalItem], remote: [RemoteItem],
            localSkipped: [String] = [], remoteSkipped: [String] = [], state: SyncState,
            localCaseSensitive: Bool = false, now: Date = Date()
        ) {
            self.direction = direction
            self.mirror = mirror
            self.local = local
            self.remote = remote
            self.localSkipped = localSkipped
            self.remoteSkipped = remoteSkipped
            self.state = state
            self.localCaseSensitive = localCaseSensitive
            self.now = now
        }
    }

    public struct Plan: Equatable, Sendable {
        public var actions: [SyncAction] = []
        /// Mirror skipped or aborted, and why.
        public var mirrorNote: String?
    }

    public static func plan(_ input: Input) -> Plan {
        var plan = Plan()
        let tolerance = Names.timeTolerance
        let direction = input.direction
        let mirror = input.mirror && direction != .bidirectional

        func close(_ a: Date, _ b: Date) -> Bool { abs(a.timeIntervalSince(b)) <= tolerance }

        // MARK: match the two sides by NFC key, then case-insensitively

        var localByKey: [String: LocalItem] = [:]
        for item in input.local { localByKey[Names.key(item.path)] = item }
        var remoteByKey: [String: RemoteItem] = [:]
        var collisions: [RemoteItem] = []
        var remoteFolded: [String: String] = [:]  // folded -> key, for this Mac's case-insensitive disk
        for item in input.remote.sorted(by: { $0.path < $1.path }) {
            let key = Names.key(item.path)
            if !input.localCaseSensitive {
                let folded = Names.foldedKey(item.path)
                if let other = remoteFolded[folded], other != key {
                    collisions.append(item)
                    continue
                }
                remoteFolded[folded] = key
            }
            remoteByKey[key] = item
        }
        for item in collisions {
            plan.actions.append(.error(
                path: item.path,
                failure: Failure(.nameCollision, "\(item.path) differs from another name only in case")))
        }
        // "Foo.txt" here and "foo.txt" there are the same file to a
        // case-insensitive side
        var localOnlyFolded: [String: String] = [:]
        for key in localByKey.keys where remoteByKey[key] == nil {
            localOnlyFolded[key.folding(options: [.caseInsensitive], locale: nil)] = key
        }
        for (key, item) in remoteByKey where localByKey[key] == nil {
            let folded = key.folding(options: [.caseInsensitive], locale: nil)
            if let localKey = localOnlyFolded[folded], let local = localByKey[localKey], local.isDirectory == item.isDirectory {
                localByKey[localKey] = nil
                localByKey[key] = local
                localOnlyFolded[folded] = nil
            }
        }

        let localSkipped = input.localSkipped.map(Names.key)
        let remoteSkipped = input.remoteSkipped.map(Names.key)
        func under(_ key: String, _ folders: [String]) -> Bool {
            folders.contains { key == $0 || key.hasPrefix($0 + "/") }
        }

        var newRemote: [String: String] = [:]
        var newLocal: [String: String] = [:]
        /// Where a new item goes on the other side: under the real name of
        /// its parent there, if the parent exists.
        func remoteTarget(for local: LocalItem) -> String {
            let parentKey = Names.key(RemotePath.parent(local.path))
            let parent = parentKey.isEmpty ? "" : (remoteByKey[parentKey]?.path ?? newRemote[parentKey] ?? parentKey)
            return RemotePath.join(parent, Names.key(local.name))
        }
        func localTarget(for remote: RemoteItem) -> String {
            let parentKey = Names.key(RemotePath.parent(remote.path))
            let parent = parentKey.isEmpty ? "" : (localByKey[parentKey]?.path ?? newLocal[parentKey] ?? RemotePath.parent(remote.path))
            return RemotePath.join(parent, remote.name)
        }
        var mirrorCandidates: [SyncAction] = []
        var tracked = 0
        for (_, entry) in input.state where !entry.isDirectory { tracked += 1 }

        let keys = Set(localByKey.keys).union(remoteByKey.keys).union(input.state.keys).sorted()
        for key in keys {
            let local = localByKey[key]
            let remote = remoteByKey[key]
            let base = input.state[key]

            switch (local, remote) {
            case (nil, nil):
                // gone from both; unless a side could not be read there
                if !under(key, localSkipped) && !under(key, remoteSkipped) {
                    plan.actions.append(.forget(key: key))
                }

            case (let l?, let r?) where l.isDirectory && r.isDirectory:
                if base == nil {
                    plan.actions.append(.record(key: key, entry: SyncEntry(local: l, remote: r, synced: input.now)))
                }

            case (let l?, let r?) where l.isDirectory != r.isDirectory:
                plan.actions.append(.error(
                    path: l.path,
                    failure: Failure(.nameCollision, "\(l.path) is a folder on one side and a file on the other")))

            case (let l?, nil):
                if under(key, remoteSkipped) {
                    plan.actions.append(.skip(path: l.path, reason: "its folder on the share could not be read"))
                } else if direction.sendsUp {
                    if l.isDirectory {
                        let target = remoteTarget(for: l)
                        newRemote[key] = target
                        plan.actions.append(.createRemoteFolder(remote: target, local: l.path))
                    } else if let reason = Names.invalidSMBNameReason(l.name) {
                        plan.actions.append(.error(path: l.path, failure: Failure(.invalidName, "\(l.name): \(reason)")))
                    } else {
                        plan.actions.append(.upload(local: l, remote: remoteTarget(for: l), conflictCopy: false))
                    }
                } else if mirror, let base, base.isDirectory == l.isDirectory,
                          l.isDirectory || (l.size == base.localSize && close(l.modified, base.localModified))
                {
                    mirrorCandidates.append(.deleteLocal(path: l.path, isDirectory: l.isDirectory))
                } else if mirror, base != nil {
                    plan.actions.append(.skip(path: l.path, reason: "gone from the share, but changed here since; kept"))
                } else {
                    plan.actions.append(.skip(path: l.path, reason: "only on this Mac"))
                }

            case (nil, let r?):
                if under(key, localSkipped) {
                    plan.actions.append(.skip(path: r.path, reason: "its folder on this Mac could not be read"))
                } else if direction.sendsDown {
                    if r.isDirectory {
                        let target = localTarget(for: r)
                        newLocal[key] = target
                        plan.actions.append(.createLocalFolder(local: target, remote: r.path))
                    } else {
                        plan.actions.append(.download(remote: r, local: localTarget(for: r), conflictCopy: false))
                    }
                } else if mirror, let base, base.isDirectory == r.isDirectory,
                          r.isDirectory || (r.size == base.remoteSize && close(r.modified, base.remoteModified))
                {
                    mirrorCandidates.append(.deleteRemote(path: r.path, isDirectory: r.isDirectory))
                } else if mirror, base != nil {
                    plan.actions.append(.skip(path: r.path, reason: "gone from this Mac, but changed on the share since; kept"))
                } else {
                    plan.actions.append(.skip(path: r.path, reason: "only on the share"))
                }

            case (let l?, let r?):
                if l.size == r.size && close(l.modified, r.modified) {
                    let known = base.map {
                        $0.localSize == l.size && close($0.localModified, l.modified) && $0.remoteSize == r.size
                            && close($0.remoteModified, r.modified) && $0.localPath == l.path && $0.remotePath == r.path
                    } ?? false
                    if !known {
                        plan.actions.append(.record(key: key, entry: SyncEntry(local: l, remote: r, synced: input.now)))
                    }
                    plan.actions.append(.skip(path: l.path, reason: "unchanged"))
                    continue
                }
                let localChanged = base.map { !($0.localSize == l.size && close($0.localModified, l.modified)) } ?? true
                let remoteChanged = base.map { !($0.remoteSize == r.size && close($0.remoteModified, r.modified)) } ?? true
                let localNewer = l.modified.timeIntervalSince(r.modified) > tolerance
                let remoteNewer = r.modified.timeIntervalSince(l.modified) > tolerance
                let upload = SyncAction.upload(local: l, remote: r.path, conflictCopy: false)
                let download = SyncAction.download(remote: r, local: l.path, conflictCopy: false)

                switch direction {
                case .upload:
                    if base != nil && localChanged && !remoteChanged || localNewer {
                        if let reason = Names.invalidSMBNameReason(l.name) {
                            plan.actions.append(.error(path: l.path, failure: Failure(.invalidName, "\(l.name): \(reason)")))
                        } else {
                            plan.actions.append(upload)
                        }
                    } else {
                        plan.actions.append(.skip(path: l.path, reason: "the share's copy is newer or equally new"))
                    }
                case .download:
                    if base != nil && remoteChanged && !localChanged || remoteNewer {
                        plan.actions.append(download)
                    } else {
                        plan.actions.append(.skip(path: l.path, reason: "this Mac's copy is newer or equally new"))
                    }
                case .bidirectional:
                    if base != nil && localChanged && !remoteChanged {
                        plan.actions.append(upload)
                    } else if base != nil && remoteChanged && !localChanged {
                        plan.actions.append(download)
                    } else {
                        // both changed (or never synced): the newer wins; a
                        // real conflict keeps the other version
                        let conflict = base != nil
                        if localNewer {
                            plan.actions.append(.upload(local: l, remote: r.path, conflictCopy: conflict))
                        } else if remoteNewer {
                            plan.actions.append(.download(remote: r, local: l.path, conflictCopy: conflict))
                        } else {
                            // same time, different size: the share's version wins,
                            // this Mac's is kept
                            plan.actions.append(.download(remote: r, local: l.path, conflictCopy: true))
                        }
                    }
                }
            }
        }

        // MARK: mirror safeguards

        if mirror && !mirrorCandidates.isEmpty {
            let sourceSkipped = direction == .upload ? input.localSkipped : input.remoteSkipped
            let sourceEmpty = direction == .upload ? input.local.isEmpty : input.remote.isEmpty
            let files = mirrorCandidates.filter {
                switch $0 {
                case .deleteLocal(_, let dir), .deleteRemote(_, let dir): !dir
                default: false
                }
            }.count
            if !sourceSkipped.isEmpty {
                plan.mirrorNote = "Mirror skipped: the source could not be read completely."
            } else if sourceEmpty && tracked > 0 {
                plan.mirrorNote = "Mirror skipped: the source is empty, which looks like a mistake."
            } else if files > mirrorAbsoluteLimit && Double(files) > Double(tracked) * mirrorRelativeLimit {
                plan.mirrorNote = "Mirror aborted: it would have removed \(files) of \(tracked) synced files."
            } else {
                // files first, then folders from the deepest up
                let ordered = mirrorCandidates.sorted { a, b in
                    func info(_ action: SyncAction) -> (Bool, Int, String) {
                        switch action {
                        case .deleteLocal(let p, let d), .deleteRemote(let p, let d):
                            return (d, p.split(separator: "/").count, p)
                        default: return (false, 0, "")
                        }
                    }
                    let (ad, adepth, ap) = info(a)
                    let (bd, bdepth, bp) = info(b)
                    if ad != bd { return !ad }
                    if adepth != bdepth { return adepth > bdepth }
                    return ap < bp
                }
                plan.actions.append(contentsOf: ordered)
            }
        }
        return plan
    }
}
