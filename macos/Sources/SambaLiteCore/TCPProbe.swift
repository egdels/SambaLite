// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Network

/// Whether a TCP connection to host:port can be opened.
public enum TCPProbe {
    public static func canConnect(host: String, port: Int, timeout: TimeInterval) async -> Bool {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return false }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        let done = Locked(false)
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            @Sendable func finish(_ result: Bool) {
                let first = done.update { value -> Bool in
                    defer { value = true }
                    return !value
                }
                guard first else { return }
                connection.cancel()
                continuation.resume(returning: result)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(true)
                case .failed, .cancelled: finish(false)
                case .waiting: finish(false)
                default: break
                }
            }
            connection.start(queue: .global(qos: .utility))
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { finish(false) }
        }
    }
}
