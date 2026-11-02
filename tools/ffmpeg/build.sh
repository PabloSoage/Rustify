#!/usr/bin/env bash
# Builds the audio-only ffmpeg/ffprobe that yt-dlp runs for downloads (4.9).
#
# What it replaces: youtubedl-android's FFmpeg AAR, a Termux build with every codec and 184 shared
# libraries — 35.6 MB per ABI in the APK and 78 MB unpacked on the phone — of which a download uses
# a handful: pull the audio out of webm/mp4, optionally encode MP3, tag it, and convert the cover.
#
# This builds FFmpeg with only that, linked statically against its own libraries and LAME, so the
# two executables depend on nothing but the system's libc, libm, libdl and libz. They land in
# app/src/main/ffmpeg/<abi>/ as libffmpeg.so and libffprobe.so: the names youtubedl-android runs from
# nativeLibraryDir. Nothing else changes for yt-dlp.
#
# Licence: FFmpeg is built LGPL (no --enable-gpl) with LAME (LGPL). Rustify is GPLv3, which the LGPL
# allows; this script is published with the code so the binaries can be rebuilt from source.
#
# Run in Linux (WSL on the workstation) with the NDK unpacked:
#   NDK=~/android-ndk-r29 tools/ffmpeg/build.sh            # all ABIs
#   NDK=~/android-ndk-r29 tools/ffmpeg/build.sh arm64-v8a  # one
set -euo pipefail

FFMPEG_VERSION=8.0
LAME_VERSION=3.100
API=29   # the app's minSdk

NDK=${NDK:-$HOME/android-ndk-r29}
REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${WORK:-$HOME/rustify-ffmpeg-build}
JOBS=${JOBS:-$(nproc)}
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a armeabi-v7a x86_64)

mkdir -p "$WORK/src"
cd "$WORK/src"
# Downloaded to a temporary name and moved into place only when complete: a connection reset once
# left a truncated tarball that a later run would have trusted for being there.
fetch() { [ -f "$2" ] || { curl -sSfL -o "$2.part" "$1" && mv "$2.part" "$2"; }; }
fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg-$FFMPEG_VERSION.tar.xz"
fetch "https://downloads.sourceforge.net/project/lame/lame/$LAME_VERSION/lame-$LAME_VERSION.tar.gz" "lame-$LAME_VERSION.tar.gz"
# The hashes were taken from a tarball whose signature checked out against FFmpeg's release key
# (FCF986EA15E6E293A5644F10B4322F04D67658D8); LAME's is the one its release has always had.
sha256sum -c "$REPO/tools/ffmpeg/SHA256SUMS"

for ABI in "${ABIS[@]}"; do
  case $ABI in
    arm64-v8a)   TRIPLE=aarch64-linux-android;     CLANG=aarch64-linux-android$API;     ARCH=aarch64; CPU=armv8-a;  EXTRA=() ;;
    armeabi-v7a) TRIPLE=arm-linux-androideabi;     CLANG=armv7a-linux-androideabi$API;  ARCH=arm;     CPU=armv7-a;  EXTRA=(--enable-neon) ;;
    x86_64)      TRIPLE=x86_64-linux-android;      CLANG=x86_64-linux-android$API;      ARCH=x86_64;  CPU=x86-64;   EXTRA=() ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac
  CC=$TOOLCHAIN/bin/$CLANG-clang
  PREFIX=$WORK/out/$ABI
  BUILD=$WORK/build/$ABI
  rm -rf "$BUILD" "$PREFIX"
  mkdir -p "$BUILD" "$PREFIX"

  echo "=== $ABI: LAME $LAME_VERSION"
  tar -xzf "$WORK/src/lame-$LAME_VERSION.tar.gz" -C "$BUILD"
  (cd "$BUILD/lame-$LAME_VERSION" &&
    # lame_init_old is declared but not built; with a static library it only breaks the symbol list.
    sed -i '/lame_init_old/d' include/libmp3lame.sym &&
    ./configure --host=$TRIPLE --prefix="$PREFIX" --disable-shared --enable-static \
      --disable-frontend --disable-decoder --with-pic \
      CC="$CC" AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" CFLAGS="-O2" >/dev/null &&
    make -j"$JOBS" >/dev/null && make install >/dev/null)

  echo "=== $ABI: FFmpeg $FFMPEG_VERSION"
  tar -xJf "$WORK/src/ffmpeg-$FFMPEG_VERSION.tar.xz" -C "$BUILD"
  (cd "$BUILD/ffmpeg-$FFMPEG_VERSION" &&
    ./configure \
      --prefix="$PREFIX" \
      --target-os=android --arch=$ARCH --cpu=$CPU --enable-cross-compile \
      --cc="$CC" --cxx="$CC++" --ld="$CC" \
      --ar="$TOOLCHAIN/bin/llvm-ar" --nm="$TOOLCHAIN/bin/llvm-nm" \
      --ranlib="$TOOLCHAIN/bin/llvm-ranlib" --strip="$TOOLCHAIN/bin/llvm-strip" \
      --sysroot="$TOOLCHAIN/sysroot" --pkg-config=false \
      --extra-cflags="-I$PREFIX/include -fPIE" --extra-ldflags="-L$PREFIX/lib -pie" \
      --enable-pic --disable-shared --enable-static --enable-small \
      --disable-debug --disable-doc --disable-autodetect --disable-network \
      --disable-everything --disable-ffplay --disable-avdevice \
      --enable-ffmpeg --enable-ffprobe --enable-swresample --enable-swscale \
      --enable-zlib --enable-libmp3lame \
      --enable-protocol=file,pipe \
      --enable-demuxer=mov,matroska,mp3,ogg,aac,flac,wav,image2,image_webp_pipe,image_png_pipe,image_jpeg_pipe,ffmetadata,concat \
      --enable-muxer=mp3,mp4,ipod,ogg,opus,webm,matroska,flac,adts,wav,ffmetadata,image2 \
      --enable-decoder=opus,aac,aac_latm,vorbis,mp3,mp3float,flac,alac,pcm_s16le,pcm_f32le,webp,mjpeg,png \
      --enable-encoder=libmp3lame,flac,pcm_s16le,png,mjpeg \
      --enable-parser=opus,aac,vorbis,mpegaudio,flac,png,mjpeg,webp \
      --enable-bsf=aac_adtstoasc,extract_extradata \
      --enable-filter=aresample,aformat,anull,acopy,null,copy,format,scale,crop \
      "${EXTRA[@]}" >/dev/null &&
    make -j"$JOBS" >/dev/null && make install >/dev/null)

  DEST=$REPO/app/src/main/ffmpeg/$ABI
  mkdir -p "$DEST"
  install -m 644 "$PREFIX/bin/ffmpeg" "$DEST/libffmpeg.so"
  install -m 644 "$PREFIX/bin/ffprobe" "$DEST/libffprobe.so"
  "$TOOLCHAIN/bin/llvm-strip" "$DEST/libffmpeg.so" "$DEST/libffprobe.so"
  "$PREFIX/bin/ffmpeg" -buildconf >/dev/null 2>&1 || true   # cross binary: not runnable here
  echo "=== $ABI: $(du -h "$DEST/libffmpeg.so" | cut -f1) ffmpeg, $(du -h "$DEST/libffprobe.so" | cut -f1) ffprobe"
  "$TOOLCHAIN/bin/llvm-readelf" -d "$DEST/libffmpeg.so" | grep NEEDED
done
