package io.github.ruvp20.pockettrackpad;

import android.app.AlertDialog;
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
    private static final int CANVAS = 0xff0e161a;
    private static final int CARD = 0xff17252b;
    private static final int TEXT = 0xffeef7f6;
    private static final int MUTED = 0xffa4b9bc;
    static final int PAD = 0xff17282f;
    static final int EDGE = 0xff365057;
    static final int GRID = 0xff31454a;
    static final int SUBTLE = 0xff6b8588;
    static final int ACCENT = 0xff99e8c9;
    static final int TOUCH_WASH = 0x4499e8c9;
    private static final int ERROR = 0xffffad9d;

    private SharedPreferences preferences;
    private PadConnection connection;
    private PadView pad;
    private Pairing pairing;
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
        showSetup("", false);
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
        if (connection != null) {
            resumePairing = ready;
            closeConnection();
            showSetup("", false);
        }
    }

    @Override protected void onDestroy() {
        closeConnection();
        if (pairing != null) Arrays.fill(pairing.key, (byte) 0);
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
        label(page, "Pair with your Mac", 18, ACCENT, true);
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
        label(identity, "Trackpad", 22, TEXT, true);
        TextView status = label(identity, "Connected to Mac", 13, ACCENT, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        Button disconnect = makeButton("Disconnect", false, () -> {
            closeConnection();
            resumePairing = false;
            if (pairing != null) Arrays.fill(pairing.key, (byte) 0);
            pairing = null;
            addressDraft = codeDraft = "";
            showSetup("", false);
        });
        header.addView(disconnect, new LinearLayout.LayoutParams(-2, -2));
        page.addView(header, fullWidth());
        space(page, 10);

        pad = new PadView(this, this::send);
        applyOptions();
        page.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        space(page, 7);

        LinearLayout footer = new LinearLayout(this);
        Button guide = makeButton("Gestures", false, this::showGestures);
        Button settings = makeButton("Settings", false, this::showSettings);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        first.setMarginEnd(dp(8));
        footer.addView(guide, first);
        footer.addView(settings, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(footer, fullWidth());
        setPage(page, 950);
    }

    private void send(int command, int x, int y) {
        if (connection != null) connection.send(command, x, y);
    }

    private void showGestures() {
        if (pad != null) pad.reset();
        new AlertDialog.Builder(this).setTitle("Trackpad gestures")
                .setMessage("One finger\nMove to point. Tap to click. Tap, then touch again and move to drag.\n\n"
                        + "Two fingers\nTap for right-click. Double-tap for smart zoom. Slide to scroll. "
                        + "Pinch or spread to zoom. Twist to rotate in Preview. "
                        + "Swipe quickly sideways for page history. Swipe left from the pad’s right edge for Notification Center.\n\n"
                        + "Three or four fingers\nSwipe up for Mission Control, down for App Exposé, "
                        + "and sideways to change Spaces. Tap with three fingers to look up text.\n\n"
                        + "Four fingers\nPinch for the app launcher or spread to show the desktop.\n\n"
                        + "Some actions depend on the active Mac app or its keyboard shortcuts. "
                        + "See the project’s setup guide for the exact mappings.")
                .setPositiveButton("Done", null).show();
    }

    private void showSettings() {
        if (pad != null) pad.reset();
        LinearLayout options = column();
        options.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(options);
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
        new AlertDialog.Builder(this).setTitle("Trackpad settings").setView(scroll)
                .setPositiveButton("Done", null).show();
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
            applyOptions();
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
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x330e161a : 0x4499e8c9),
                shape(primary ? ACCENT : CARD, 9, primary ? 0 : EDGE), null));
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
