package io.github.ruvp20.pockettrackpad;

import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.*;
import android.Manifest;
import android.content.Intent;
import android.annotation.SuppressLint;
import androidx.annotation.RequiresApi;
import androidx.activity.result.contract.ActivityResultContracts;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.Arrays;
import java.util.Locale;

public final class MainActivity extends ComponentActivity implements PadConnection.Listener {
    private static final int CANVAS = 0xff08090b;
    private static final int CARD = 0xff202124;
    private static final int TEXT = 0xfff6f7f9;
    private static final int MUTED = 0xffb4b8c0;
    static final int PAD = 0xff141518;
    static final int EDGE = 0xff35383d;
    static final int GRID = 0xff27343e;
    static final int SUBTLE = 0xff607887;
    static final int ACCENT = 0xff63c5ff;
    static final int TOUCH_WASH = 0x4463c5ff;
    private static final int ERROR = 0xffff888c;

    private SharedPreferences preferences;
    private PadConnection connection;
    private PadView pad;
    private Pairing pairing;
    private BluetoothPad bluetooth;
    private String bluetoothName = "", note = "";
    private boolean controls, discoverPrompt, bluetoothRegistered;
    private Dialog sheet;
    private final ActivityResultLauncher<String[]> nearby = registerForActivityResult(
        new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
            if (granted.containsValue(false)) notice("Allow Nearby devices in Android settings to use Bluetooth.");
            else beginBluetooth();
        });
    private final ActivityResultLauncher<Intent> enableRadio = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) beginBluetooth();
            else notice("Turn on Bluetooth to connect.");
        });
    private final ActivityResultLauncher<Intent> discover = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            discoverPrompt = false;
            if (result.getResultCode() <= 0) {
                closeConnection();
                notice("Visibility request cancelled. Tap Connect to try again.");
            }
        });
    private boolean ready, resumePairing;
    private String addressDraft = "", codeDraft = "";

    private final ActivityResultLauncher<ScanOptions> scan = registerForActivityResult(
            new ScanContract(), result -> {
                if (result.getContents() == null) return;
                try {
                    Pairing parsed = Pairing.fromQr(result.getContents());
                    addressDraft = parsed.host + ":" + parsed.port;
                    codeDraft = parsed.code;
                    connect(parsed);
                } catch (IllegalArgumentException error) {
                    showSetup("This QR code is invalid. Scan the one on your Mac.", true);
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("gestures", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        showPad();
    }

    @Override protected void onResume() {
        super.onResume();
        if (resumePairing && pairing != null) {
            resumePairing = false;
            connect(pairing);
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (bluetooth != null && !discoverPrompt) {
            closeConnection();
            showPad();
        }
        if (connection != null) {
            resumePairing = ready;
            closeConnection();
            showSetup("", false);
        }
    }

    @Override protected void onDestroy() {
        closeConnection();
        if (pairing != null) Arrays.fill(pairing.key, (byte) 0);
        dismissSheet();
        super.onDestroy();
    }

    private void connect(Pairing target) {
        closeConnection();
        if (pairing != null && pairing != target) Arrays.fill(pairing.key, (byte) 0);
        pairing = target;
        connection = new PadConnection(target.host, target.port, target.key, this);
        hideKeyboard();
        showSetup("Connecting to your Mac…", false);
        connection.start();
    }

    private void closeConnection() {
        if (pad != null) {
            pad.reset();
            pad = null;
        }
        if (bluetooth != null && Build.VERSION.SDK_INT >= 28) bluetooth.close();
        bluetooth = null;
        bluetoothRegistered = false;
        bluetoothName = "";
        PadConnection previous = connection;
        connection = null;
        ready = false;
        if (previous != null) previous.close();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override public void ready(PadConnection source) {
        runOnUiThread(() -> {
            if (source != connection || isFinishing()) return;
            ready = true;
            note = "";
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showPad();
        });
    }

    @Override public void ended(PadConnection source, String message) {
        runOnUiThread(() -> {
            if (source != connection || isFinishing()) return;
            closeConnection();
            showSetup(message, true);
        });
    }

    private void showSetup(String message, boolean error) {
        LinearLayout page = column();
        page.setPadding(dp(22), dp(20), dp(22), dp(22));
        label(page, "Pocket Trackpad", 32, TEXT, true);
        button(page, "Back to trackpad", false, () -> { closeConnection(); showPad(); });
        label(page, "Pair over Wi-Fi", 18, ACCENT, true);
        space(page, 8);
        label(page, "Connect both devices to the same Wi-Fi. Open Pocket Trackpad on your Mac.", 16, MUTED, false);
        space(page, 22);

        boolean connecting = connection != null;
        boolean hasCamera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        if (hasCamera && !connecting) {
            button(page, "Scan Mac QR code", true, () -> scan.launch(new ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the Pocket Trackpad code on your Mac")
                    .setOrientationLocked(false)
                    .setBeepEnabled(false)));
            space(page, 18);
        }
        if (!message.isEmpty()) {
            TextView feedback = label(page, message, 15, error ? ERROR : ACCENT, true);
            feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            space(page, 14);
        }
        if (connecting) {
            button(page, "Cancel", false, () -> {
                closeConnection();
                showSetup("", false);
            });
        } else {
            LinearLayout card = column();
            card.setPadding(dp(15), dp(12), dp(15), dp(15));
            card.setBackground(shape(CARD, 14, EDGE));
            page.addView(card, fullWidth());
            label(card, "Enter pairing details", 17, TEXT, true);
            space(card, 10);
            EditText address = field(card, "Mac address", "192.168.1.25:58432", addressDraft, false);
            EditText code = field(card, "Pairing code", "32 characters from your Mac", codeDraft, true);
            space(card, 6);
            button(card, "Connect", !hasCamera, () -> {
                addressDraft = address.getText().toString();
                codeDraft = code.getText().toString();
                try {
                    connect(Pairing.manual(addressDraft, codeDraft));
                } catch (IllegalArgumentException failure) {
                    if (!Pairing.validAddress(addressDraft)) {
                        address.setError("Enter the IPv4 address and optional port shown on your Mac.");
                        address.requestFocus();
                    } else {
                        code.setError("Enter the 32-character pairing code shown on your Mac.");
                        code.requestFocus();
                    }
                }
            });
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.setFocusableInTouchMode(true);
        scroll.addView(page);
        setPage(scroll, 530);
    }

    private void showPad() {
        LinearLayout page = column();
        page.setPadding(dp(15), dp(10), dp(15), dp(10));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = column();
        label(identity, "Pocket Trackpad", 22, TEXT, true);
        String state = ready ? (bluetooth != null ? "Bluetooth · " + bluetoothName : "Wi-Fi connected")
                : bluetooth != null ? "Bluetooth pairing" : "Disconnected";
        TextView status = label(identity, "●  " + state, 13, ready ? ACCENT : bluetooth != null ? MUTED : ERROR, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(makeButton(ready || bluetooth != null ? "Disconnect" : "Connect", false, () -> {
            if (!ready && bluetooth == null) { connections(); return; }
            closeConnection();
            resumePairing = false;
            if (pairing != null) Arrays.fill(pairing.key, (byte) 0);
            pairing = null;
            addressDraft = codeDraft = note = "";
            showPad();
        }));
        boolean focused = ready && preferences.getBoolean("focus", false);
        if (!focused) page.addView(header, fullWidth());
        space(page, 10);

        pad = new PadView(this, this::send);
        applyOptions();
        if (!note.isEmpty()) {
            TextView message = label(page, note, 14, MUTED, false);
            message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            space(page, 8);
        }
        if (!ready && bluetooth != null && Build.VERSION.SDK_INT >= 28 && bluetoothRegistered) {
            button(page, "Choose paired computer", false, () -> chooseMac(getSystemService(BluetoothManager.class).getAdapter()));
            space(page, 8);
        }
        page.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        space(page, 7);

        LinearLayout footer = new LinearLayout(this);
        if (focused) {
            rowButton(footer, "Exit focus", false, () -> {
                preferences.edit().putBoolean("focus", false).apply();
                showPad();
            });
        } else {
            rowButton(footer, controls ? "Hide controls" : "Controls", false, () -> { controls = !controls; showPad(); });
            rowButton(footer, "Settings", false, this::showSettings);
        }
        page.addView(footer, fullWidth());
        if (controls && !focused) {
            space(page, 6);
            LinearLayout row = new LinearLayout(this);
            rowButton(row, "‹ Back", false, () -> send(Wire.ACTION, Wire.BACK, 0)).setEnabled(ready);
            rowButton(row, "Forward ›", false, () -> send(Wire.ACTION, Wire.FORWARD, 0)).setEnabled(ready);
            page.addView(row, fullWidth());
            LinearLayout actions = new LinearLayout(this);
            rowButton(actions, "Gestures", false, this::showGestures);
            if (ready && bluetooth != null && Build.VERSION.SDK_INT >= 28) {
                rowButton(actions, "Keyboard", false, this::keys);
                LinearLayout audio = new LinearLayout(this);
                rowButton(audio, "Vol −", false, () -> bluetooth.media(2));
                rowButton(audio, "Mute", false, () -> bluetooth.media(4));
                rowButton(audio, "Vol +", false, () -> bluetooth.media(1));
                page.addView(audio, fullWidth());
            }
            page.addView(actions, fullWidth());
        }
        setPage(page, 950);
    }

    private void send(int command, int x, int y) {
        if (bluetooth != null && Build.VERSION.SDK_INT >= 28) bluetooth.send(command, x, y);
        else if (connection != null) connection.send(command, x, y);
    }

    private Button rowButton(LinearLayout row, String title, boolean primary, Runnable action) {
        Button button = makeButton(title, primary, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) params.setMarginStart(dp(8));
        row.addView(button, params);
        return button;
    }

    private void dismissSheet() {
        if (sheet != null) sheet.dismiss();
        sheet = null;
    }

    private void showSheet(String title, LinearLayout contents) {
        dismissSheet();
        Dialog dialog = new Dialog(this);
        sheet = dialog;
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(shape(CARD, 20, EDGE));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(TEXT);
        name.setTextSize(22);
        heading.addView(name, new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(makeButton("Done", false, dialog::dismiss));
        panel.addView(heading);
        space(panel, 12);
        panel.addView(contents);
        ScrollView scroller = new ScrollView(this);
        scroller.addView(panel);
        dialog.setContentView(scroller);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(-1, -2);
        dialog.show();
    }

    private void connections() {
        LinearLayout options = column();
        button(options, "Pair with Bluetooth", true, () -> { dismissSheet(); requestBluetooth(); });
        space(options, 8);
        label(options, "Use your phone as a Bluetooth mouse and keyboard for your Mac.", 15, MUTED, false);
        space(options, 20);
        button(options, "Wi-Fi · scan or enter code", false, () -> { dismissSheet(); showSetup("", false); });
        showSheet("Connect to a Mac", options);
    }

    private void notice(String message) {
        note = message;
        showPad();
    }

    private void requestBluetooth() {
        if (Build.VERSION.SDK_INT < 28) { notice("Bluetooth input needs Android 9 or later. Choose Wi-Fi to connect."); return; }
        if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
            nearby.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE});
            return;
        }
        beginBluetooth();
    }

    @SuppressLint("MissingPermission")
    private void beginBluetooth() {
        if (Build.VERSION.SDK_INT < 28) return;
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) { notice("Bluetooth is unavailable on this device. Choose Wi-Fi to connect."); return; }
        if (!adapter.isEnabled()) { enableRadio.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
        closeConnection();
        bluetooth = new BluetoothPad(this, adapter, new BluetoothPad.Events() {
            @Override public void registered() { bluetoothRegistered = true; chooseMac(adapter); }
            @Override public void incoming(BluetoothDevice device) {
                dismissSheet();
                Dialog confirm = new AlertDialog.Builder(MainActivity.this).setTitle("Control this computer?")
                        .setMessage(BluetoothPad.name(device))
                        .setPositiveButton("Connect", (d,w) -> { if (bluetooth != null) bluetooth.approve(); })
                        .setNegativeButton("Disconnect", (d,w) -> { closeConnection(); showPad(); })
                        .setCancelable(false).create();
                sheet = confirm;
                confirm.show();
            }
            @Override public void connected(String name) {
                dismissSheet();
                bluetoothName = name;
                note = "";
                ready = true;
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                showPad();
            }
            @Override public void ended(String reason) {
                dismissSheet();
                closeConnection();
                notice(reason);
            }
        });
        notice("Preparing Bluetooth…");
        bluetooth.start();
    }

    @RequiresApi(28)
    @SuppressLint("MissingPermission")
    private void chooseMac(BluetoothAdapter adapter) {
        LinearLayout devices = column();
        label(devices, "Select a paired Mac, or make this phone visible and select it in Mac Bluetooth settings.", 15, MUTED, false);
        space(devices, 12);
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            button(devices, BluetoothPad.name(device), false, () -> {
                dismissSheet();
                if (bluetooth != null) {
                    notice("Connecting to " + BluetoothPad.name(device) + "…");
                    bluetooth.connect(device);
                }
            });
        }
        button(devices, "Make phone visible", true, () -> {
            dismissSheet();
            discoverPrompt = true;
            notice("On your Mac, open System Settings → Bluetooth and connect to " + adapter.getName() + ".");
            discover.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120));
        });
        note = "Choose a computer to pair.";
        showPad();
        showSheet("Bluetooth pairing", devices);
    }

    private void keys() {
        if (Build.VERSION.SDK_INT < 28 || bluetooth == null) return;
        LinearLayout keys = column();
        EditText text = field(keys, "Text to type", "US keyboard layout", "", false);
        text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        button(keys, "Type on Mac", true, () -> {
            String value = text.getText().toString();
            for (int i = 0; i < value.length(); i++) {
                if (HidReports.stroke(value.charAt(i)) < 0) {
                    text.setError("Use US keyboard letters, numbers and punctuation.");
                    return;
                }
            }
            if (bluetooth != null) bluetooth.type(value);
            dismissSheet();
        });
        String[] names = {"Return", "Escape", "Space", "Tab", "Delete"};
        int[] usages = {40, 41, 44, 43, 42};
        for (int i = 0; i < names.length; i++) {
            final int usage = usages[i];
            button(keys, names[i], false, () -> { if (bluetooth != null) bluetooth.key(0, usage); });
        }
        showSheet("Mac keys", keys);
    }

    private void showGestures() {
        if (pad != null) pad.reset();
        new AlertDialog.Builder(this).setTitle("Trackpad gestures")
                .setMessage("One finger\nMove to point. Tap to click. Tap, then touch again and move to drag.\n\n"
                        + "Two fingers\nTap for right-click. Double-tap for smart zoom. Slide to scroll. "
                        + "Pinch or spread to zoom. With the Wi-Fi receiver, twist to rotate in Preview. "
                        + "Swipe quickly sideways for page history. Swipe left from the pad’s right edge for Notification Center.\n\n"
                        + "Three or four fingers\nSwipe up for Mission Control, down for App Exposé, "
                        + "and sideways to change Spaces. Tap with three fingers to look up text.\n\n"
                        + "Four fingers\nPinch for Spotlight over Bluetooth or the app launcher over Wi-Fi. Spread to show the desktop.\n\n"
                        + "Some actions depend on the active Mac app or its keyboard shortcuts. "
                        + "See the project’s setup guide for the exact mappings.")
                .setPositiveButton("Done", null).show();
    }

    private void showSettings() {
        if (pad != null) pad.reset();
        LinearLayout options = column();
        options.setPadding(dp(20), dp(8), dp(20), dp(8));

        option(options, "Focus mode when connected", "focus", false);
        option(options, "Natural scrolling", "natural", true);
        option(options, "Quick horizontal swipes change pages", "pageSwipes", true);
        option(options, "Three-finger drag", "threeDrag", false);
        label(options, "With three-finger drag on, use four fingers for desktop swipes.", 14, MUTED, false);
        space(options, 12);
        label(options, "Pointer speed", 16, TEXT, true);
        SeekBar speed = new SeekBar(this);
        speed.setContentDescription("Pointer speed");
        speed.setMax(18);
        speed.setProgress(preferences.getInt("speed", 7));
        speed.setMinimumHeight(dp(48));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                preferences.edit().putInt("speed", progress).apply();
                applyOptions();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        options.addView(speed, fullWidth());
        button(options, "Gesture guide", false, () -> { dismissSheet(); showGestures(); });
        showSheet("Trackpad settings", options);
    }

    private void option(LinearLayout parent, String label, String key, boolean defaultValue) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextSize(16);
        control.setTextColor(TEXT);
        control.setMinHeight(dp(50));
        control.setChecked(preferences.getBoolean(key, defaultValue));
        control.setOnCheckedChangeListener((view, value) -> {
            preferences.edit().putBoolean(key, value).apply();
            if (key.equals("focus")) showPad();
            else applyOptions();
        });
        parent.addView(control, fullWidth());
    }

    private void applyOptions() {
        if (pad == null) return;
        pad.configure(preferences.getBoolean("natural", true), preferences.getBoolean("threeDrag", false),
                preferences.getBoolean("pageSwipes", true),
                0.65f + preferences.getInt("speed", 7) * 0.1f);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
    }

    private void setPage(View content, int maxWidth) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(CANVAS);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        frame.addView(content, layout);
        frame.addOnLayoutChangeListener((view, left, top, right, bottom, a, b, c, d) -> {
            int width = Math.min(right - left - frame.getPaddingLeft() - frame.getPaddingRight(), dp(maxWidth));
            if (width > 0 && layout.width != width) {
                layout.width = width;
                content.setLayoutParams(layout);
            }
        });
        if (Build.VERSION.SDK_INT >= 30) {
            frame.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = windowInsets.getInsets(WindowInsets.Type.ime());
                frame.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(frame);
        frame.requestApplyInsets();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams fullWidth() { return new LinearLayout.LayoutParams(-1, -2); }

    private void space(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private TextView label(LinearLayout parent, String title, int size, int color, boolean strong) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(size);
        view.setTextColor(color);
        if (strong) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(view, fullWidth());
        return view;
    }

    private EditText field(LinearLayout parent, String title, String hint, String value, boolean password) {
        TextView caption = label(parent, title, 13, MUTED, true);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setText(value);
        input.setTextSize(16);
        input.setMinHeight(dp(48));
        input.setPadding(dp(11), dp(6), dp(11), dp(6));
        input.setBackground(shape(CANVAS, 9, EDGE));
        input.setOnFocusChangeListener((view, focused) ->
                input.setBackground(shape(CANVAS, 9, focused ? ACCENT : EDGE)));
        input.setSaveEnabled(false);
        LinearLayout.LayoutParams params = fullWidth();
        params.setMargins(0, dp(4), 0, dp(10));
        parent.addView(input, params);
        return input;
    }

    private void button(LinearLayout parent, String title, boolean primary, Runnable action) {
        parent.addView(makeButton(title, primary, action), fullWidth());
    }

    private Button makeButton(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(dp(48));
        button.setTextColor(primary ? CANVAS : TEXT);
        button.setPadding(dp(13), dp(5), dp(13), dp(5));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x330e161a : 0x4463c5ff),
                shape(primary ? ACCENT : CARD, 9, primary ? 0 : EDGE), null));
        button.setOnFocusChangeListener((v, focused) -> {
            if (focused) button.setBackground(shape(primary ? ACCENT : CARD, 12, ACCENT));
            else button.setBackground(new RippleDrawable(ColorStateList.valueOf(TOUCH_WASH),
                    shape(primary ? ACCENT : CARD, 12, primary ? 0 : EDGE), null));
        });
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }

    private static final class Pairing {
        final String host, code;
        final int port;
        final byte[] key;

        private Pairing(String host, int port, String rawCode) {
            if (!validHost(host) || port < 1 || port > 65535) throw new IllegalArgumentException();
            this.host = host;
            this.port = port;
            code = rawCode.trim().toLowerCase(Locale.ROOT);
            if (!code.matches("[0-9a-f]{32}")) throw new IllegalArgumentException();
            key = new byte[16];
            for (int i = 0; i < key.length; i++) key[i] = (byte) Integer.parseInt(code.substring(i * 2, i * 2 + 2), 16);
        }

        static Pairing manual(String address, String code) {
            String[] parts = address.trim().split(":", -1);
            if (parts.length > 2) throw new IllegalArgumentException();
            return new Pairing(parts[0], parts.length == 2 ? Integer.parseInt(parts[1]) : Wire.PORT, code);
        }

        static Pairing fromQr(String payload) {
            Uri uri = Uri.parse(payload);
            if (!"pockettrackpad".equals(uri.getScheme()) || !"pair".equals(uri.getHost())
                    || !"1".equals(uri.getQueryParameter("v"))) throw new IllegalArgumentException();
            String host = uri.getQueryParameter("host");
            String port = uri.getQueryParameter("port");
            String key = uri.getQueryParameter("key");
            if (host == null || port == null || key == null) throw new IllegalArgumentException();
            return new Pairing(host, Integer.parseInt(port), key);
        }

        static boolean validAddress(String address) {
            try {
                String[] parts = address.trim().split(":", -1);
                if (!validHost(parts[0]) || parts.length > 2) return false;
                int port = parts.length == 2 ? Integer.parseInt(parts[1]) : Wire.PORT;
                return port >= 1 && port <= 65535;
            } catch (NumberFormatException failure) {
                return false;
            }
        }

        private static boolean validHost(String host) {
            String[] pieces = host.split("\\.", -1);
            if (pieces.length != 4) return false;
            for (String piece : pieces) {
                if (!piece.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(piece) > 255) return false;
            }
            return true;
        }
    }
}
