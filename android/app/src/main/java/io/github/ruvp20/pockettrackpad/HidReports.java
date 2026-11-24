package io.github.ruvp20.pockettrackpad;

/** USB HID usage tables, carried over the Bluetooth HID interrupt channel. */
final class HidReports {
    static final int MOUSE = 1, KEYBOARD = 2, MEDIA = 3;
    static final int CTRL = 1, SHIFT = 2, ALT = 4, CMD = 8;
    // Mouse: two buttons, signed 16-bit X/Y, vertical wheel, Consumer AC Pan.
    // Keyboard: eight modifier bits and one simultaneous non-modifier usage.
    static final byte[] DESCRIPTOR = bytes(
        0x05,1, 0x09,2, 0xa1,1, 0x85,MOUSE, 0x09,1, 0xa1,0,
        0x05,9, 0x19,1, 0x29,2, 0x15,0, 0x25,1, 0x75,1, 0x95,2, 0x81,2,
        0x75,6, 0x95,1, 0x81,3,
        0x05,1, 0x09,0x30, 0x09,0x31, 0x16,0x01,0x80, 0x26,0xff,0x7f, 0x75,16, 0x95,2, 0x81,6,
        0x09,0x38, 0x15,0x81, 0x25,0x7f, 0x75,8, 0x95,1, 0x81,6,
        0x05,0x0c, 0x0a,0x38,0x02, 0x81,6, 0xc0,0xc0,
        0x05,1, 0x09,6, 0xa1,1, 0x85,KEYBOARD, 0x05,7,
        0x19,0xe0, 0x29,0xe7, 0x15,0, 0x25,1, 0x75,1, 0x95,8, 0x81,2,
        0x19,0, 0x29,0x73, 0x15,0, 0x25,0x73, 0x75,8, 0x95,1, 0x81,0, 0xc0,
        0x05,0x0c, 0x09,1, 0xa1,1, 0x85,MEDIA, 0x15,0, 0x25,1,
        0x09,0xe9, 0x09,0xea, 0x09,0xe2, 0x09,0xcd, 0x75,1, 0x95,4, 0x81,2,
        0x75,4, 0x95,1, 0x81,3, 0xc0);

    interface Sink { void report(int id, byte[] payload); }

    private final Sink sink;
    private int buttons, moveX, moveY, scrollX, scrollY;
    private boolean zoomed;

    HidReports(Sink sink) { this.sink = sink; }

    static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    byte[] currentMouse() { return bytes(buttons, 0, 0, 0, 0, 0, 0); }

    void mouse(int x, int y, int vertical, int horizontal) {
        sink.report(MOUSE, bytes(buttons, x, x >> 8, y, y >> 8, vertical, horizontal));
    }

    void key(int modifiers, int usage) {
        sink.report(KEYBOARD, bytes(modifiers, usage));
        sink.report(KEYBOARD, bytes(0, 0));
    }

    void media(int bit) {
        sink.report(MEDIA, bytes(bit));
        sink.report(MEDIA, bytes(0));
    }

    // Key positions on the US keyboard layout. Each character is sent as a press/release pair.
    static int stroke(char c) {
        if (c == '#') return (SHIFT << 8) + 0x20;
        if (c >= 'a' && c <= 'z') return 4 + c - 'a';
        if (c >= 'A' && c <= 'Z') return (SHIFT << 8) + 4 + c - 'A';
        String plain = "1234567890\n\u001b\b\t -=[]\\#;'`,./";
        String shifted = "!@#$%^&*()\n\u001b\b\t _+{}|~:\"~<>?";
        int index = plain.indexOf(c);
        if (index >= 0) return 30 + index;
        index = shifted.lastIndexOf(c);
        return index >= 0 ? (SHIFT << 8) + 30 + index : -1;
    }

    void send(int command, int x, int y) {
        switch (command) {
            case Wire.MOVE:
                moveX += x;
                moveY += y;
                mouse(moveX / 1000, moveY / 1000, 0, 0);
                moveX %= 1000;
                moveY %= 1000;
                break;
            case Wire.SCROLL:
                scrollX += x;
                scrollY += y;
                int h = scrollX / 12000, v = scrollY / 12000;
                if (h != 0 || v != 0) mouse(0, 0, v, -h);
                scrollX %= 12000;
                scrollY %= 12000;
                break;
            case Wire.CLICK:
            case Wire.RIGHT_CLICK:
                buttons = command == Wire.CLICK ? 1 : 2;
                mouse(0, 0, 0, 0);
                buttons = 0;
                mouse(0, 0, 0, 0);
                break;
            case Wire.DRAG_START:
            case Wire.DRAG_END:
                buttons = command == Wire.DRAG_START ? 1 : 0;
                mouse(0, 0, 0, 0);
                break;
            case Wire.ACTION: action(x); break;
            default: break;
        }
    }

    private void action(int action) {
        switch (action) {
            case Wire.ZOOM_IN: key(CMD | SHIFT, 0x2e); break;
            case Wire.ZOOM_OUT: key(CMD, 0x2d); break;
            case Wire.SMART_ZOOM:
                key(zoomed ? CMD : CMD | SHIFT, zoomed ? 0x27 : 0x2e);
                zoomed = !zoomed;
                break;
            case Wire.BACK: key(CMD, 0x2f); break;
            case Wire.FORWARD: key(CMD, 0x30); break;
            case Wire.MISSION_CONTROL: key(CTRL, 0x52); break;
            case Wire.APP_WINDOWS: key(CTRL, 0x51); break;
            case Wire.SPACE_LEFT: key(CTRL, 0x50); break;
            case Wire.SPACE_RIGHT: key(CTRL, 0x4f); break;
            case Wire.LOOKUP: key(CTRL | CMD, 0x07); break;
            case Wire.APPS: key(CMD, 0x2c); break; // Spotlight
            case Wire.DESKTOP: key(0, 0x44); break;
            case Wire.NOTIFICATIONS: key(CTRL | ALT, 0x11); break;
            default: break; // App-specific rotation needs the Wi-Fi receiver.
        }
    }
}
