# Rustify 🎵🦀

**Rustify** is a highly optimized Android music player that merges Spotify's extensive metadata with YouTube's reliable audio playback.

By leveraging a high-performance **Rust core** integrated via JNI, Rustify delivers a native, low-latency, and ad-free listening experience directly from your Spotify library.

---

## ✨ Key Features

### 🎧 Playback & Audio
- **Spotify to YouTube Sync** — Fetch your liked tracks, playlists, and albums from Spotify. The Rust resolver matches them against YouTube in milliseconds using ISRC lookups, metadata matching, and an intelligent fallback chain (API → general YouTube scraper).
- **YouTube Music Integration** — Full YouTube Music browsing: search, albums, artists, playlists, and a local YTM library (favorites, saved albums/artists, custom playlists). Switch between the official YTM API and the general YouTube scraper for more results, including covers and unofficial content.
- **Advanced Audio Player** — Built on AndroidX Media3 (ExoPlayer): intelligent streaming, gapless playback simulation, and robust foreground service support. Auto-retry with exponential backoff on network errors, and automatic re-resolution when stream URLs expire.
- **Background Downloads** — Download tracks, albums, and playlists via yt-dlp for offline playback.
- **YouTube Alternatives** — Pick a different YouTube source for any track. Manual mappings are persisted, take priority over auto-match, and preserve playback position.

### 📊 Discovery & Metrics
- **AI DJ** — Generates automixes from your listening history. Three modes: Heuristic (offline, rule-based), External API (OpenAI-compatible), and Local (stub). Customizable base URL, model, and API key; defaults to a keyless endpoint.
- **Listening Metrics** — Track-level analytics (plays, time, streaks) aggregated by track, artist, and album with daily/weekly/monthly filters. Import from Spotify's Extended and legacy `StreamingHistory*.json` exports with deduplication.
- **Song Radio** — Generate a playlist of similar tracks from any Spotify track, available from every detail screen.
- **New Releases** — Paginated album grid of Spotify's latest releases.

### 💾 Data & Sync
- **Google Drive Backup/Sync** — Bidirectional sync of mappings, local playlists/favorites, YTM library, and metrics via the private `appDataFolder`. Merge = set-union + last-write-wins + metrics dedupe.
- **Local Playlists** — Create, rename, and manage playlists from local and YTM tracks, with mosaic cover art from the first tracks.
- **State Persistence** — Atomic temp-and-rename writes with debounced serialization prevent file corruption. Playback position, queue, repeat, and shuffle survive restarts.

### 🔗 Deep Linking & Sharing
- **Unified Link Parser** — Handles Spotify tracks, albums, playlists, and artists (including `/intl-XX/` routes) from `open.spotify.com` and clipboard paste. YouTube Music links are parsed and routed to the appropriate YTM screen.
- **Rustify Wrapper Links** — Share links on your own verified domain (`https://<host>/r/?s=<url>`) that open directly in Rustify via Android App Links, with a `rustify://` scheme fallback.
- **Dual Share** — "Share" always sends the plain link; "Share as Rustify Link" appears as a second button when the toggle is on.

### 🎨 Interface & UX
- **Modern Jetpack Compose UI** — Built entirely with Material 3, with reactive state via `StateFlow`. Includes synchronized auto-scrolling lyrics (LRCLIB, cached with retry-on-failure), a Home hamburger menu, library management (save/follow/edit, bulk add-to-queue), and polished touches such as swipe-to-queue and adaptive marquee scrolling.
- **Spotify Canvas** — Short looping muted mp4 videos play full-screen behind the cover art and controls (Spotify-style); tap to hide the UI and watch at full color. Protobuf decode is done by hand in Rust with no extra dependencies.

### 🪵 Developer & Diagnostics
- **In-App Log Capture** — Real-time `logcat` stream with color-coded levels, tag/level filtering, autoscroll, and export. Crash-resilient (persisted to `filesDir`).
- **Baseline Profiles** — Optimized startup via Jetpack ProfileInstaller.
- **Internationalization** — Fully localized in English and Spanish (Japanese partial).

---

## 🏗️ Architecture & Tech Stack

Rustify uses a hybrid architecture that separates the heavy lifting from the UI thread:

### 1. The Core Engine (Rust 🦀)
Located in `core_engine/` and compiled to a native shared library (`libcore_engine.so`):
- **Spotify Client** — Dynamically scrapes and updates GraphQL hashes, acting like a web browser to avoid API blocking; handles auth (`sp_dc` cookie), token refresh, and API retry with `Retry-After` support.
- **YouTube Music API** — RustyPipe 0.11.4 integration for `music_search_*`, album/artist/playlist detail, and radio.
- **YouTube Scraper** — General YouTube search (includes unofficial content), used for the "Find alternatives" dialog and as an optional search mode in the YTM Explore tab.
- **Matching Algorithm** — Normalized string comparison, ISRC lookups, and duration validation (±5s) to ensure the YouTube audio matches the Spotify track.
- **Canvas Endpoint** — Hand-crafted protobuf encode/decode (varints) for the `canvaz-cache/v0/canvases` REST endpoint. No `prost` dependency.
- **JNI Bridge** — All Rust functionality exposed to Kotlin via `lib.rs`.

### 2. The Android Client (Kotlin ☕)
Located in `app/`, handling the user experience and Android OS integration:
- **UI Layer** — Built entirely with **Jetpack Compose** and Material 3, relying on `StateFlow` for reactive, unidirectional data flow. Navigation via a manual stack with `SaveableStateHolder`.
- **Playback Service** — `AudioPlayerService` manages the `ExoPlayer` instance, playback queue, URL cache with expiry, and retry logic.
- **Media Session** — `RustifyForegroundService` binds to Android media controls (lock screen, Bluetooth) via Media3 `MediaSessionService`.
- **Audio Chain** — Pluggable provider chain (`AudioSourceChain`) with configurable priority order. Currently powered by yt-dlp; designed for future provider expansion.
- **Google Drive Sync** — `AuthorizationClient` with `drive.appdata` scope + OkHttp REST v3 over the private `appDataFolder`.

---

## 🛠️ Building the Project

### Prerequisites
- **Android Studio** (Koala or newer recommended).
- **Android NDK** (required to compile the JNI bindings).
- **Rust Toolchain**: Install via `rustup` (`rustup target add aarch64-linux-android x86_64-linux-android`).
- **cargo-ndk**: Install via `cargo install cargo-ndk`.

### Compilation
The Gradle build script (`build.gradle.kts`) automatically invokes `cargo-ndk` to compile the Rust core before packaging the APK.

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
