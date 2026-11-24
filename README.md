# Pocket Trackpad

Use an Android phone as a wireless trackpad for your Mac. The Android app recognizes multi-touch gestures and sends them over Wi-Fi to a native macOS receiver. Your gestures stay on the local network.

**Requirements:** macOS 13 or later on Apple silicon or Intel, Android 7.0 or later, and a local IPv4 network that allows the devices to reach each other.

## Install and pair

1. Download **PocketTrackpad-macOS.zip** and **PocketTrackpad-Android.apk** from the [v0.1.0 release](https://github.com/ruvp20/pocket-trackpad/releases/tag/v0.1.0).
2. On your Mac, extract the ZIP, move **Pocket Trackpad.app** to **Applications**, and open it. The preview build is ad-hoc signed and has no Apple notarization. If macOS blocks the first launch, open **System Settings → Privacy & Security → Open Anyway** after the first launch attempt, or [build from source](#build-from-source). If macOS asks whether to accept incoming connections, allow the app. [Apple’s first-launch instructions](https://support.apple.com/en-us/102445)
3. In Pocket Trackpad on your Mac, click **Open Settings** and enable **Pocket Trackpad** under **Privacy & Security → Accessibility**. The receiver window shows when permission is active.
4. Open the APK on your Android phone. If prompted, permit the browser or file manager you used to install this APK. The APK is a debug-signed preview build.
5. Put the phone and Mac on the **same Wi-Fi**. The Mac can also use Ethernet connected to the same router. Open Pocket Trackpad on your phone, tap **Scan Mac QR code**, and point it at the Mac window. For manual pairing, enter the Mac address and current 32-character pairing code as displayed.
6. Slide one finger over the trackpad area to move the pointer. Open **Gestures** in the Android app for a guide. Keep the Mac receiver running and the Android app in the foreground while using it.

## Gestures

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
| Pinch with four fingers | Open Launchpad on supported macOS versions, or Spotlight’s Applications view on newer macOS versions. |
| Spread with four fingers | Show Desktop through **F11** if that shortcut is enabled. |

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

## Pairing and troubleshooting

- **Phone cannot reach Mac.** Check that the Mac window says **Ready to pair**. Guest networks may isolate devices. Disable VPN routing for this local connection if needed. If the Mac has several active adapters, use its same-network IPv4 address from **System Settings → Network** for manual pairing. The receiver uses TCP port **58432**.
- **Connection works, but the cursor does not move.** Check **Privacy & Security → Accessibility**. If you replaced or rebuilt the Mac app, remove its obsolete Accessibility entry, reopen the app, and grant access again.
- **Pairing rejected or the phone is busy.** Scan the QR currently shown on the Mac. One phone controls the receiver at a time. **New pairing code** disconnects the current phone and invalidates the old code.
- **Android cannot install the APK.** Android rejects updating an installed copy that has a different signing key. Uninstall the old copy first, or reinstall an APK signed with the same key.

Each receiver start generates a random 128-bit pairing secret. On connection, each side generates a fresh random challenge, and the two sides use HKDF-SHA256 to derive separate AES-GCM traffic keys. Both sides authenticate by opening the other side's first encrypted frame. The receiver accepts a single authenticated phone at a time and releases an active drag when the phone disconnects. The QR contains the pairing secret. Keep it on your Mac screen and use the receiver on a trusted local network.

## License

[MIT](LICENSE). The Android QR scanner uses [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) and [ZXing](https://github.com/zxing/zxing), distributed under Apache 2.0.
