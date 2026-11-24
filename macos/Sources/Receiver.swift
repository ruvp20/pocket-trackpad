import Foundation
import Network

final class Receiver {
    enum State {
        case stopped, starting, ready, connected, failed(String)
    }

    var onState: (State) -> Void = { _ in }
    var onInput: (Wire.Record) -> Void = { _ in }
    var onDisconnect: () -> Void = {}
    private var listener: NWListener?
    private var peers: [ObjectIdentifier: Peer] = [:]
    private var active: Peer?
    private var inactivity: Timer?

    func start(secret: Data, port: UInt16 = Wire.port) throws {
        stop()
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else { throw Wire.Failure.malformed }
        let listener = try NWListener(using: parameters, on: endpointPort)
        self.listener = listener
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self, let listener, self.listener === listener else { return }
            switch state {
            case .ready: self.onState(.ready)
            case .waiting(let error), .failed(let error):
                self.stop()
                self.onState(.failed(error.localizedDescription))
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self, weak listener] connection in
            guard let self, let listener, self.listener === listener else {
                connection.cancel()
                return
            }
            self.accept(connection, secret: secret)
        }
        onState(.starting)
        listener.start(queue: .main)
        inactivity = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.expirePeers()
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        inactivity?.invalidate()
        inactivity = nil
        if active != nil { onDisconnect() }
        active = nil
        for peer in Array(peers.values) { peer.cancel() }
        peers.removeAll()
        onState(.stopped)
    }

    private func accept(_ connection: NWConnection, secret: Data) {
        guard peers.count < 8 else { connection.cancel(); return }
        let peer = Peer(connection)
        peers[ObjectIdentifier(peer)] = peer
        peer.onClose = { [weak self, weak peer] in
            guard let self, let peer else { return }
            self.peers.removeValue(forKey: ObjectIdentifier(peer))
            if self.active === peer {
                self.active = nil
                self.onDisconnect()
                if self.listener != nil { self.onState(.ready) }
            }
        }
        peer.onAuthenticate = { [weak self, weak peer] in
            guard let self, let peer, self.active == nil else { return false }
            self.active = peer
            self.onState(.connected)
            return true
        }
        peer.onInput = { [weak self, weak peer] record in
            guard let self, let peer, self.active === peer else { return }
            self.onInput(record)
        }
        peer.start(secret: secret)
    }

    private func expirePeers() {
        let now = ProcessInfo.processInfo.systemUptime
        for peer in Array(peers.values) {
            let allowedIdle: TimeInterval = active === peer ? 16 : 5
            if now - peer.lastInput > allowedIdle { peer.cancel() }
        }
    }
}

private final class Peer {
    var onClose: () -> Void = {}
    var onAuthenticate: () -> Bool = { false }
    var onInput: (Wire.Record) -> Void = { _ in }
    private(set) var lastInput = ProcessInfo.processInfo.systemUptime

    private let connection: NWConnection
    private var fromPhone: Wire.Channel?
    private var toPhone: Wire.Channel?
    private var closed = false
    private var windowStart = ProcessInfo.processInfo.systemUptime
    private var recordsInWindow = 0

    init(_ connection: NWConnection) { self.connection = connection }

    func start(secret: Data) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: self.readHello(secret: secret)
            case .failed, .cancelled: self.cancel()
            default: break
            }
        }
        connection.start(queue: .main)
    }

    func cancel() {
        guard !closed else { return }
        closed = true
        connection.cancel()
        onClose()
    }

    private func readHello(secret: Data) {
        read(Wire.magic.count + Wire.challengeSize) { [self] hello in
            guard hello.starts(with: Wire.magic) else { throw Wire.Failure.malformed }
            let client = Data(hello.dropFirst(Wire.magic.count))
            let server = try Wire.randomBytes()
            self.fromPhone = Wire.Channel(key: Wire.key(secret: secret, client: client, server: server,
                                                       fromPhone: true))
            self.toPhone = Wire.Channel(key: Wire.key(secret: secret, client: client, server: server,
                                                     fromPhone: false))
            self.connection.send(content: Wire.magic + server,
                                 completion: .contentProcessed { [weak self] error in
                if error != nil { self?.cancel() }
            })
            self.read(Wire.sealedSize) { [self] proof in
                guard try self.fromPhone?.open(proof) == Wire.Record(.authenticate) else {
                    throw Wire.Failure.malformed
                }
                let accepted = self.onAuthenticate()
                let reply = try self.toPhone?.seal(Wire.Record(accepted ? .accepted : .busy))
                self.connection.send(content: reply, completion: .contentProcessed { [weak self] error in
                    if !accepted || error != nil { self?.cancel() }
                })
                if accepted {
                    self.lastInput = ProcessInfo.processInfo.systemUptime
                    self.readInput()
                }
            }
        }
    }

    private func readInput() {
        read(Wire.sealedSize) { [self] data in
            guard let record = try self.fromPhone?.open(data) else { throw Wire.Failure.malformed }
            let now = ProcessInfo.processInfo.systemUptime
            if now - self.windowStart >= 1 {
                self.windowStart = now
                self.recordsInWindow = 0
            }
            self.recordsInWindow += 1
            guard self.recordsInWindow <= 500 else { throw Wire.Failure.malformed }
            self.lastInput = now
            switch record.command {
            case .heartbeat:
                guard record == Wire.Record(.heartbeat),
                      let reply = try self.toPhone?.seal(Wire.Record(.heartbeat)) else {
                    throw Wire.Failure.malformed
                }
                self.connection.send(content: reply, completion: .contentProcessed { [weak self] error in
                    if error != nil { self?.cancel() }
                })
            case .authenticate, .accepted, .busy: throw Wire.Failure.malformed
            default: self.onInput(record)
            }
            self.readInput()
        }
    }

    private func read(_ count: Int, process: @escaping (Data) throws -> Void) {
        connection.receive(minimumIncompleteLength: count, maximumLength: count) { [weak self] data, _, _, error in
            guard let self, !self.closed else { return }
            guard error == nil, let data, data.count == count else { self.cancel(); return }
            do { try process(data) } catch { self.cancel() }
        }
    }
}
