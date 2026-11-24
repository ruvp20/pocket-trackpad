#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="${1:-$root/macos/build}"
mkdir -p "$build_dir"
build_dir="$(cd "$build_dir" && pwd)"
stage="$(mktemp -d "$build_dir/.stage.XXXXXX")"
trap 'rm -rf "$stage"' EXIT

app="$stage/Pocket Trackpad.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$root/macos/Resources/Info.plist" "$app/Contents/Info.plist"
cp "$root/macos/Resources/PocketTrackpad.icns" "$app/Contents/Resources/"
cp "$root/LICENSE" "$app/Contents/Resources/LICENSE.txt"

for arch in arm64 x86_64; do
  xcrun swiftc -swift-version 5 -O -whole-module-optimization \
    -target "$arch-apple-macos13.0" "$root"/macos/Sources/*.swift -o "$stage/$arch"
done

xcrun lipo -create "$stage/arm64" "$stage/x86_64" -output "$app/Contents/MacOS/PocketTrackpad"
codesign --force --sign - "$app"
destination="$build_dir/Pocket Trackpad.app"
if [[ -d "$destination" ]]; then
  mv "$destination" "$stage/previous.app"
fi
mv "$app" "$destination"
printf '%s\n' "$destination"
