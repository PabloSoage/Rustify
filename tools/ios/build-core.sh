#!/usr/bin/env bash
# Builds core_engine as a static library for iOS, with the C ABI (src/ffi.rs) and the audio engine.
#
# Needs a Mac with Xcode (ring, cpal's CoreAudio bindings and rquickjs all compile C against the iOS
# SDK). Output, one library per Kotlin/Native target:
#
#   core_engine/target/ios/iosArm64/libcore_engine.a            (devices)
#   core_engine/target/ios/iosSimulatorArm64/libcore_engine.a   (simulator on Apple Silicon)
#   core_engine/target/ios/iosX64/libcore_engine.a              (simulator on an Intel Mac; only
#                                                                built on one, or with IOS_X64=1)
#
# shared/build.gradle.kts links these into the iOS framework through the `rustify` cinterop.
# Not run by the Android build, and nothing here changes what the APK contains.
set -euo pipefail

cd "$(dirname "$0")/../../core_engine"

FEATURES="c-api,desktop-audio"

# rquickjs-sys runs bindgen for iOS (Cargo.toml). Two things its bindgen gets wrong on its own, seen
# in CI: it hands clang Rust's triple, and clang rejects `aarch64-apple-ios-sim` ("version 'sim' in
# target triple is invalid"); and it gives clang no SDK, so `stdio.h` is not found. bindgen appends
# these per-target arguments after its own, so the later --target wins.
SIM_SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
DEVICE_SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_apple_ios_sim="--target=arm64-apple-ios-simulator -isysroot $SIM_SDK"
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_apple_ios="--target=arm64-apple-ios -isysroot $DEVICE_SDK"
export BINDGEN_EXTRA_CLANG_ARGS_x86_64_apple_ios="--target=x86_64-apple-ios-simulator -isysroot $SIM_SDK"

# The simulator and device builds share nothing, so each gets its own target directory and the two
# can run side by side (the same lesson as the three Android ABIs).
build() {
  local rust_target="$1" kn_target="$2"
  rustup target add "$rust_target" >/dev/null
  CARGO_TARGET_DIR="target/ios-build/$kn_target" \
    cargo rustc --lib --release --target "$rust_target" --features "$FEATURES" --crate-type staticlib
  mkdir -p "target/ios/$kn_target"
  cp "target/ios-build/$kn_target/$rust_target/release/libcore_engine.a" "target/ios/$kn_target/"
}

build aarch64-apple-ios iosArm64 &
device=$!
build aarch64-apple-ios-sim iosSimulatorArm64 &
simulator=$!

# An Intel Mac's simulator runs x86_64 apps. Not built elsewhere: nobody else needs it, and it is a
# third full build of the core.
intel=""
if [ "$(uname -m)" = "x86_64" ] || [ "${IOS_X64:-0}" = "1" ]; then
  build x86_64-apple-ios iosX64 &
  intel=$!
fi

# `wait` with no arguments answers 0 whatever the builds did; waiting on each one is what makes a
# failed build fail the script (it did not, the first time CI ran this).
status=0
wait "$device" || { echo "iOS device build failed" >&2; status=1; }
wait "$simulator" || { echo "iOS simulator build failed" >&2; status=1; }
[ -z "$intel" ] || wait "$intel" || { echo "iOS Intel simulator build failed" >&2; status=1; }
[ "$status" -eq 0 ] || exit "$status"

ls -l target/ios/*/libcore_engine.a
