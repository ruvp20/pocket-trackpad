package io.github.ruvp20.pockettrackpad;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.lifecycle.Lifecycle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class AppUpdates {
    private static final String REPO = "ruvp20/pocket-trackpad";
    private final MainActivity activity;
    private boolean busy;
    private long lastCheck;
    private File pending;

    AppUpdates(MainActivity activity) { this.activity = activity; }

    private static InputStream open(String address) throws IOException {
        URLConnection conn = new URL(address).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "PocketTrackpad");
        return conn.getInputStream();
    }

    private boolean visible() {
        return activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED) && !activity.isFinishing();
    }

    void check(boolean manual) {
        if (pending != null && canInstall()) { install(pending); return; }
        long now = System.currentTimeMillis();
        if (busy || (!manual && now - lastCheck < 3600000)) return;
        busy = true;
        lastCheck = now;
        new Thread(() -> {
            try {
                JSONObject release;
                try (InputStream in = open("https://api.github.com/repos/" + REPO + "/releases/latest");
                     java.util.Scanner reader = new java.util.Scanner(in, StandardCharsets.UTF_8.name())) {
                    release = new JSONObject(reader.useDelimiter("\\A").next());
                }
                String tag = release.getString("tag_name");
                String installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
                if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+") || !newer(tag.substring(1), installed)) {
                    finish(manual ? "You have the latest version." : null); return;
                }
                JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (!"PocketTrackpad-Android.apk".equals(asset.getString("name"))) continue;
                    String digest = asset.getString("digest");
                    if (!digest.matches("sha256:[a-f0-9]{64}")) throw new IOException();
                    activity.runOnUiThread(() -> {
                        busy = false;
                        if (!visible()) return;
                        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Update to " + tag + "?")
                            .setPositiveButton("Download", (d,w) -> download(tag, digest)).setNegativeButton("Later", null).create();
                        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                        dialog.show();
                    });
                    return;
                }
                throw new IOException();
            } catch (Exception e) { finish(manual ? "Could not check GitHub for updates." : null); }
        }).start();
    }

    private static boolean newer(String offered, String installed) {
        String[] a = offered.split("\\."), b = installed.split("\\.");
        for (int i = 0; i < 3; i++) {
            int delta = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (delta != 0) return delta > 0;
        }
        return false;
    }

    private void download(String tag, String digest) {
        busy = true;
        new Thread(() -> {
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException();
                File apk = File.createTempFile("update-", ".apk", dir);
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                try (InputStream in = open("https://github.com/" + REPO + "/releases/download/" + tag + "/PocketTrackpad-Android.apk");
                     OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[16384];
                    int size;
                    while ((size = in.read(buf)) != -1) { out.write(buf, 0, size); hash.update(buf, 0, size); }
                }
                StringBuilder actual = new StringBuilder("sha256:");
                for (byte b : hash.digest()) actual.append(String.format(Locale.ROOT, "%02x", b));
                PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                if (!digest.contentEquals(actual) || info == null || !activity.getPackageName().equals(info.packageName)
                    || !tag.equals("v" + info.versionName)) { apk.delete(); throw new IOException(); }
                activity.runOnUiThread(() -> { busy = false; pending = apk; if (visible()) install(apk); });
            } catch (Exception e) { finish("Update download failed. Try again in Settings."); }
        }).start();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void install(File apk) {
        try {
            if (!canInstall()) {
                Toast.makeText(activity, "Allow Pocket Trackpad to install this update.", Toast.LENGTH_LONG).show();
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
            pending = null;
            activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException e) { finish("Open the release on GitHub to install the update."); }
    }

    private void finish(String message) {
        activity.runOnUiThread(() -> {
            busy = false;
            if (message != null && visible()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }
}
