// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation

extension NSLock {
    /// `withLock` needs macOS 14; the app runs on 12.
    @inlinable
    func sync<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}

/// A value several threads read and write.
public final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    public init(_ value: Value) { self.value = value }

    public func get() -> Value { lock.sync { value } }

    public func set(_ newValue: Value) { lock.sync { value = newValue } }

    @discardableResult
    public func update<T>(_ body: (inout Value) throws -> T) rethrows -> T {
        try lock.sync { try body(&value) }
    }
}
