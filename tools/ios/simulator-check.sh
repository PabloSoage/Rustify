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

xcrun simctl install "$device" "$app"
xcrun simctl spawn "$device" log stream --level debug --predicate 'processImagePath CONTAINS "Rustify"' >"$out/app.log" 2>&1 &
logger=$!
trap 'kill "$logger" 2>/dev/null || true; xcrun simctl shutdown "$device" || true' EXIT

running() { xcrun simctl spawn "$device" launchctl list | grep -q "$bundle"; }

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
