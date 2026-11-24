package io.github.ruvp20.pockettrackpad;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;

@SuppressLint("MissingPermission") // The activity requests Nearby devices before opening the profile.
@RequiresApi(28)
final class BluetoothPad implements BluetoothProfile.ServiceListener {
    interface Events {
        void registered();
        void incoming(BluetoothDevice device);
        void connected(String name);
        void ended(String reason);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothHidDevice profile;
    private BluetoothDevice target, linked;
    private boolean closed, approved;
    private final HidReports reports = new HidReports(this::report);
    private final Runnable timeout = () -> fail("Bluetooth timed out. Open Bluetooth settings on the Mac and try again.");

    BluetoothPad(Context context, BluetoothAdapter adapter, Events events) {
        this.context = context;
        this.adapter = adapter;
        this.events = events;
    }

    void start() {
        main.postDelayed(timeout, 12000);
        if (!adapter.getProfileProxy(context, this, BluetoothProfile.HID_DEVICE)) {
            fail("This phone does not expose the Bluetooth input-device profile. Use Wi-Fi pairing.");
        }
    }

    @Override public void onServiceConnected(int type, BluetoothProfile proxy) {
        if (closed) { adapter.closeProfileProxy(type, proxy); return; }
        profile = (BluetoothHidDevice) proxy;
        BluetoothHidDeviceAppSdpSettings settings = new BluetoothHidDeviceAppSdpSettings(
                "Pocket Trackpad", "Wireless pointer and gesture controls", "Pocket Trackpad",
                BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR);
        if (!profile.registerApp(settings, null, null, context.getMainExecutor(), callback)) {
            fail("Bluetooth input is busy or unavailable. Close other Bluetooth input apps and retry.");
        }
    }

    @Override public void onServiceDisconnected(int type) { fail("Bluetooth service disconnected. Reconnect to continue."); }

    void connect(BluetoothDevice device) {
        target = device;
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, 20000);
        if (profile == null || !profile.connect(device)) fail("The Mac declined the connection. Check its Bluetooth settings.");
    }

    void approve() {
        if (closed || linked == null || approved) return;
        approved = true;
        main.removeCallbacks(timeout);
        events.connected(name(linked));
    }

    static String name(BluetoothDevice device) {
        String name = device.getName();
        return name != null && !name.isEmpty() ? name : device.getAddress();
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean registered) {
            if (closed) return;
            if (!registered) { fail("Bluetooth input registration ended. Keep Pocket Trackpad open and reconnect."); return; }
            main.removeCallbacks(timeout);
            events.registered();
            if (plugged != null && profile.getConnectionState(plugged) == BluetoothProfile.STATE_CONNECTED) {
                onConnectionStateChanged(plugged, BluetoothProfile.STATE_CONNECTED);
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (closed) return;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if ((target != null && !target.equals(device)) || (linked != null && !linked.equals(device))) {
                    profile.disconnect(device);
                    return;
                }
                if (device.equals(linked)) return;
                linked = device;
                main.removeCallbacks(timeout);
                if (device.equals(target)) approve();
                else events.incoming(device);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && (device.equals(linked) || device.equals(target))) {
                fail("Mac disconnected. Tap Connect to pair again.");
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int size) {
            if (closed || profile == null) return;
            if (type != BluetoothHidDevice.REPORT_TYPE_INPUT || (id != HidReports.MOUSE && id != HidReports.KEYBOARD && id != HidReports.MEDIA)) {
                profile.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
                return;
            }
            profile.replyReport(device, type, id, id == HidReports.MOUSE ? reports.currentMouse() : new byte[id == HidReports.MEDIA ? 1 : 2]);
        }
    };

    void send(int command, int x, int y) { if (approved && !closed) reports.send(command, x, y); }
    void key(int modifier, int usage) { if (approved && !closed) reports.key(modifier, usage); }

    void media(int bit) { if (approved && !closed) reports.media(bit); }

    void type(String text) {
        for (int i = 0; i < text.length(); i++) {
            int stroke = HidReports.stroke(text.charAt(i));
            main.postDelayed(() -> key(stroke >> 8, stroke & 255), i * 24L);
        }
    }

    private void report(int id, byte[] data) {
        if (!closed && approved && !profile.sendReport(linked, id, data)) fail("Bluetooth input stopped. Reconnect to your Mac.");
    }

    private void fail(String message) {
        if (closed) return;
        close();
        events.ended(message);
    }

    void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (profile != null) {
            try {
                if (linked != null) {
                    profile.sendReport(linked, HidReports.MOUSE, new byte[7]);
                    profile.sendReport(linked, HidReports.KEYBOARD, new byte[2]);
                    profile.disconnect(linked);
                }
                profile.unregisterApp();
            } finally {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, profile);
                profile = null;
            }
        }
    }
}
