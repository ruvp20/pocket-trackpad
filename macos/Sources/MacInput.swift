import AppKit
import ApplicationServices
import Carbon.HIToolbox

final class MacInput {
    private let source = CGEventSource(stateID: .hidSystemState)
    private var dragging = false
    private var clickTime: TimeInterval = -.infinity
    private var clickLocation = CGPoint.zero
    private var clickCount: Int64 = 0
    private var scrollRemainder = CGPoint.zero
    private var smartZoomApp: pid_t?
    private var launcherWork: DispatchWorkItem?

    func handle(_ record: Wire.Record) {
        guard AXIsProcessTrusted() else { return }
        switch record.command {
        case .move: move(x: units(record.x), y: units(record.y))
        case .scroll: scroll(x: units(record.x), y: units(record.y))
        case .click: click(right: false)
        case .rightClick: click(right: true)
        case .dragStart: startDrag()
        case .dragEnd: endDrag()
        case .action:
            if let action = Wire.Action(rawValue: record.x) { perform(action) }
        default: break
        }
    }

    func reset() {
        endDrag()
        clickTime = -.infinity
        scrollRemainder = .zero
        launcherWork?.cancel()
        launcherWork = nil
    }

    private func units(_ number: Int32) -> CGFloat {
        max(-250, min(250, CGFloat(number) / 1000))
    }

    private var cursor: CGPoint? { CGEvent(source: nil)?.location }

    private func mouse(_ type: CGEventType, at point: CGPoint,
                       button: CGMouseButton = .left, count: Int64 = 0) {
        guard let event = CGEvent(mouseEventSource: source, mouseType: type,
                                  mouseCursorPosition: point, mouseButton: button) else { return }
        event.setIntegerValueField(.mouseEventClickState, value: count)
        event.post(tap: .cghidEventTap)
    }

    private func move(x: CGFloat, y: CGFloat) {
        guard let current = cursor, x != 0 || y != 0 else { return }
        let target = CGPoint(x: current.x + x, y: current.y + y)
        let displays = NSScreen.screens.compactMap {
            $0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber
        }.map { CGDisplayBounds($0.uint32Value) }
        let position = displays.map { rect in
            CGPoint(x: min(max(target.x, rect.minX), rect.maxX - 1),
                    y: min(max(target.y, rect.minY), rect.maxY - 1))
        }.min { distance($0, target) < distance($1, target) } ?? target
        mouse(dragging ? .leftMouseDragged : .mouseMoved, at: position, count: dragging ? 1 : 0)
    }

    private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }

    private func click(right: Bool) {
        guard !dragging, let point = cursor else { return }
        if right {
            clickTime = -.infinity
            mouse(.rightMouseDown, at: point, button: .right, count: 1)
            mouse(.rightMouseUp, at: point, button: .right, count: 1)
            return
        }
        let now = ProcessInfo.processInfo.systemUptime
        let isRepeat = now - clickTime <= NSEvent.doubleClickInterval && distance(point, clickLocation) < 5
        clickCount = isRepeat ? min(clickCount + 1, 3) : 1
        clickTime = now
        clickLocation = point
        mouse(.leftMouseDown, at: point, count: clickCount)
        mouse(.leftMouseUp, at: point, count: clickCount)
    }

    private func startDrag() {
        guard !dragging, let point = cursor else { return }
        clickTime = -.infinity
        dragging = true
        mouse(.leftMouseDown, at: point, count: 1)
    }

    private func endDrag() {
        guard dragging else { return }
        dragging = false
        if let point = cursor { mouse(.leftMouseUp, at: point, count: 1) }
    }

    private func scroll(x: CGFloat, y: CGFloat) {
        scrollRemainder.x += x
        scrollRemainder.y += y
        let dx = Int32(scrollRemainder.x)
        let dy = Int32(scrollRemainder.y)
        scrollRemainder.x -= CGFloat(dx)
        scrollRemainder.y -= CGFloat(dy)
        guard dx != 0 || dy != 0 else { return }
        CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
                wheel1: dy, wheel2: dx, wheel3: 0)?.post(tap: .cghidEventTap)
    }

    private func key(_ code: Int, _ flags: CGEventFlags = []) {
        for isDown in [true, false] {
            let event = CGEvent(keyboardEventSource: source, virtualKey: CGKeyCode(code), keyDown: isDown)
            event?.flags = flags
            event?.post(tap: .cghidEventTap)
        }
    }

    private func perform(_ action: Wire.Action) {
        let front = NSWorkspace.shared.frontmostApplication
        switch action {
        case .zoomIn: key(kVK_ANSI_Equal, [.maskCommand, .maskShift])
        case .zoomOut: key(kVK_ANSI_Minus, .maskCommand)
        case .smartZoom:
            guard let pid = front?.processIdentifier else { return }
            if smartZoomApp == pid {
                key(kVK_ANSI_0, .maskCommand)
                smartZoomApp = nil
            } else {
                key(kVK_ANSI_Equal, [.maskCommand, .maskShift])
                smartZoomApp = pid
            }
        case .rotateLeft, .rotateRight:
            if front?.bundleIdentifier == "com.apple.Preview" {
                key(action == .rotateLeft ? kVK_ANSI_L : kVK_ANSI_R, .maskCommand)
            }
        case .back: key(kVK_ANSI_LeftBracket, .maskCommand)
        case .forward: key(kVK_ANSI_RightBracket, .maskCommand)
        case .missionControl: key(kVK_UpArrow, .maskControl)
        case .appWindows: key(kVK_DownArrow, .maskControl)
        case .spaceLeft: key(kVK_LeftArrow, .maskControl)
        case .spaceRight: key(kVK_RightArrow, .maskControl)
        case .lookup: key(kVK_ANSI_D, [.maskControl, .maskCommand])
        case .desktop: key(kVK_F11)
        case .notifications: key(kVK_ANSI_N, [.maskControl, .maskAlternate])
        case .apps:
            let launcher = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.apps.launcher")
                ?? NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.launchpad.launcher")
            if let launcher {
                NSWorkspace.shared.open(launcher)
            } else {
                key(kVK_Space, .maskCommand)
                launcherWork?.cancel()
                let work = DispatchWorkItem { [weak self] in self?.key(kVK_ANSI_1, .maskCommand) }
                launcherWork = work
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(220), execute: work)
            }
        }
    }
}
