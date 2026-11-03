#!/usr/bin/env bash
# Builds core_engine as a static library for iOS, with the C ABI (src/ffi.rs) and the audio engine.
#
# Needs a Mac with Xcode (ring, cpal's CoreAudio bindings and rquickjs all compile C against the iOS
# SDK). Output, one library per Kotlin/Native target:
#
#   core_engine/target/ios/iosArm64/libcore_engine.a            (devices)
#   core_engine/target/ios/iosSimulatorArm64/libcore_engine.a   (simulator on Apple Silicon)
#
# shared/build.gradle.kts links these into the iOS framework through the `rustify` cinterop.
# Not run by the Android build, and nothing here changes what the APK contains.
set -euo pipefail

cd "$(dirname "$0")/../../core_engine"

FEATURES="c-api,desktop-audio"
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
build aarch64-apple-ios-sim iosSimulatorArm64 &
wait

ls -l target/ios/*/libcore_engine.a
