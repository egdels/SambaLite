// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
import SambaLiteCore

enum Format {
    static func bytes(_ count: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: count, countStyle: .file)
    }

    static func speed(_ bytesPerSecond: Double) -> String {
        bytes(Int64(bytesPerSecond)) + "/s"
    }

    /// "3 min", "45 s", "2 h 10 min"
    static func duration(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        if total < 60 { return "\(total) s" }
        let minutes = total / 60
        if minutes < 60 { return "\(minutes) min" }
        return "\(minutes / 60) h \(minutes % 60) min"
    }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        f.doesRelativeDateFormatting = true
        return f
    }()

    static func date(_ date: Date) -> String {
        date.timeIntervalSince1970 <= 0 ? "--" : dateFormatter.string(from: date)
    }

    nonisolated(unsafe) private static let relative: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .full
        return f
    }()

    /// "5 minutes ago"; "just now" under a minute.
    static func ago(_ date: Date, now: Date = Date()) -> String {
        if now.timeIntervalSince(date) < 60 { return "just now" }
        return relative.localizedString(for: date, relativeTo: now)
    }

    static func interval(_ minutes: Int) -> String {
        switch minutes {
        case 0: "Manually"
        case ..<60: "Every \(minutes) minutes"
        case 60: "Every hour"
        default: "Every \(minutes / 60) hours"
        }
    }

    static func count(_ n: Int, _ singular: String, _ plural: String? = nil) -> String {
        n == 1 ? "1 \(singular)" : "\(n) \(plural ?? singular + "s")"
    }
}
