# Rustify 🎵🦀

**Rustify** is a highly optimized, cross-platform Android music player that seamlessly merges Spotify's extensive metadata with YouTube's reliable audio playback. 

By leveraging a high-performance **Rust core** integrated via JNI, Rustify bypasses the limitations of traditional web-scraping wrappers, delivering a native, low-latency, and ad-free listening experience directly from your Spotify library.

---

## ✨ Key Features

- **Spotify to YouTube Sync**: Fetch your liked tracks, playlists, and albums directly from Spotify. Rustify intelligently matches them against YouTube's audio catalog in milliseconds.
- **Synchronized Lyrics**: Native integration with LRCLIB provides real-time, auto-scrolling lyrics perfectly timed to your currently playing track.
- **Background Downloads**: A robust, built-in `DownloadManager` allows you to download tracks, albums, and playlists securely for offline playback.
- **Advanced Audio Player**: Built on top of AndroidX Media3 (ExoPlayer), featuring intelligent streaming, gapless playback simulation, and robust foreground service support for uninterrupted listening.
- **Deep Linking & Intent Interception**: Seamlessly open Spotify URLs and custom `rustify://` links directly inside the app without needing the official Spotify client.
- **Local Music Support**: Scan and manage local music files on your device. Rustify integrates them with your Spotify library and automatically enriches them with Spotify metadata.
- **Aggressive Caching**: Intelligent persistent caching for audio stream URLs (via `stream_url_cache.json`), cover art, and user libraries ensures blazing-fast load times and offline resilience.
- **Multi-Language Support**: Fully localized and translated into English, Spanish, and Japanese.
- **Modern Jetpack Compose UI**: A beautiful, fluid, and responsive user interface built entirely with modern Android declarative UI paradigms.
- **Baseline Profiles**: Optimized startup times and rendering performance using Jetpack ProfileInstaller.

---

## 🏗️ Architecture & Tech Stack

Rustify's true power lies in its hybrid architecture, separating the heavy lifting from the UI thread:

### 1. The Core Engine (Rust 🦀)
Located in `core_engine/`, this is the brain of the application compiled to a native shared library (`libcore_engine.so`):
- **Spotify Scraper**: Dynamically scrapes and updates Spotify's GraphQL hashes, acting identically to a web browser to prevent API blocking.
- **YouTube Resolution**: Handles YouTube ID resolution and audio stream extraction using an embedded, dynamically updated `yt-dlp` bridge.
- **Matching Algorithm**: Uses normalized string comparison, ISRC lookups, and duration validation (±5s) to ensure the YouTube audio perfectly matches the Spotify track.
- **JNI Bridge**: Exposed safely to Kotlin via `lib.rs`.

### 2. The Android Client (Kotlin ☕)
Located in `app/`, this handles the user experience and Android OS integration:
- **UI Layer**: Built entirely with **Jetpack Compose**, relying heavily on `ViewModel` and `StateFlow` for reactive, unidirectional data flow.
- **Playback Service**: `AudioPlayerService.kt` acts as a Singleton process managing the `ExoPlayer` instance, playback queue, and cache logic.
- **Media Session**: `RustifyForegroundService.kt` binds the player to the Android OS media controls (lock screen, Bluetooth controls) via Media3 `MediaSessionService`.

---

## 🛠️ Building the Project

### Prerequisites
- **Android Studio** (Koala or newer recommended).
- **Android NDK** (Required to compile the JNI bindings).
- **Rust Toolchain**: Install via `rustup` (`rustup target add aarch64-linux-android x86_64-linux-android`).
- **cargo-ndk**: Install via `cargo install cargo-ndk`.

### Compilation
The Gradle build script (`build.gradle.kts`) is configured to automatically invoke `cargo-ndk` to compile the Rust core before packaging the APK.

1. **Build Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
2. **Build Release APK**:
   ```bash
   ./gradlew assembleRelease
   ```

### ADB Commands for Sideloading (AOT Optimization)
While the Release APK embeds the baseline profiles (`assets/dexopt/baseline.prof`) for automatic background optimization, you can force Immediate AOT compilation for testing by installing the APK alongside its Dex Metadata (`.dm`) file:

```bash
# For ARM64 Devices (Target Android 16 - Profile 0)
adb install-multiple app/release/app-arm64-v8a-release.apk app/release/baselineProfiles/0/app-arm64-v8a-release.dm

# For x86_64 Emulators
adb install-multiple app/release/app-x86_64-release.apk app/release/baselineProfiles/0/app-x86_64-release.dm
```

---

## 🤝 Contributing

Contributions are welcome! If you find a bug or want to improve the matching algorithm, feel free to open an Issue or submit a Pull Request.
Please ensure that any modifications to the Rust core compile successfully for both `aarch64` and `x86_64` targets.

---

## 🌟 Acknowledgements

- The app icon uses the logo of the [Lightkeepers](https://genshin-impact.fandom.com/wiki/Lightkeepers) from Genshin Impact.

---

## ⚖️ License

Copyright (C) 2026 Pablo Soage Rodas

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License v3.0** as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the `LICENSE` file for more details.

You should have received a copy of the GNU General Public License along with this program. If not, see https://www.gnu.org/licenses/.
