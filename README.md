# Rustify 🎵🦀

**Rustify** is a highly optimized Android music player that merges Spotify's extensive metadata with a
pluggable set of audio backends.

By leveraging a high-performance **Rust core** integrated via JNI, Rustify delivers a native, low-latency,
and ad-free listening experience directly from your Spotify library.

---

## ✨ Key Features

### 🎧 Playback & Audio
- **Spotify to Audio Sync** — Fetch your liked tracks, playlists, and albums from Spotify. The Rust resolver matches them against a source in milliseconds using ISRC lookups, metadata matching, and an intelligent fallback chain.
- **Pluggable Audio Backends** — A configurable provider chain with per-source priority and enable/disable switches. yt-dlp is the default; Invidious and Deezer (with your own account credentials) are available as opt-in alternatives, each with its own diagnostics and playback test.
- **YouTube Music Integration** — Full YouTube Music browsing: search, albums, artists, playlists, and a local YTM library (favorites, saved albums/artists, custom playlists). Switch between the official YTM API and the general YouTube scraper for more results, including covers and unofficial content.
- **Local Music** — Scan your own folders and play local files, optionally preferring a local match over a streamed one.
- **Advanced Audio Player** — Built on AndroidX Media3 (ExoPlayer): intelligent streaming, a configurable disk cache, and robust foreground service support. Auto-retry with exponential backoff on network errors, and automatic re-resolution when stream URLs expire.
- **Background Downloads** — Download tracks, albums, and playlists for offline playback, plus a custom download screen for arbitrary sources.
- **Audio Alternatives** — Pick a different source for any track. Manual choices are persisted, take priority over auto-matching, and are manageable from a dedicated match editor with search and filtering.
- **Spotify Web Player** — Open Spotify's own web player inside the app, with network filtering driven by uBlock Origin's public filter lists. Optionally (and experimentally) it can act as the playback backend, driven by Rustify's own transport controls.

### 🚗 In the Car
- **Android Auto** — Browse your library and control playback from the car's head unit, with a media tree that stays in sync with the app.
- **Travel Playlist** — Compute a route and fill a playlist to match its estimated duration, using an open-source, keyless map stack.

### 📊 Discovery & Metrics
- **AI DJ** — Generates automixes from your listening history. Three modes: Heuristic (offline, rule-based), External API (OpenAI-compatible), and Local. Customizable base URL, model, and API key; defaults to a keyless endpoint.
- **Listening Metrics** — Track-level analytics (plays, time, streaks) aggregated by track, artist, and album with daily/weekly/monthly filters and a full history view. Import from Spotify's Extended and legacy `StreamingHistory*.json` exports with deduplication.
- **Song Radio** — Generate a playlist of similar tracks from any Spotify track, available from every detail screen.
- **New Releases & Full Discographies** — A paginated grid of Spotify's latest releases, and a per-artist view of every track in release order.

### 💾 Data & Sync
- **Google Drive Backup/Sync** — Bidirectional sync of matches, local playlists/favorites, YTM library, and metrics via the private `appDataFolder`, with a screen showing exactly what is covered. Merge = set-union + last-write-wins + metrics dedupe.
- **Local Playlists** — Create, rename, and manage playlists from local and YTM tracks, with mosaic cover art from the first tracks.
- **State Persistence** — Atomic temp-and-rename writes with debounced serialization prevent file corruption. Playback position, queue, repeat, and shuffle survive restarts.

### 🔗 Deep Linking & Sharing
- **Unified Link Parser** — Handles Spotify tracks, albums, playlists, and artists (including `/intl-XX/` routes) from `open.spotify.com` and clipboard paste. YouTube Music links are parsed and routed to the appropriate YTM screen.
- **Rustify Wrapper Links** — Share links on your own verified domain (`https://<host>/r/?s=<url>`) that open directly in Rustify via Android App Links, with a `rustify://` scheme fallback.
- **Dual Share** — "Share" always sends the plain link; "Share as Rustify Link" appears as a second button when the toggle is on, and attaches the cover art alongside the track details.

### 🔄 Updates
- **In-App Updater** — Checks GitHub Releases on demand or at startup, shows the changelog for every version you've missed, downloads the build matching your device, and lets you pick which installer handles it.

### 🎨 Interface & UX
- **Modern Jetpack Compose UI** — Built entirely with Material 3, with reactive state via `StateFlow`. Includes synchronized auto-scrolling lyrics (LRCLIB, cached with retry-on-failure), a Home menu, library management (save/follow/edit, bulk add-to-queue), pull-to-refresh across library sections, draggable queue reordering, and polished touches such as swipe-to-queue and adaptive marquee scrolling.
- **Spotify Canvas** — Short looping muted mp4 videos play full-screen behind the cover art and controls (Spotify-style); tap to hide the UI and watch at full color. Protobuf decode is done by hand in Rust with no extra dependencies.

### 🪵 Developer & Diagnostics
- **In-App Log Capture** — Real-time `logcat` stream with color-coded levels, tag/level filtering, autoscroll, and export. Crash-resilient (persisted to `filesDir`).
- **Baseline Profiles** — Optimized startup via Jetpack ProfileInstaller.
- **Internationalization** — Fully localized in English, Spanish, and Japanese.

---

## 🏗️ Architecture & Tech Stack

Rustify uses a hybrid architecture that separates the heavy lifting from the UI thread:

### 1. The Core Engine (Rust 🦀)
Located in `core_engine/` and compiled to a native shared library (`libcore_engine.so`):
- **Spotify Client** — Dynamically scrapes and updates GraphQL hashes, acting like a web browser to avoid API blocking; handles auth (`sp_dc` cookie), token refresh, and API retry with `Retry-After` support.
- **YouTube Music API** — RustyPipe 0.11 integration for `music_search_*`, album/artist/playlist detail, and radio.
- **YouTube Scraper** — General YouTube search (includes unofficial content), used for the "Find alternatives" dialog and as an optional search mode in the YTM Explore tab.
- **Matching Algorithm** — Normalized string comparison, ISRC lookups, and duration validation (±5s) to ensure the resolved audio matches the Spotify track.
- **Canvas Endpoint** — Hand-crafted protobuf encode/decode (varints) for the `canvaz-cache/v0/canvases` REST endpoint. No `prost` dependency.
- **Network Filtering** — An EasyList/uBlock-syntax filter engine (adblock-rust) backing the in-app web player.
- **JNI Bridge** — All Rust functionality exposed to Kotlin via `lib.rs`, over a stringly-typed JSON contract.

### 2. The Android Client (Kotlin ☕)
Located in `app/`, handling the user experience and Android OS integration:
- **UI Layer** — Built entirely with **Jetpack Compose** and Material 3, relying on `StateFlow` for reactive, unidirectional data flow. Navigation via a manual stack with `SaveableStateHolder`.
- **Playback Service** — `AudioPlayerService` manages the `ExoPlayer` instance, playback queue, URL cache with expiry, and retry logic.
- **Media Session** — `RustifyForegroundService` is a Media3 `MediaLibraryService` that binds to Android media controls (lock screen, Bluetooth) and exposes the browsable tree used by Android Auto.
- **Audio Chain** — Pluggable provider chain (`AudioSourceChain`) with configurable priority order and per-provider timeouts, shared by both playback and downloads.
- **Google Drive Sync** — Browser-based OAuth (PKCE) with `drive.appdata` scope + OkHttp REST v3 over the private `appDataFolder`.

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
