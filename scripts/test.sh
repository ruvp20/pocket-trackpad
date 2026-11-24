#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
temp="$(mktemp -d)"
receiver_pid=
cleanup() {
  if [[ -n "$receiver_pid" ]]; then
    kill "$receiver_pid" 2>/dev/null || true
  fi
  rm -rf "$temp"
}
trap cleanup EXIT
java_dir="$root/android/app/src/main/java/io/github/ruvp20/pockettrackpad"
javac --release 17 -d "$temp" \
  "$java_dir/Wire.java" "$java_dir/HidReports.java" "$java_dir/PadConnection.java" "$java_dir/GestureTracker.java" "$root/tests/Smoke.java"
xcrun swiftc -swift-version 5 "$root/macos/Sources/Wire.swift" \
  "$root/macos/Sources/Receiver.swift" "$root/tests/main.swift" -o "$temp/receiver"

port="${POCKET_TEST_PORT:-58572}"
"$temp/receiver" "$port" > "$temp/receiver.log" 2>&1 &
receiver_pid=$!
for ((attempt = 0; attempt < 40; attempt++)); do
  if grep -qx READY "$temp/receiver.log"; then
    break
  fi
  kill -0 "$receiver_pid" 2>/dev/null || break
  sleep 0.1
done
if ! grep -qx READY "$temp/receiver.log"; then
  cat "$temp/receiver.log"
  exit 1
fi
if ! java -cp "$temp" io.github.ruvp20.pockettrackpad.Smoke "$port"; then
  cat "$temp/receiver.log"
  exit 1
fi
wait "$receiver_pid"
receiver_pid=
cat "$temp/receiver.log"
