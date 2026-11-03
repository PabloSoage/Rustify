#!/usr/bin/env bash
# Turns the desktop app's jpackage folder into a real Rustify-x86_64.AppImage (docs 25, R3 / T2.6).
#
# Compose's TargetFormat.AppImage is jpackage's "app-image": a folder with bin/Rustify and lib/, not an
# .AppImage file. This wraps that folder with appimagetool, which is what Spotube ships on Linux.
#
#   ./gradlew -Prustify.desktop=true :desktopApp:createDistributable
#   tools/desktop/make-appimage.sh            # → desktopApp/build/appimage/Rustify-<arch>.AppImage
#
# Needs appimagetool on PATH (https://github.com/AppImage/appimagetool/releases) and runs on Linux.
set -euo pipefail

cd "$(dirname "$0")/../.."

APP_DIR="desktopApp/build/compose/binaries/main/app/Rustify"
OUT_DIR="desktopApp/build/appimage"
ARCH="$(uname -m)"

[ -x "$APP_DIR/bin/Rustify" ] || {
  echo "No $APP_DIR/bin/Rustify: run ./gradlew -Prustify.desktop=true :desktopApp:createDistributable first" >&2
  exit 1
}
command -v appimagetool >/dev/null || { echo "appimagetool is not on PATH" >&2; exit 1; }

rm -rf "$OUT_DIR/Rustify.AppDir"
mkdir -p "$OUT_DIR/Rustify.AppDir/usr"
cp -a "$APP_DIR/." "$OUT_DIR/Rustify.AppDir/usr/"

cat > "$OUT_DIR/Rustify.AppDir/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/Rustify" "$@"
RUN
chmod +x "$OUT_DIR/Rustify.AppDir/AppRun"

cat > "$OUT_DIR/Rustify.AppDir/rustify.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Rustify
Comment=Music player over your Spotify library
Exec=Rustify
Icon=rustify
Categories=AudioVideo;Audio;Player;
DESKTOP

# The launcher icon jpackage put in the folder, if any; appimagetool insists on one.
ICON="$(find "$APP_DIR" -name '*.png' | head -n 1 || true)"
if [ -n "$ICON" ]; then
  cp "$ICON" "$OUT_DIR/Rustify.AppDir/rustify.png"
else
  # A 1×1 transparent PNG, so the tool runs; replace with the real icon (T2.6).
  printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82' > "$OUT_DIR/Rustify.AppDir/rustify.png"
fi

ARCH="$ARCH" appimagetool "$OUT_DIR/Rustify.AppDir" "$OUT_DIR/Rustify-$ARCH.AppImage"
ls -l "$OUT_DIR/Rustify-$ARCH.AppImage"
