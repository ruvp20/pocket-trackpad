import Foundation

let receiver = Receiver()
let expected: [Wire.Record] = [
    .init(.move, 18_000, -7_500), .init(.dragStart), .init(.move, 2_000, 3_000),
    .init(.action, Wire.Action.missionControl.rawValue)
]
var received = [Wire.Record]()
var disconnects = 0
var connections = 0

receiver.onState = { state in
    switch state {
    case .ready where connections == 0:
        print("READY")
        fflush(stdout)
    case .connected: connections += 1
    case .failed(let error): fatalError(error)
    default: break
    }
}
receiver.onInput = { record in
    precondition(received.count < expected.count && record == expected[received.count],
                 "Unexpected input: \(record)")
    received.append(record)
    if received.count == expected.count {
        DispatchQueue.main.async { receiver.stop() }
    }
}
receiver.onDisconnect = {
    disconnects += 1
    if disconnects == 2 {
        precondition(connections == 2 && received == expected, "Input or cleanup incomplete")
        print("Swift receiver: pairing, input and disconnect passed")
        exit(0)
    }
}
let secret = Data(0..<16)
try receiver.start(secret: secret, port: UInt16(CommandLine.arguments[1])!)
DispatchQueue.main.asyncAfter(deadline: .now() + 25) { fatalError("Receiver test timed out") }
RunLoop.main.run()
