// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// What went wrong, in the terms a user can act on. Every error a transfer
/// or sync meets is sorted into one of these; the monitor groups by them.
public enum FailureCategory: String, Codable, Sendable, CaseIterable {
    case serverUnreachable
    case loginRejected
    case accessDenied
    case notFound
    case invalidName
    case pathTooLong
    case remoteFull
    case localFull
    case localFolderMissing
    case remoteFolderMissing
    case folderSkipped
    case fileChanged
    case nameCollision
    case cancelled
    case other

    /// Worth trying again without the user changing anything.
    public var isTransient: Bool {
        switch self {
        case .serverUnreachable, .fileChanged: true
        default: false
        }
    }

    public var title: String {
        switch self {
        case .serverUnreachable: "Server not reachable"
        case .loginRejected: "Login rejected"
        case .accessDenied: "Access denied"
        case .notFound: "File or folder not found"
        case .invalidName: "Invalid file name"
        case .pathTooLong: "Path too long"
        case .remoteFull: "Share is full"
        case .localFull: "Not enough space on this Mac"
        case .localFolderMissing: "Local folder missing"
        case .remoteFolderMissing: "Folder on the share missing"
        case .folderSkipped: "Folder could not be read"
        case .fileChanged: "File changed during the transfer"
        case .nameCollision: "Names differ only in case"
        case .cancelled: "Cancelled"
        case .other: "Other error"
        }
    }

    public var advice: String {
        switch self {
        case .serverUnreachable: "Check that the Mac is in the server's network (or VPN) and the server is on. SambaLite tries again by itself."
        case .loginRejected: "Edit the connection and enter the username and password again."
        case .accessDenied: "The account may not write or read there. Check the share's permissions on the server."
        case .notFound: "The file was moved or deleted while SambaLite worked on it."
        case .invalidName: "Rename the file: SMB does not allow \\ / : * ? \" < > | or a trailing dot or space."
        case .pathTooLong: "Shorten folder or file names."
        case .remoteFull: "Free space on the server."
        case .localFull: "Free space on this Mac."
        case .localFolderMissing: "Connect the disk the folder is on, or choose the folder again in the sync's settings."
        case .remoteFolderMissing: "The folder on the share was renamed or removed. Set the sync up again."
        case .folderSkipped: "A folder could not be listed; its files were left alone this time."
        case .fileChanged: "The file was being written to. It is synced on the next run."
        case .nameCollision: "Two files differ only in upper and lower case, which this Mac cannot keep apart. Rename one."
        case .cancelled: ""
        case .other: "See the message for details."
        }
    }
}

/// An error with its category and a message for the log.
public struct Failure: Error, Codable, Sendable, Equatable, LocalizedError {
    public var category: FailureCategory
    public var message: String

    public init(_ category: FailureCategory, _ message: String) {
        self.category = category
        self.message = message
    }

    public var errorDescription: String? { message }

    /// Sorts any error that comes out of AMSMB2 (POSIX errors mapped from
    /// NT status codes), Foundation or this package.
    public static func from(_ error: any Error) -> Failure {
        if let failure = error as? Failure { return failure }
        if error is CancellationError { return Failure(.cancelled, "Cancelled") }
        let ns = error as NSError
        let text = ns.localizedDescription
        if ns.domain == NSPOSIXErrorDomain, let code = POSIXErrorCode(rawValue: Int32(ns.code)) {
            // libsmb2 maps STATUS_LOGON_FAILURE to ECONNREFUSED; only the
            // text tells a wrong password from a closed port.
            let upper = text.uppercased()
            if upper.contains("LOGON_FAILURE") || upper.contains("LOGON FAILURE")
                || upper.contains("WRONG_PASSWORD") || upper.contains("ACCOUNT_LOCKED")
            {
                return Failure(.loginRejected, "The server rejected the username or password.")
            }
            return Failure(category(posix: code), describe(code, text))
        }
        if ns.domain == NSCocoaErrorDomain {
            switch ns.code {
            case NSFileNoSuchFileError, NSFileReadNoSuchFileError:
                return Failure(.notFound, text)
            case NSFileWriteOutOfSpaceError:
                return Failure(.localFull, text)
            case NSFileReadNoPermissionError, NSFileWriteNoPermissionError:
                return Failure(.accessDenied, text)
            case NSFileWriteInvalidFileNameError:
                return Failure(.invalidName, text)
            default:
                break
            }
            if let underlying = ns.userInfo[NSUnderlyingErrorKey] as? NSError {
                var failure = from(underlying)
                failure.message = text
                return failure
            }
        }
        return Failure(.other, text)
    }

    static func category(posix code: POSIXErrorCode) -> FailureCategory {
        switch code {
        case .ECONNREFUSED, .ECONNRESET, .ECONNABORTED, .ETIMEDOUT, .ENOTCONN, .EPIPE,
             .EHOSTUNREACH, .EHOSTDOWN, .ENETUNREACH, .ENETDOWN, .ENETRESET, .EAGAIN, .EIO,
             .ESHUTDOWN, .EBADF:
            .serverUnreachable
        case .EACCES, .EPERM, .EROFS:
            .accessDenied
        case .ENOENT, .ENOTDIR:
            .notFound
        case .ENOSPC, .EDQUOT:
            .remoteFull
        case .ENAMETOOLONG:
            .pathTooLong
        case .EINVAL, .EILSEQ:
            .invalidName
        case .ECANCELED:
            .cancelled
        case .ETXTBSY, .EDEADLK:
            // sharing violation, lock conflict: someone has the file open
            .fileChanged
        default:
            .other
        }
    }

    private static func describe(_ code: POSIXErrorCode, _ text: String) -> String {
        // AMSMB2 turns NT status codes into POSIX codes and often leaves
        // the description as "Error code n: ", which tells nobody anything.
        let plain = String(cString: strerror(code.rawValue))
        let cleaned = text.trimmingCharacters(in: .whitespaces)
        if cleaned.isEmpty || cleaned.hasPrefix("Error code") || cleaned.hasPrefix("The operation couldn") {
            return plain
        }
        return cleaned.contains(plain) ? cleaned : "\(plain): \(cleaned)"
    }
}
