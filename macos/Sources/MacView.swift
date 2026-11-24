import AppKit
import ApplicationServices
import CoreImage.CIFilterBuiltins
import SwiftUI

final class MacModel: ObservableObject {
    @Published private(set) var state = Receiver.State.stopped
    @Published private(set) var trusted = AXIsProcessTrusted()
    @Published private(set) var address = ""
    @Published private(set) var pairingCode = ""
    @Published private(set) var qr: NSImage?

    private let receiver = Receiver()
    private let input = MacInput()
    private var refresh: Timer?
    private var secret: Data?

    init() {
        receiver.onState = { [weak self] in self?.state = $0 }
        receiver.onDisconnect = { [weak self] in self?.input.reset() }
        receiver.onInput = { [weak self] in self?.input.handle($0) }
        refreshEnvironment()
        refresh = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.refreshEnvironment()
        }
    }

    deinit { refresh?.invalidate() }

    var running: Bool {
        switch state {
        case .stopped, .failed: return false
        default: return true
        }
    }

    var connected: Bool {
        if case .connected = state { return true }
        return false
    }

    var status: String {
        switch state {
        case .stopped: return "Receiver paused"
        case .starting: return "Starting receiver"
        case .ready: return "Ready to pair"
        case .connected: return "Phone connected"
        case .failed: return "Receiver unavailable"
        }
    }

    var statusDetail: String {
        switch state {
        case .stopped: return "Start the receiver to pair a phone."
        case .starting: return "Opening the connection on this Mac."
        case .ready where address.isEmpty: return "Connect this Mac to Wi-Fi or Ethernet."
        case .ready: return "Scan the code from Pocket Trackpad on Android."
        case .connected: return "Your phone can control this Mac."
        case .failed(let message): return message
        }
    }

    func start() {
        do {
            let secret = try Wire.randomBytes()
            self.secret = secret
            pairingCode = secret.hex
            updateQR()
            try receiver.start(secret: secret)
        } catch {
            stop()
            state = .failed(error.localizedDescription)
        }
    }

    func stop() {
        receiver.stop()
        secret = nil
        pairingCode = ""
        qr = nil
    }

    func openAccessibility() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    private func refreshEnvironment() {
        let currentTrust = AXIsProcessTrusted()
        if trusted != currentTrust {
            if !currentTrust { input.reset() }
            trusted = currentTrust
        }
        let next = Self.localAddress()
        if address != next {
            address = next
            updateQR()
        }
    }

    private func updateQR() {
        guard secret != nil, !address.isEmpty else { qr = nil; return }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data("pockettrackpad://pair?v=1&host=\(address)&port=\(Wire.port)&key=\(pairingCode)".utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { qr = nil; return }
        let quietZone = output.composited(over: CIImage(color: .white))
            .cropped(to: output.extent.insetBy(dx: -4, dy: -4))
            .transformed(by: CGAffineTransform(scaleX: 6, y: 6))
        guard let bitmap = CIContext().createCGImage(quietZone, from: quietZone.extent) else {
            qr = nil
            return
        }
        qr = NSImage(cgImage: bitmap, size: quietZone.extent.size)
    }

    private static func localAddress() -> String {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0, let first else { return "" }
        defer { freeifaddrs(first) }
        var addresses = [(String, String)]()
        var next: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = next {
            let item = entry.pointee
            next = item.ifa_next
            guard let socket = item.ifa_addr, socket.pointee.sa_family == AF_INET,
                  item.ifa_flags & UInt32(IFF_UP) != 0,
                  item.ifa_flags & UInt32(IFF_LOOPBACK | IFF_POINTOPOINT) == 0 else { continue }
            let interface = String(cString: item.ifa_name)
            guard interface.hasPrefix("en") || interface.hasPrefix("bridge") else { continue }
            var bytes = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(socket, socklen_t(socket.pointee.sa_len), &bytes, socklen_t(bytes.count),
                           nil, 0, NI_NUMERICHOST) == 0 {
                let result = String(decoding: bytes.prefix(while: { $0 != 0 }).map(UInt8.init(bitPattern:)), as: UTF8.self)
                if !result.hasPrefix("169.254.") { addresses.append((interface, result)) }
            }
        }
        return addresses.sorted { $0.0.localizedStandardCompare($1.0) == .orderedAscending }.first?.1 ?? ""
    }
}

struct MacView: View {
    @ObservedObject var model: MacModel
    private let canvas = Color(red: 0.055, green: 0.086, blue: 0.102)
    private let surface = Color(red: 0.086, green: 0.129, blue: 0.149)
    private let edge = Color(red: 0.17, green: 0.23, blue: 0.25)
    private let primaryText = Color(red: 0.93, green: 0.97, blue: 0.97)
    private let secondaryText = Color(red: 0.65, green: 0.73, blue: 0.75)
    private let accent = Color(red: 0.60, green: 0.91, blue: 0.79)

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Pocket Trackpad").font(.system(size: 30, weight: .semibold)).tracking(-0.8)
                    Text("Use your Android phone to control this Mac.").foregroundStyle(secondaryText)
                }
                Spacer()
                Button(model.running ? "Pause receiver" : "Start receiver") {
                    if model.running { model.stop() } else { model.start() }
                }
                .controlSize(.large)
            }

            HStack(alignment: .top, spacing: 24) {
                pairingTile
                VStack(alignment: .leading, spacing: 14) {
                    Label(model.status, systemImage: model.connected ? "checkmark.circle.fill" : "circle.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(model.running ? accent : secondaryText)
                        .accessibilityAddTraits(.updatesFrequently)
                    Text(model.statusDetail).foregroundStyle(secondaryText).fixedSize(horizontal: false, vertical: true)
                    if model.running, !model.address.isEmpty {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("Mac address").font(.caption).foregroundStyle(secondaryText)
                            Text("\(model.address):\(Wire.port)").font(.system(.callout, design: .monospaced))
                                .textSelection(.enabled)
                        }
                        if !model.connected {
                            VStack(alignment: .leading, spacing: 5) {
                                Text("Manual pairing code").font(.caption).foregroundStyle(secondaryText)
                                if !model.pairingCode.isEmpty {
                                    Text(model.pairingCode).font(.system(.caption, design: .monospaced))
                                        .textSelection(.enabled)
                                }
                            }
                        }
                        Button("New pairing code") { model.start() }
                            .buttonStyle(.link)
                            .foregroundStyle(accent)
                            .help("Disconnect the current phone and generate a new code")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 3)
            }

            HStack(spacing: 10) {
                Image(systemName: model.trusted ? "checkmark.shield.fill" : "lock.shield")
                    .foregroundStyle(accent).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.trusted ? "Accessibility enabled" : "Allow Accessibility")
                        .font(.system(size: 13, weight: .semibold))
                    Text(model.trusted ? "This Mac can receive pointer and gesture input." :
                            "Required for pointer and gesture input.")
                        .font(.caption).foregroundStyle(secondaryText)
                }
                Spacer()
                if !model.trusted {
                    Button("Open Settings") { model.openAccessibility() }.controlSize(.large)
                }
            }
            .padding(14)
            .background(surface, in: RoundedRectangle(cornerRadius: 12))
        }
        .padding(26)
        .frame(width: 584, height: 414)
        .background(canvas)
        .foregroundStyle(primaryText)
        .preferredColorScheme(.dark)
    }

    private var pairingTile: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16).fill(surface)
            RoundedRectangle(cornerRadius: 16).strokeBorder(edge)
            if model.running, !model.connected, let qr = model.qr {
                Image(nsImage: qr).resizable().interpolation(.none)
                    .frame(width: 178, height: 178)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .accessibilityLabel("Android pairing QR code")
            } else {
                VStack(spacing: 10) {
                    Image(systemName: model.connected ? "iphone.radiowaves.left.and.right" : "qrcode")
                        .font(.system(size: 39, weight: .light)).foregroundStyle(accent)
                        .accessibilityHidden(true)
                    Text(model.connected ? "Paired" : "Pairing code").foregroundStyle(secondaryText)
                }
            }
        }
        .frame(width: 202, height: 202)
    }
}
