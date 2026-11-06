// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Rules about file names that both sides of a sync and a transfer agree on.
public enum Names {
    /// Suffix of a file that is still being written. The finished file gets
    /// its real name by a rename, so a half-written file never carries it.
    public static let partSuffix = ".sambalite-part"
    /// Suffix of the replaced file while an upload swaps in the new one.
    public static let oldSuffix = ".sambalite-old"
    /// Mirror mode moves removed entries here, at the root of the target.
    public static let trashFolder = ".sambalite-trash"
    /// Tolerance when two modification times are compared: SMB servers,
    /// FAT disks and clock jitter round differently. The Android app uses
    /// the same three seconds.
    public static let timeTolerance: TimeInterval = 3

    /// Names a sync never carries across: the Mac's own bookkeeping files,
    /// which would otherwise land on every share (`.DS_Store`, AppleDouble
    /// `._x` files, volume folders), half-written files and the trash.
    public static func isIgnored(_ name: String) -> Bool {
        if name.hasSuffix(partSuffix) || name.hasSuffix(oldSuffix) { return true }
        if name.hasPrefix("._") { return true }
        switch name {
        case ".DS_Store", "Icon\r", ".Spotlight-V100", ".Trashes", ".fseventsd",
             ".TemporaryItems", ".DocumentRevisions-V100", trashFolder:
            return true
        default:
            return false
        }
    }

    /// The key two names are matched by: NFC, because macOS file systems
    /// may hand out decomposed umlauts while SMB servers usually store
    /// composed ones, and the same folder must not appear twice.
    public static func key(_ name: String) -> String {
        name.precomposedStringWithCanonicalMapping
    }

    /// The key that finds names a case-insensitive file system (the Mac's
    /// default, Windows servers) cannot keep apart.
    public static func foldedKey(_ name: String) -> String {
        key(name).folding(options: [.caseInsensitive], locale: nil)
    }

    /// Characters SMB does not allow in a name (MS-FSCC 2.1.5.2), plus the
    /// trailing dot or space Windows servers strip silently.
    public static func invalidSMBNameReason(_ name: String) -> String? {
        if name.isEmpty { return "empty name" }
        let forbidden = CharacterSet(charactersIn: "\\/:*?\"<>|")
        if name.unicodeScalars.contains(where: { forbidden.contains($0) || $0.value < 0x20 }) {
            return "contains a character the server does not allow (\\ / : * ? \" < > |)"
        }
        if name.hasSuffix(".") || name.hasSuffix(" ") {
            return "ends with a dot or a space"
        }
        return nil
    }

    /// "report.pdf" -> "report (conflict 2026-11-06 1430).pdf": the name a
    /// losing version keeps when both sides changed a file.
    public static func conflictName(_ name: String, at date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HHmmss"
        return insert(" (conflict \(formatter.string(from: date)))", into: name)
    }

    /// The name of the half-written copy of `name`. It carries a short
    /// fingerprint of the source's size and modification time, so an
    /// interrupted transfer resumes only into a copy of the same version;
    /// a source that changed meanwhile starts a new part file.
    public static func partName(_ name: String, size: Int64, modified: Date) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in "\(size):\(Int64(modified.timeIntervalSince1970.rounded(.down)))".utf8 {
            hash = (hash ^ UInt64(byte)) &* 0x100_0000_01b3
        }
        let tag = String(hash & 0xffff_ffff, radix: 16)
        var base = name
        // 255 bytes is the limit on both sides
        while base.utf8.count + tag.utf8.count + partSuffix.utf8.count + 1 > 250 { base.removeLast() }
        return "\(base).\(tag)\(partSuffix)"
    }

    /// "photo.jpg" -> "photo 2.jpg", as Finder numbers copies.
    public static func numbered(_ name: String, _ number: Int) -> String {
        insert(" \(number)", into: name)
    }

    private static func insert(_ text: String, into name: String) -> String {
        let ext = (name as NSString).pathExtension
        let base = (name as NSString).deletingPathExtension
        if ext.isEmpty || base.isEmpty { return name + text }
        return base + text + "." + ext
    }
}

/// Paths inside a share: "/"-separated, relative to the share root, no
/// leading or trailing slash; "" is the root.
public enum RemotePath {
    public static func join(_ parent: String, _ name: String) -> String {
        parent.isEmpty ? name : parent + "/" + name
    }

    public static func parent(_ path: String) -> String {
        guard let slash = path.lastIndex(of: "/") else { return "" }
        return String(path[..<slash])
    }

    public static func name(_ path: String) -> String {
        guard let slash = path.lastIndex(of: "/") else { return path }
        return String(path[path.index(after: slash)...])
    }

    /// Accepts what users type or files contain: backslashes, leading and
    /// trailing slashes, doubled slashes.
    public static func normalized(_ path: String) -> String {
        path.replacingOccurrences(of: "\\", with: "/")
            .split(separator: "/", omittingEmptySubsequences: true)
            .joined(separator: "/")
    }

    public static func components(_ path: String) -> [String] {
        path.split(separator: "/").map(String.init)
    }
}
