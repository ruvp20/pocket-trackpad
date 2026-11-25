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

sparkle="$build_dir/sparkle-2.10.1"
archive="$sparkle/Sparkle-2.10.1.tar.xz"
mkdir -p "$sparkle"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 2 https://github.com/sparkle-project/Sparkle/releases/download/2.10.1/Sparkle-2.10.1.tar.xz -o "$stage/sparkle.tar.xz"
  mv "$stage/sparkle.tar.xz" "$archive"
fi
echo "1a856d2a9edb8e628de88121d7be04cf0e2167f8641650b78fca00b14244cf27  $archive" | shasum -a 256 -c -
tar xf "$archive" -C "$sparkle"
mkdir -p "$app/Contents/Frameworks"
ditto "$sparkle/Sparkle.framework" "$app/Contents/Frameworks/Sparkle.framework"
cp "$sparkle/LICENSE" "$app/Contents/Resources/Sparkle-LICENSE.txt"

for arch in arm64 x86_64; do
  xcrun swiftc -swift-version 5 -O -whole-module-optimization \
    -F "$sparkle" -framework Sparkle -Xlinker -rpath -Xlinker @executable_path/../Frameworks -target "$arch-apple-macos13.0" "$root"/macos/Sources/*.swift -o "$stage/$arch"
done

xcrun lipo -create "$stage/arm64" "$stage/x86_64" -output "$app/Contents/MacOS/PocketTrackpad"
codesign --force --sign - "$app"
destination="$build_dir/Pocket Trackpad.app"
if [[ -d "$destination" ]]; then
  mv "$destination" "$stage/previous.app"
fi
mv "$app" "$destination"
printf '%s\n' "$destination"
