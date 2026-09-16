#if canImport(Network) && canImport(UIKit)
import Foundation
import Network
import UIKit

enum ConnectionState: Equatable {
    case disconnected
    case connecting
    case connected(String)
    case rejected(String)
}

final class ConnectionManager {
    var autoReconnectEnabled: () -> Bool = { false }
    var onStateChanged: ((ConnectionState) -> Void)?
    var onMessage: ((ServerMessage) -> Void)?
    var onProtocolError: ((String) -> Void)?

    private let queue = DispatchQueue(label: "ch.michioxd.sensorithm.network")
    private var connection: NWConnection?
    private var generation = 0
    private var received = Data()
    private var lastAddress = ""
    private var lastPort: UInt16 = 4420
    private var state = ConnectionState.disconnected
    private var handshakeComplete = false

    func connect(address: String, port: UInt16) {
        queue.async {
            guard self.state == .disconnected || self.isRejected else { return }
            self.lastAddress = address
            self.lastPort = port
            self.disconnectInternal(notify: false)
            self.generation += 1
            let generation = self.generation
            self.handshakeComplete = false
            self.received.removeAll(keepingCapacity: true)
            self.update(.connecting)
            let connection = NWConnection(host: NWEndpoint.Host(address), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
            self.connection = connection
            connection.stateUpdateHandler = { [weak self] connectionState in self?.handle(connectionState, generation: generation) }
            connection.start(queue: self.queue)
            self.queue.asyncAfter(deadline: .now() + 10) { [weak self] in
                guard let self, self.generation == generation, self.state == .connecting else { return }
                self.disconnectInternal(notify: true)
            }
        }
    }

    func disconnect() { queue.async { self.disconnectInternal(notify: true) } }

    func sendMask(_ mask: UInt8) { send(Data([mask & 0x3f])) }

    func send(_ message: ClientMessage) {
        guard let frame = try? ClientProtocol.controlFrame(for: message) else { return }
        send(frame)
    }

    private var isRejected: Bool { if case .rejected = state { return true }; return false }

    private func handle(_ connectionState: NWConnection.State, generation: Int) {
        guard generation == self.generation else { return }
        switch connectionState {
        case .ready:
            let metadata = ["name": UIDevice.current.name, "os": "iOS \(UIDevice.current.systemVersion)", "app": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown"]
            guard let data = try? JSONSerialization.data(withJSONObject: metadata), let newline = "\n".data(using: .utf8) else { disconnectInternal(notify: true); return }
            send(data + newline, generation: generation)
            receive(generation)
        case .failed, .cancelled:
            disconnectInternal(notify: true)
        default: break
        }
    }

    private func receive(_ generation: Int) {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, complete, error in
            guard let self, generation == self.generation else { return }
            if let data { self.received.append(data); self.processLines(generation) }
            if error != nil || complete { self.disconnectInternal(notify: true) } else { self.receive(generation) }
        }
    }

    private func processLines(_ generation: Int) {
        while let newline = received.firstIndex(of: 0x0a) {
            let line = String(data: received.prefix(upTo: newline), encoding: .utf8) ?? ""
            received.removeSubrange(...newline)
            if !handshakeComplete {
                do {
                    let hello = try ClientProtocol.parseHello(line)
                    if hello.accepted { handshakeComplete = true; update(.connected(hello.version)) }
                    else {
                        update(.rejected(hello.message ?? "Another client is already connected. You cannot connect right now."))
                        self.generation += 1
                        connection?.cancel(); connection = nil
                    }
                } catch { protocolError(error) }
            } else {
                do {
                    let message = try ClientProtocol.parseServerMessage(line)
                    DispatchQueue.main.async { self.onMessage?(message) }
                }
                catch { protocolError(error) }
            }
            guard generation == self.generation else { return }
        }
    }

    private func send(_ data: Data, generation: Int? = nil) {
        queue.async {
            guard self.handshakeComplete || generation != nil, let connection = self.connection, generation == nil || generation == self.generation else { return }
            connection.send(content: data, completion: .contentProcessed { [weak self] error in if error != nil { self?.queue.async { self?.disconnectInternal(notify: true) } } })
        }
    }

    private func disconnectInternal(notify: Bool) {
        generation += 1
        connection?.cancel(); connection = nil; received.removeAll(); handshakeComplete = false
        let shouldReconnect = notify && !isRejected && autoReconnectEnabled()
        update(.disconnected)
        if shouldReconnect { queue.asyncAfter(deadline: .now() + 3) { [weak self] in guard let self, self.state == .disconnected else { return }; self.connect(address: self.lastAddress, port: self.lastPort) } }
    }

    private func update(_ state: ConnectionState) { self.state = state; DispatchQueue.main.async { self.onStateChanged?(state) } }
    private func protocolError(_ error: Error) { DispatchQueue.main.async { self.onProtocolError?(error.localizedDescription) } }
}
#endif