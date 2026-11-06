// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// A file or folder on this Mac, relative to a sync's local folder.
public struct LocalItem: Sendable, Equatable, Hashable {
    /// "/"-separated, relative to the root; "" is the root.
    public var path: String
    public var isDirectory: Bool
    public var size: Int64
    public var modified: Date

    public init(path: String, isDirectory: Bool, size: Int64, modified: Date) {
        self.path = path
        self.isDirectory = isDirectory
        self.size = size
        self.modified = modified
    }

    public var name: String { RemotePath.name(path) }
}

public enum LocalFiles {
    static let keys: [URLResourceKey] = [
        .isDirectoryKey, .isSymbolicLinkKey, .isRegularFileKey, .fileSizeKey, .contentModificationDateKey,
    ]

    /// Size and time of one file or folder; nil when there is none.
    /// Symbolic links are reported as nil: a sync does not follow them.
    public static func stat(_ url: URL) -> LocalItem? {
        // URL caches resource values per instance; a file that grew since
        // the last look would report its old size
        var url = url
        url.removeAllCachedResourceValues()
        guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
        if values.isSymbolicLink == true { return nil }
        return LocalItem(
            path: url.lastPathComponent, isDirectory: values.isDirectory ?? false,
            size: Int64(values.fileSize ?? 0),
            modified: values.contentModificationDate ?? Date(timeIntervalSince1970: 0))
    }

    /// Whether the folder is there and is a folder. A sync whose folder
    /// is on a disk that is not connected must not see an empty folder.
    public static func isFolder(_ url: URL) -> Bool {
        var isDirectory: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) && isDirectory.boolValue
    }

    /// Every file and folder below `root`, folders before their contents.
    /// Symbolic links and ignored names are left out (`symlinks` counts
    /// the links); a folder that cannot be read lands in `skipped`.
    public static func walk(_ root: URL, skip: (String) -> Bool = { Names.isIgnored($0) }) throws
        -> (items: [LocalItem], skipped: [(path: String, failure: Failure)], symlinks: [String])
    {
        guard isFolder(root) else {
            throw Failure(.localFolderMissing, "\(root.path) is not there")
        }
        var items: [LocalItem] = []
        var skipped: [(String, Failure)] = []
        var symlinks: [String] = []
        var queue = [""]
        let fm = FileManager.default
        while !queue.isEmpty {
            let folder = queue.removeFirst()
            let folderURL = folder.isEmpty ? root : root.appendingPathComponent(folder)
            let children: [URL]
            do {
                children = try fm.contentsOfDirectory(at: folderURL, includingPropertiesForKeys: keys, options: [])
            } catch {
                if folder.isEmpty { throw Failure.from(error) }
                skipped.append((folder, Failure(.folderSkipped, Failure.from(error).message)))
                continue
            }
            for child in children {
                let name = child.lastPathComponent
                if skip(name) { continue }
                let path = RemotePath.join(folder, name)
                guard let values = try? child.resourceValues(forKeys: Set(keys)) else { continue }
                if values.isSymbolicLink == true {
                    symlinks.append(path)
                    continue
                }
                let isDirectory = values.isDirectory ?? false
                if !isDirectory && values.isRegularFile != true { continue }  // sockets, pipes
                items.append(LocalItem(
                    path: path, isDirectory: isDirectory, size: Int64(values.fileSize ?? 0),
                    modified: values.contentModificationDate ?? Date(timeIntervalSince1970: 0)))
                if isDirectory { queue.append(path) }
            }
        }
        return (items, skipped, symlinks)
    }

    /// Free space for a file of `bytes` on the volume of `url`, keeping a
    /// reserve of 10 MB like the Android app.
    public static func hasRoom(for bytes: Int64, at url: URL) -> Bool {
        let reserve: Int64 = 10 * 1024 * 1024
        var probe = url
        while !FileManager.default.fileExists(atPath: probe.path) && probe.pathComponents.count > 1 {
            probe.deleteLastPathComponent()
        }
        guard let values = try? probe.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        else { return true }
        let free = values.volumeAvailableCapacityForImportantUsage ?? Int64(values.volumeAvailableCapacity ?? Int.max)
        return free >= bytes + reserve
    }

    /// Moves `url` to the Trash, or deletes it where there is no Trash.
    public static func trashOrDelete(_ url: URL) throws {
        do {
            try FileManager.default.trashItem(at: url, resultingItemURL: nil)
        } catch {
            try FileManager.default.removeItem(at: url)
        }
    }

    /// A name in `folder` that is not taken: "a.txt", "a 2.txt", ...
    public static func freeName(_ name: String, in folder: URL) -> String {
        var candidate = name
        var number = 2
        while FileManager.default.fileExists(atPath: folder.appendingPathComponent(candidate).path) {
            candidate = Names.numbered(name, number)
            number += 1
        }
        return candidate
    }
}
