#!/usr/bin/env bash
# Builds the app for the Apple Silicon simulator, runs it there and writes screenshots and its log to
# $1 (default: build/ios-check). Fails if the app does not stay up.
#
# Needs an Apple Silicon Mac: Compose Multiplatform publishes no iosX64, so an Intel Mac's simulator
# has nothing to run. Expects tools/ios/build-core.sh to have run. CI's `ios` job is where this runs.
set -euo pipefail

cd "$(dirname "$0")/../.."
out="$(pwd)/${1:-build/ios-check}"
mkdir -p "$out"

(
  cd iosApp
  xcodegen generate
  xcodebuild -project Rustify.xcodeproj -target Rustify -configuration Debug -sdk iphonesimulator \
    ARCHS=arm64 SYMROOT="$PWD/build" CODE_SIGNING_ALLOWED=NO build | tail -3
)
app="iosApp/build/Debug-iphonesimulator/Rustify.app"
bundle="com.varuna.rustify"

device="$(xcrun simctl list devices available | sed -nE 's/^ +iPhone[^(]*\(([0-9A-F-]+)\) .*/\1/p' | head -1)"
[ -n "$device" ] || { echo "no iPhone simulator here" >&2; exit 1; }
xcrun simctl boot "$device" || true
xcrun simctl bootstatus "$device" -b
trap 'xcrun simctl shutdown "$device" || true' EXIT
xcrun simctl location "$device" set 43.3709,-8.3959

xcrun simctl install "$device" "$app"
xcrun simctl spawn "$device" log stream --level info --predicate 'processImagePath CONTAINS "Rustify"' >"$out/app.log" 2>&1 &
logger=$!
trap 'kill "$logger" 2>/dev/null || true; xcrun simctl shutdown "$device" || true' EXIT

# Not `grep -q`: it leaves at the first match, launchctl dies writing to it, and pipefail reads that as
# "not running". The app "was gone" at random.
running() { xcrun simctl spawn "$device" launchctl list | grep "$bundle" >/dev/null; }

xcrun simctl launch "$device" "$bundle"
shot() {
  sleep "$2"
  xcrun simctl io "$device" screenshot "$out/$1.png"
  running || { echo "the app is gone after $1" >&2; exit 1; }
}
# The first frame, then Home with what it loaded.
shot 1-launch 8
shot 2-home 25
# A link, as the system hands one: the same path as on the phone and the desktop.
xcrun simctl openurl "$device" "rustify://track/4uLU6hMCjMI75M1A2tKUQC"
shot 3-link 15
echo "the app stayed up; screenshots in $out"
xcrun simctl terminate "$device" "$bundle"

# A second of tone in the app's Documents, for the walkthrough to find in local music. The name has an
# accent and a ñ on purpose; so does the folder's path on people's phones.
data="$(xcrun simctl get_app_container "$device" "$bundle" data)"
tone="$(mktemp -d)"
python3 - "$tone/tone.wav" <<'PY'
import math, struct, sys, wave
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(44100)
    w.writeframes(b"".join(struct.pack("<h", int(8000 * math.sin(i * 0.0626))) for i in range(44100 * 120)))
PY
mkdir -p "$data/Documents"
afconvert -d aac -f m4af "$tone/tone.wav" "$data/Documents/Walkthrough Ñandú.m4a"

# The network the walkthrough plays and downloads over; the simulator shares the Mac's loopback.
python3 tools/ios/own-server.py 8765 "$out/own-server.log" &
server=$!
trap 'kill "$logger" "$server" 2>/dev/null || true; xcrun simctl shutdown "$device" || true' EXIT
sleep 3

xcrun simctl location "$device" set 43.3709,-8.3959

# The app used, not only looked at (iosApp/UITests).
(
  cd iosApp
  TEST_RUNNER_CHECK_DIR="$out" xcodebuild test -project Rustify.xcodeproj -scheme Rustify \
    -destination "platform=iOS Simulator,id=$device" -derivedDataPath build/test \
    -resultBundlePath "$out/walkthrough.xcresult" ARCHS=arm64 CODE_SIGNING_ALLOWED=NO | tail -40
)

# What the screen cannot tell: that the audio came over the network, further than its first bytes (the
# engine asks in ranges, one that starts past zero is it going on reading), and that a file came of it.
grep -E '^STREAM .* from [1-9]' "$out/own-server.log" >/dev/null \
  || { echo "the app never read past the start of the stream" >&2; exit 1; }
# Asked again: running the tests reinstalled the app, which moves its container.
data="$(xcrun simctl get_app_container "$device" "$bundle" data)"
ls -l "$data/Documents" | tee "$out/documents.txt"
[ "$(find "$data/Documents" -name '*Around the World*' -size +1M | wc -l)" -ge 1 ] \
  || { echo "the download left no file in Documents" >&2; exit 1; }
echo "walked through it; screenshots in $out"
