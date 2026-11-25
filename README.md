# Pocket Trackpad

Use your Android phone to point, click, scroll, drag and switch Spaces on a Mac. Pair directly over Bluetooth, or use the Mac receiver over Wi-Fi. The touch surface lights up under each fingertip. Open **Controls** for navigation and **Settings** to adjust pointer speed, gesture behavior and focus mode.

## Install and pair

Download the [Android APK](https://github.com/ruvp20/pocket-trackpad/releases/download/v0.3.0/PocketTrackpad-Android.apk) on your phone and open it. Allow installation from the browser or file manager if Android asks. This preview is signed with the same debug key as v0.1.0.

### Bluetooth

1. Use Android 9 or later on a phone that supports Android’s Bluetooth HID Device profile. Turn on Bluetooth on both devices and open **System Settings → Bluetooth** on the Mac.
2. On Android tap the **Disconnected** status pill, then **Pair with Bluetooth**. Allow **Nearby devices** if prompted.
3. If the Mac is already listed, tap its name. For first-time pairing, tap **Make phone visible**, allow the system prompt, and select your phone under **Nearby Devices** on the Mac. Confirm the system pairing prompts and approve the computer in Pocket Trackpad.
4. Look for **Bluetooth · [computer name]** on the phone, then slide one finger on the pad. If the devices paired but the pad is still waiting, tap **Choose paired computer** and select the Mac. Keep Pocket Trackpad in the foreground during use.

The Mac receives standard Bluetooth mouse, keyboard and volume reports directly. The Mac companion and Accessibility permission are only needed for Wi-Fi. In Bluetooth mode, the **Keyboard** button types US-layout text and offers Return, Escape, Tab, Space and Delete. **Controls** also includes volume and mute buttons.

### Wi-Fi

1. Use Android 7 or later and macOS 13 or later. Download the [Mac receiver ZIP](https://github.com/ruvp20/pocket-trackpad/releases/download/v0.3.0/PocketTrackpad-macOS.zip), extract it, move **Pocket Trackpad.app** to Applications and open it. The universal Mac preview is ad-hoc signed. [Apple’s first-launch instructions](https://support.apple.com/en-us/102445)
2. Put both devices on the same local network. On the Mac select **Wi-Fi**, open **Accessibility** settings from the receiver, and grant Pocket Trackpad access. Allow incoming connections if the firewall asks.
3. If multiple network addresses appear, select the address of the Wi-Fi or Ethernet interface on your phone’s network. The QR updates immediately.
4. On Android tap the **Disconnected** status pill, then **Wi-Fi · scan or enter code** and scan the Mac QR, or enter the displayed IPv4 address and pairing code. Keep the receiver open.

## Automatic updates

Install v0.3.0 or later once on each platform. Both apps check this repository’s latest stable GitHub release. Prereleases are skipped.

- Android checks on opening or returning to the app, at most once per hour per session. **Settings → Check for updates** checks immediately. Tap **Download** when a newer version is offered, allow installation from Pocket Trackpad if prompted, and confirm Android’s installer. The app verifies the release APK digest, package name and version. Android enforces signing-key continuity when upgrading.
- On macOS, keep the receiver in Applications and open it regularly. Sparkle checks periodically while it runs and verifies downloaded updates with the embedded Ed25519 public key. **Pocket Trackpad → Check for Updates…** checks immediately. Follow the updater if it asks to install or relaunch.

### Publishing future updates

Increment the Android version code and Mac bundle version, and give both apps the same new three-part version. Build Android with the original APK signing key and build the Mac receiver with the script below. Run `./scripts/package-updates.sh` on the release Mac. It uses the Sparkle key stored in the login Keychain under account `io.github.ruvp20.pockettrackpad`. Back up the signing keys securely to preserve upgrade continuity. Attach **PocketTrackpad-Android.apk**, **PocketTrackpad-macOS.zip**, and **appcast.xml** from the generated release directory to the matching GitHub tag (for example, `v0.3.0`). Publish those three assets together as the latest stable release.

## Gestures

One- and two-finger actions work in either orientation. Rotate the phone to landscape for every three-, four- or five-finger action. Three-finger drag is enabled by default. Turn it off in Settings to use three-finger swipes for Mission Control and Spaces. Bluetooth and Wi-Fi share the mappings below. Bluetooth scrolls in wheel steps and opens Spotlight for an inward four- or five-finger scoop. Preview-only rotation uses the Wi-Fi receiver.

| Android gesture | Mac action |
| --- | --- |
| Move, tap, or double-tap with one finger | Move pointer, click, or double-click |
| Tap, then touch and move with one finger | Click and drag. Lift to release. |
| Tap with two fingers | Secondary click. A short delay leaves time to recognize a double-tap. |
| Slide with two fingers | Vertical or horizontal pixel scrolling. Toggle natural scrolling in Android settings. |
| Pinch or spread two fingers | App zoom through **⌘−** or **⌘+**. Uses discrete steps. |
| Double-tap with two fingers | Smart-zoom approximation. Alternates app zoom in and reset through **⌘+** and **⌘0**. |
| Twist two fingers | Rotate the current image or page in **Preview**. |
| Swipe quickly left or right with two fingers | Forward or back through **⌘]** or **⌘[** in apps that support those shortcuts. Disable quick page swipes in Android settings for dedicated horizontal scrolling. |
| Swipe left with two fingers from the right edge of the pad | Notification Center if configured below. |
| Swipe up or down with three or four fingers | Mission Control or App Exposé through **Control–↑/↓**. |
| Swipe left or right with three or four fingers | Move to the next or previous Space through **Control–→/←**. |
| Tap with three fingers | Look Up through **Control–⌘–D** in supported apps. |
| Move with three fingers with **Three-finger drag** enabled | Click and drag. Use four fingers for desktop swipes in this mode. |
| Gather four or five fingertips inward | Open Launchpad on supported macOS versions, or Spotlight’s Applications view on newer macOS versions. |
| Spread four or five fingertips outward | Show Desktop through **F11** if that shortcut is enabled. |

For **Notification Center**, set its shortcut to **Control–Option–N** in **System Settings → Keyboard → Keyboard Shortcuts → Mission Control**. Enable **Show Desktop → F11** there if needed. Enable the **Move left/right a space** shortcuts in the same panel and create multiple Spaces before testing horizontal desktop swipes. With a customized keyboard layout or shortcut configuration, some mappings may differ. See [Apple's trackpad guide](https://support.apple.com/en-in/102482) and [Mac keyboard shortcuts](https://support.apple.com/en-ie/102650).

Pocket Trackpad sends mouse, wheel, and shortcut events. It has no Apple Force Click, hardware haptics, native magnification or rotation events, or interactive Space and page transitions. Shortcut compatibility depends on the focused Mac app.

## Build from source

On your Mac, install the Apple Command Line Tools if needed, then build the universal receiver.

```sh
xcode-select --install
git clone https://github.com/ruvp20/pocket-trackpad.git
cd pocket-trackpad
./scripts/build-macos.sh
open 'macos/build/Pocket Trackpad.app'
```

For Android, open the `android` folder in [Android Studio](https://developer.android.com/studio), install Android SDK Platform 36 and Build Tools 35.0.0, and use JDK 17 for Gradle. Select your connected Android phone and click **Run**, or run the Gradle wrapper from the command line with `JAVA_HOME` and `ANDROID_HOME` configured.

```sh
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run `./scripts/test.sh` from the repository root on macOS with a JDK available. It checks the gesture recognizer and connects the Android app's Java networking code to the Swift receiver over loopback.

## Screen capture on Android

Pocket Trackpad marks the trackpad, pairing screens, QR camera and app dialogs as secure windows. Android blocks their content from standard screenshots and screen recordings. A manufacturer’s three-finger screenshot shortcut can still intercept the touch before the app receives it. If dragging opens a screenshot prompt, search your phone settings for **three-finger screenshot** and turn it off or exclude Pocket Trackpad if your phone offers app exceptions. [Android secure-window documentation](https://developer.android.com/security/fraud-prevention/activities)

## Pairing and troubleshooting

- **Bluetooth pairing stalls.** Keep the phone app open. Check that Bluetooth is on and Nearby devices permission is granted. If another Bluetooth input app is running, close it and retry. The Android manufacturer must enable the HID Device profile. On unsupported phones select Wi-Fi.
- **Phone cannot reach Mac on Wi-Fi.** Check that the Mac window says **Ready to pair**. Guest networks may isolate devices. Disable VPN routing for this local connection if needed. If the Mac has several active adapters, use its same-network IPv4 address from **System Settings → Network** for manual pairing. The receiver uses TCP port **58432**.
- **Connection works, but the cursor does not move.** Check **Privacy & Security → Accessibility**. If you replaced or rebuilt the Mac app, remove its obsolete Accessibility entry, reopen the app, and grant access again.
- **Pairing rejected or the phone is busy.** Scan the QR currently shown on the Mac. One phone controls the receiver at a time. **New pairing code** disconnects the current phone and invalidates the old code.
- **Android cannot install the APK.** Android rejects updating an installed copy that has a different signing key. Uninstall the old copy first, or reinstall an APK signed with the same key.

In Wi-Fi mode, each receiver start generates a random 128-bit pairing secret. On connection, each side generates a fresh random challenge, and the two sides use HKDF-SHA256 to derive separate AES-GCM traffic keys. Both sides authenticate by opening the other side's first encrypted frame. The receiver accepts a single authenticated phone at a time and releases an active drag when the phone disconnects. The QR contains the pairing secret. Keep it on your Mac screen and use the receiver on a trusted local network.

## License

[MIT](LICENSE). The Android QR scanner uses [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) and [ZXing](https://github.com/zxing/zxing), distributed under Apache 2.0.
