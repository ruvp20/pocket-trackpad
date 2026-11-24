import CryptoKit
import Foundation
import Security

enum Wire {
    static let magic = Data("PTP1".utf8)
    static let challengeSize = 16
    static let recordSize = 12
    static let sealedSize = recordSize + 16
    static let port: UInt16 = 58432

    enum Failure: Error { case randomBytes, malformed }

    enum Command: UInt8 {
        case authenticate = 1, accepted, busy, heartbeat
        case move = 10, scroll, click, rightClick, dragStart, dragEnd, action
    }

    enum Action: Int32 {
        case zoomIn = 1, zoomOut, smartZoom, rotateLeft, rotateRight
        case back, forward, missionControl, appWindows, spaceLeft, spaceRight
        case lookup, apps, desktop, notifications
    }

    struct Record: Equatable {
        let command: Command
        let x: Int32
        let y: Int32

        init(_ command: Command, _ x: Int32 = 0, _ y: Int32 = 0) {
            self.command = command
            self.x = x
            self.y = y
        }

        init(bytes: Data) throws {
            let b = Array(bytes)
            guard b.count == Wire.recordSize, let command = Command(rawValue: b[0]),
                  b[1] == 0, b[2] == 0, b[3] == 0 else { throw Failure.malformed }
            func integer(_ offset: Int) -> Int32 {
                let value = b[offset..<offset + 4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
                return Int32(bitPattern: value)
            }
            self.init(command, integer(4), integer(8))
        }

        var bytes: Data {
            var result = Data([command.rawValue, 0, 0, 0])
            for number in [x, y] {
                withUnsafeBytes(of: number.bigEndian) { result.append(contentsOf: $0) }
            }
            return result
        }
    }

    static func randomBytes() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: challengeSize)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw Failure.randomBytes
        }
        return Data(bytes)
    }

    static func key(secret: Data, client: Data, server: Data, fromPhone: Bool) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: secret),
            salt: client + server,
            info: Data((fromPhone ? "PTP1 phone-to-mac" : "PTP1 mac-to-phone").utf8),
            outputByteCount: 32
        )
    }

    struct Channel {
        let key: SymmetricKey
        private var counter: UInt64 = 0

        init(key: SymmetricKey) { self.key = key }

        private func nonce() throws -> AES.GCM.Nonce {
            guard counter < UInt64(UInt32.max) else { throw Failure.malformed }
            var bytes = Data(repeating: 0, count: 4)
            withUnsafeBytes(of: counter.bigEndian) { bytes.append(contentsOf: $0) }
            return try AES.GCM.Nonce(data: bytes)
        }

        mutating func seal(_ record: Record) throws -> Data {
            let box = try AES.GCM.seal(record.bytes, using: key, nonce: nonce())
            counter += 1
            return box.ciphertext + box.tag
        }

        mutating func open(_ data: Data) throws -> Record {
            guard data.count == Wire.sealedSize else { throw Failure.malformed }
            let box = try AES.GCM.SealedBox(nonce: nonce(), ciphertext: data.prefix(recordSize),
                                           tag: data.suffix(16))
            let plaintext = try AES.GCM.open(box, using: key)
            let record = try Record(bytes: plaintext)
            counter += 1
            return record
        }
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
