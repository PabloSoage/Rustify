# Rustify

Rustify is a highly optimized, cross-platform Android music player that seamlessly merges Spotify's metadata with YouTube's audio playback. It uses a high-performance Rust core via JNI for metadata fetching and proxying, and a modern Jetpack Compose UI for the frontend.

## 🚀 Features

- **Spotify to YouTube Sync**: Fetch your liked tracks, playlists, and albums from Spotify and play them directly via YouTube audio streams.
- **High-Performance Rust Core**: Network requests, Spotify API logic, and YouTube ID resolution are powered by a custom Rust engine, guaranteeing extremely low latency and minimal overhead.
- **Local Music Support**: Scan and manage local music files, seamlessly integrating them with your Spotify library. Automatically matches local tracks to Spotify metadata.
- **Modern Jetpack Compose UI**: A beautiful, fluid, and responsive UI built entirely with Jetpack Compose.
- **Advanced Audio Player**: Built with AndroidX Media3 (ExoPlayer), featuring intelligent caching, seamless gapless playback simulation, and robust foreground service support.
- **Baseline Profiles**: Optimized startup times and rendering performance using Jetpack ProfileInstaller.

## 🏗️ Architecture

Rustify is built on a hybrid architecture:

1.  **Core Engine (Rust)**:
    -   Located in `core_engine/`.
    -   Handles Spotify API communication (`reqwest`).
    -   Handles YouTube ID resolution and audio stream extraction using `yt-dlp` (via embedded python interpreter or native HTTP bridges).
    -   Exposed to Kotlin via JNI (`lib.rs`).
2.  **Android App (Kotlin)**:
    -   Located in `app/`.
    -   `MainActivity.kt`: Entry point, handles deep links and language settings.
    -   `AudioPlayerService.kt`: Singleton process that manages the `ExoPlayer` instance, playback queue, and cache logic.
    -   `RustifyForegroundService.kt`: `MediaSessionService` that binds the player to the Android OS media controls and notifications.
    -   **UI Layer**: Found in `ui/screens/` and `ui/components/`, heavily relying on `ViewModel` and `StateFlow`.
3.  **Bridge**:
    -   `NativeEngine.kt`: The Kotlin `object` that loads `libcore_engine.so` and declares the `external` JNI functions.

## 🛠️ Development & Building

### Prerequisites

-   **Android Studio** (Koala or newer recommended).
-   **Android NDK** (Required for JNI).
-   **Rust Toolchain**: Install via `rustup` (`rustup target add aarch64-linux-android x86_64-linux-android`).
-   **cargo-ndk**: Install via `cargo install cargo-ndk`.

### Building the Project

The Gradle build script (`build.gradle.kts`) is configured to automatically invoke `cargo-ndk` to build the Rust core before packaging the APK.

1.  **Build Debug APK**:
    ```bash
    ./gradlew assembleDebug
    ```
2.  **Build Release APK**:
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

## 🌐 Localization

Rustify supports multiple languages. Strings are managed in `app/src/main/res/values/strings.xml` (and `-es`, `-ja` suffixes). The app forces locale updates internally if the user overrides the system language from the Settings screen.

## 📝 License

This project is proprietary and confidential. All rights reserved.
