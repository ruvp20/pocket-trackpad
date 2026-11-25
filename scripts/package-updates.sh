#!/usr/bin/env bash
# Package both builds and create the signed feed before publishing a stable release.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
mac_build="${1:-$root/macos/build}"
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
app="$mac_build/Pocket Trackpad.app"
version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist")"
destination="${2:-$mac_build/release-v$version}"
mkdir -p "$destination"
ditto -c -k --sequesterRsrc --keepParent "$app" "$destination/PocketTrackpad-macOS.zip"
"$mac_build/sparkle-2.10.1/bin/generate_appcast" --account io.github.ruvp20.pockettrackpad \
  --download-url-prefix "https://github.com/ruvp20/pocket-trackpad/releases/download/v$version/" "$destination"
cp "$apk" "$destination/PocketTrackpad-Android.apk"
