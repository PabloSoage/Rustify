# Rustify 🎵🦀

**Rustify** is a music player that uses your Spotify library as the catalogue and a backend of your
own choosing for the audio, over a native **Rust core**. It started on Android and now runs, as the
same app, on **Android, Windows, Linux, macOS, iOS and the web** (served by `rustify-server` from
your own computer or NAS).

It is a personal project, released as betas. Local music, YouTube Music and search work without any
account; your own Spotify session brings in your library and Home, plus whatever the audio backend
you pick asks for. It keeps everything it knows about you on your own devices, and Google Drive (your
own, private app folder) can keep them in sync.

> **Current:** app `5.6.4b` · core `5.6.4` · Android `minSdk 29` · `targetSdk 36` · iOS 16+
>
> The Rust core is built from the same commit as every app and carries the same version number.

---

## 📸 Screenshots

Taken signed out — everything shown works without a Spotify account. The first fourteen are from the
Android emulator; the rest from a POCO F8 Ultra (Snapdragon 8 Elite Gen 5), since the audio
lab's SME kernels and Gemini Nano need the real hardware.

|   |   |   |   |
|---|---|---|---|
| <img src="assets/screenshots/home.webp" width="180" alt="Home without an account"> | <img src="assets/screenshots/playlist.webp" width="180" alt="Playlist"> | <img src="assets/screenshots/album.webp" width="180" alt="Album"> | <img src="assets/screenshots/now-playing.webp" width="180" alt="Now playing"> |
| Home without an account | Playlist | Album | Now playing |
| <img src="assets/screenshots/queue.webp" width="180" alt="Queue — drag to reorder"> | <img src="assets/screenshots/lyrics-translate.webp" width="180" alt="Lyrics, translated on the phone"> | <img src="assets/screenshots/artist.webp" width="180" alt="Artist"> | <img src="assets/screenshots/artist-about.webp" width="180" alt="Artist · About"> |
| Queue — drag to reorder | Lyrics, translated on the phone | Artist | Artist · About |
| <img src="assets/screenshots/dj.webp" width="180" alt="DJ — moods in one tap"> | <img src="assets/screenshots/recap.webp" width="180" alt="Your year in music"> | <img src="assets/screenshots/travel-map.webp" width="180" alt="Travel playlist"> | <img src="assets/screenshots/travel-route.webp" width="180" alt="Route → playlist length"> |
| DJ — moods in one tap | Your year in music | Travel playlist | Route → playlist length |
| <img src="assets/screenshots/backends.webp" width="180" alt="Audio sources, fastest first"> | <img src="assets/screenshots/search.webp" width="180" alt="Search — recent and browse"> | <img src="assets/screenshots/find-in-playlist.webp" width="180" alt="Find in a playlist"> | <img src="assets/screenshots/audio-lab.webp" width="180" alt="Audio lab — spectrum, mel and key"> |
| Audio sources, fastest first | Search — recent and browse | Find in a playlist | Audio lab — spectrum, mel and key |
| <img src="assets/screenshots/audio-lab-bench.webp" width="180" alt="Audio lab — every ARM path, measured"> | <img src="assets/screenshots/nano-chat.webp" width="180" alt="Chat with Gemini Nano, on the phone"> | <img src="assets/screenshots/nano-chat-actions.webp" width="180" alt="The chat plays, queues and skips"> | <img src="assets/screenshots/nano-chat-search.webp" width="180" alt="The chat finds songs"> |
| Every ARM path, measured (SME 9× NEON) | Chat with Gemini Nano, on the phone | The chat plays, queues and skips | The chat finds songs |
| <img src="assets/screenshots/nano-chat-photo.webp" width="180" alt="A photo of a cover → the album, then play it"> | <img src="assets/screenshots/nano-panel.webp" width="180" alt="Gemini Nano for the DJ"> |
| A cover photo → the album → "play it" | Gemini Nano for the DJ |

### Other platforms

The same app, from the same shared interface (Compose Multiplatform), on iOS, macOS, Windows and
Linux, and in a browser through `rustify-server`.

| iOS | | |
|:---:|:---:|:---:|
| <img src="assets/screenshots/platforms/ios-search.webp" width="200" alt="iOS: search, playing"> | <img src="assets/screenshots/platforms/ios-dj.webp" width="200" alt="iOS: the DJ"> | <img src="assets/screenshots/platforms/ios-settings.webp" width="200" alt="iOS: settings"> |

| macOS | |
|:---:|:---:|
| <img src="assets/screenshots/platforms/macos-home.webp" width="380" alt="macOS: Home, in Spanish"> | <img src="assets/screenshots/platforms/macos-local-music.webp" width="380" alt="macOS: local music"> |

| Windows | Linux |
|:---:|:---:|
| <img src="assets/screenshots/platforms/windows-artist.webp" width="380" alt="Windows: an artist"> | <img src="assets/screenshots/platforms/linux-library.webp" width="380" alt="Linux: the library, playing"> |

| Web (`rustify-server`) |
|:---:|
| <img src="assets/screenshots/platforms/web-search.webp" width="560" alt="Web: search"> |

---

## 📦 Installing

Every release on [GitHub Releases](../../releases) carries all of these. They are betas, and the
desktop and iOS builds are **not signed** with a paid certificate, so the system asks once.

| Platform | File | Notes |
| --- | --- | --- |
| **Android** 10+ | `Rustify-<version>-android-arm64-v8a.apk` (most phones), `-armeabi-v7a` (older 32-bit), `-x86_64` (emulators) | Allow installing from your browser or file manager. Updates install over the previous version, and the app can update itself. |
| **Windows** 10/11 x64 | `-windows-x86_64.msi` or `-setup.exe`, or `-portable.zip` | SmartScreen: *More info → Run anyway*. The portable build keeps its data next to itself. |
| **Linux** x64 / arm64 | `.deb`, `.rpm`, `.AppImage` or `-portable.tar.gz` | The Spotify sign-in window uses WebKitGTK 4.1 (`libwebkit2gtk-4.1-0` on Debian/Ubuntu); without it, paste the cookie instead. |
| **macOS** Apple Silicon / Intel | `-macos-arm64.dmg` / `-macos-x86_64.dmg` | Gatekeeper: right-click the app → *Open* the first time (or `xattr -dr com.apple.quarantine /Applications/Rustify.app`). |
| **iOS** 16+ | `-ios-unsigned.ipa` | Install with [AltStore](https://altstore.io) or [SideStore](https://sidestore.io), which sign it with your own Apple ID. |
| **Web** | `rustify-server-<version>-linux-<arch>.tar.gz` (x86_64, aarch64, armv7) | See below. |

### The web interface (`rustify-server`)

`rustify-server` is the core behind an HTTP API, with the same app as a web page in front of it: for
a NAS, a home server, or any computer you want to reach from the browser of another device.

```bash
rustify-server                                   # http://127.0.0.1:8484, this machine only
rustify-server --bind 0.0.0.0:8484 --music-dir /srv/music --download-dir /srv/downloads
```

- **No token to copy.** On the machine the server runs on, the page opens as it is. From any other
  device, the first visit from your home network creates an account (a name and a password); after
  that each browser asks once and remembers it for a year.
- **Spotify in one click** when the server has a screen: it opens Spotify's own sign-in window, as
  the desktop app does. On a server without one, send the session from the phone or desktop app
  (*Settings → Integrations → Your Rustify server*) or paste the `sp_dc` cookie.
- **Google Drive** sync works in the browser too, with the same sign-in as the desktop.
- Beyond your own network, put it behind HTTPS (`--secure-cookies`). `server/rustify-server.service`
  is a systemd unit, and `server/Dockerfile` builds an image.

---

## ✨ Key Features

### 🖥️ One App, Six Platforms
- **The same app everywhere** — One interface, written once in Compose Multiplatform (`:sharedUi`),
  draws the phone, the tablet-style desktop window, the iPhone and the web page; one Rust core
  answers all of them. A feature a platform cannot have says so instead of vanishing.
- **Desktop** — The core's own audio engine (Symphonia + cpal) with the phone's effects, Spotify's
  sign-in in the system's own webview, local music, downloads, a tray icon, `rustify://` links, the
  DJ with voice, and a **local model** for the DJ, chat and translation (Ollama, LM Studio,
  llama-server, or its own with CUDA, Vulkan or Metal).
- **iOS** — Background audio, the lock screen and Control Center, the session in the Keychain, and
  folders you pick kept across launches.
- **Web** — The whole app in the browser from your own `rustify-server`: playback, video, lyrics,
  Jam, downloads to the server's folder, and Google Drive sync.
- **Sync between them** — Google Drive keeps your playlists, favourites, matches, history and
  *pick up where you left off* the same on every device. A device that was offline for days merges
  in; nothing is overwritten.

### 🎧 Playback & Audio
- **Spotify to audio** — Your liked tracks, playlists and albums come from Spotify; the Rust resolver
  matches each one against a source using ISRC lookups, folded-title comparison and a duration check,
  with a fallback chain behind it. YouTube audio is resolved by the core itself with one request — seconds
  instead of the half minute yt-dlp took — with yt-dlp kept as the fallback and for downloads.
- **Backends you can install** — Settings → Audio → **Add-ons**: paste a URL and it joins the same
  list as yt-dlp, Invidious and Deezer, reorderable and switchable, taking part in the same fallback
  chain. An add-on is told the title, the artists, the duration and the identifiers of a track and
  nothing else — never your session, never your library, never anything about your device — and there
  is a test that checks that rather than a comment promising it.
- **Pluggable backend chain** — Per-source priority, enable/disable switches and per-provider
  timeouts, shared by playback and downloads, each with its own diagnostics and playback test. Sources
  **race**: the next starts if the first has not answered in 4 s, and the first answer plays. The
  default order follows speed and robustness — the direct YouTube client, then yt-dlp (slower, but it
  updates itself), then add-ons; Invidious and Deezer last and off.
- **Sound** — Volume normalised from song to song with YouTube's loudness data or the file's own
  ReplayGain tags (automatic track/album mode), a fade between songs and on pause, an equaliser, speed
  and pitch, A-B repeat, skip silence, and a sleep timer that fades out — by minutes or by songs.
- **Per-headphone correction** — An AutoEq profile per output device, applied when that device
  connects.
- **Stereo tools** — Crossfeed for headphones, balance, mono for one earbud, and voice reduction for
  karaoke.
- **Bit-perfect USB** — On Android 14+, a USB DAC can take the file's own sample rate untouched,
  with nothing in the app in the way.
- **Your own server** — A Subsonic / Navidrome / Gonic server is one more audio source: a song it has
  plays from your file at its own quality, one it lacks goes to the next source.
- **Headphones that come back** — Playback pauses when the headphones or Bluetooth drop, and resumes
  if they reconnect within a minute (the car starting). A network drop no longer skips songs; a cut
  stream resumes where it stopped. Long tracks remember their place.
- **Battery** — On phones that support it, the audio chip (DSP) decodes the song and the processor
  sleeps between buffers of several minutes, instead of waking every few milliseconds. It steps aside
  by itself while the equaliser, the fade, the video or the audio lab need the processor.
- **Stored songs** — A song you have played once is kept on the device (512 MB, oldest evicted first)
  and served to the player as an ordinary address on `127.0.0.1`, so it starts instantly and seeks
  properly. The first play is unchanged: nothing waits for a download. Local files are never copied.
  One switch in Settings → Audio turns the whole mechanism off.
- **Deezer without a special case** — Deezer tracks arrive Blowfish-encrypted in 2048-byte stripes
  and are decrypted **as they arrive**, by the same local server, so what reaches the player is a
  plain URL with working range requests. Nothing is downloaded before the music starts.
- **YouTube Music** — Search, albums, artists, playlists and a local YTM library (favourites, saved
  albums/artists, custom playlists). Switch between the YTM API and the general YouTube scraper for
  covers and unofficial content.
- **Local music** — Scan your own folders and play local files, optionally preferring a local copy
  over a streamed one. The match runs in the core, over the same accent folding the library search
  uses, so a file tagged `Bjork - Joga` still matches *Björk – Jóga*.
- **Media3 player** — ExoPlayer with a configurable disk cache and a foreground service, auto-retry
  with exponential backoff, and automatic re-resolution when a stream URL expires.
- **Background downloads** — Tracks, albums and playlists for offline playback, plus a screen for
  downloading arbitrary sources.
- **Audio alternatives** — Pick a different source for any track. Manual choices persist, take
  priority over auto-matching, and have their own editor with search and filtering.
- **Play on another device** — Send what is playing to a speaker, TV or receiver on your wifi (DLNA).
  The queue stays on the phone; play, pause and seek go to the device, and the progress bar comes
  back from it. Off until you turn it on — see [§3](#3-casting-and-the-one-open-door) for why that
  matters.
- **Spotify web player** — Spotify's own web player inside the app, with network filtering driven by
  uBlock Origin's public filter lists. Experimentally, it can also act as the playback backend,
  driven by Rustify's own transport controls.

### 👥 Listening Together
- **Jam** — One phone hosts the queue; others join with a code and play it in step, wherever they
  are. Guests can skip and pick songs; pausing stays on your own phone. It goes through a public MQTT
  broker (or your own), **end-to-end encrypted**: the broker sees a hash and ciphertext, never the
  code or the queue.

### 🚗 In the Car
- **Android Auto** — Browse your library, search (no account needed), recently played, shuffle and
  repeat from the head unit, over a media tree that stays in sync with the app; albums and
  playlists show as a grid of covers.
- **Travel Playlist** — Compute a route and fill a playlist to match its estimated duration, on an
  open-source, keyless map stack.

### 📚 Library, Discovery & Metrics
- **Search that ignores accents and word order** — `bjork` finds Björk; `dark side moon` finds *The
  Dark Side of the Moon*. Results are ranked rather than merely filtered, and songs can be found by
  their album. One implementation, in the core, for every tab. Before you type, Search shows your
  recent searches and categories to browse (genres and moods, Spotify's own playlists first).
- **Find in a playlist** — A search box on every playlist, over the whole of it and not just what has
  loaded; playing a result plays the playlist from there.
- **Pick up where you left off** — A row on Home for the albums, playlists and radios you were partway
  through, showing `4/12 · Song Name` and resuming at the track and the minute you left. It keeps
  several at once; a song you skipped past does not qualify, and something you finished disappears.
- **What you have already heard** — Albums and playlists mark fully-listened tracks with a tick where
  the track number goes. It survives a playlist being edited.
- **AI DJ** — Automixes from your listening history in three modes: heuristic (offline,
  rule-based), an external OpenAI-compatible API, and **on-device** — Gemini Nano through Android's
  AICore, on the phone's NPU where it has one: offline, no key, nothing leaves the phone. One-tap
  moods (chill, focus, party, workout, happy, sad) and "more like" your most played artists.
  Settings shows the model's download, size and updates, and there is a **chat with Gemini Nano**
  that also drives the app: ask it to play, queue or find music, start a mix, pause or skip. Send it
  a photo of a cover and it names the album; "play it" plays it. The conversation never leaves the
  phone — only the search an action makes goes out, like any search in the app.
- **Listening metrics** — Every play kept, with no ceiling, in the core. Plays, time, streaks and
  per-artist records (bronze to platinum) by track, artist and album, against the previous period,
  with a full history. Imports Spotify's Extended and legacy `StreamingHistory*.json` exports, with
  deduplication.
- **Your year in music** — A Wrapped-style recap of any year from the history on your phone —
  minutes, top artist and song, what kind of listener you are — as cards you can share as an image.
- **Song Radio** — Spotify's own radio for any track, without an account.
- **Blocklist** — Songs and artists you never want: the radio never adds them and the queue skips
  them. Chosen by hand, they still play.
- **Scrobbling** — To ListenBrainz and to Last.fm (with your own API key), queued while offline.
- **Tempo** — The BPM of a local track, measured on the phone.
- **Local library** — A folder view, M3U import and export, artist tags split on separators you
  choose, lyrics read from the file itself, and saved queues you can switch between.
- **Credits & recommendations** — Who wrote, played and produced a song; ten songs that fit any
  playlist, under it. Both from the public catalogue, no account needed.
- **Artist pages** — Top tracks, discography, fans also like, and *About*: monthly listeners, world
  rank, biography, the cities that listen most and upcoming concerts.
- **New Releases & full discographies** — Latest releases grouped by when they actually came out
  (Spotify gives dates at three precisions, and treating them alike puts a whole year on 1 January),
  plus a per-artist view of every track in release order.

### 💾 Data & Sync
- **Your data, in a file you own** — Export everything Rustify keeps (chosen matches, what you were
  in the middle of, what you have listened to, installed add-ons) as one readable JSON file, and
  restore it here or on another phone. **It never contains a password or a session** — there is a
  list of what is deliberately left out, and a test that checks the file does not carry it.
- **Google Drive backup/sync** — Bidirectional sync of matches, local playlists/favourites, the YTM
  library, metrics and *pick up where you left off*, through the private `appDataFolder`, on every
  platform. Merge is set-union + last-write-wins by date + metrics dedupe; removing a place leaves a
  dated tombstone so it does not come back from another device.
- **Local playlists** — Create, rename and manage playlists of local and YTM tracks, with mosaic
  covers from the first tracks.
- **State persistence** — Versioned schema with migrations, atomic temp-and-rename writes, debounced
  serialization. Playback position, queue, repeat, shuffle **and where the queue came from** all
  survive a restart.

### 🔗 Deep Linking & Sharing
- **One link parser** — Spotify tracks, albums, playlists and artists (including `/intl-XX/` routes),
  YouTube Music links, and clipboard paste, all read by the same code in the core. A song shared from
  inside a playlist opens the song.
- **Rustify wrapper links** — Share links on your own verified domain (`https://<host>/r/?s=<url>`)
  that open directly in Rustify via Android App Links, with a `rustify://` fallback. They are
  unwrapped by the same code that wraps them, older forms included.
- **Dual share** — "Share" sends the plain link; "Share as Rustify Link" appears as a second button
  when the toggle is on, and attaches the cover art.

### 🔄 Updates
- **In-app updater** — Checks GitHub Releases on demand or at startup, shows the changelog for every
  version you missed, downloads the build matching your device (the APK for your ABI, or the desktop
  package for your system and architecture), and lets you pick the installer.

### 📱 Outside the App
- **Home-screen widget, Quick Settings tile and launcher shortcuts.**
- **Headphone button** — Two and three presses do what you choose.

### 🎨 Interface & UX
- **Jetpack Compose + Material 3** — Reactive state via `StateFlow`, navigation over a manual stack
  with `SaveableStateHolder`. Synchronized auto-scrolling lyrics (Spotify's own when signed in,
  then LRCLIB and others, reorderable) with **on-device translation** line by line (ML Kit, offline
  once the model is downloaded),
  library management with bulk actions, pull-to-refresh, draggable queue reordering, swipe-to-queue
  and adaptive marquee scrolling.
- **Spotify Canvas** — Short looping muted videos behind the cover art and controls; tap to hide the
  UI and watch full-screen. The protobuf is decoded by hand in Rust, with no extra dependency.

### 🪵 Developer & Diagnostics
- **Audio lab** — Settings → Advanced: waveform, spectrum, spectrogram (log or mel) and the **key**
  of what is playing. The FFT and the matrix product behind them have hand-written kernels for each
  ARM generation — NEON, SVE2, BF16 and **SME**, the matrix unit of armv9.2 — chosen at runtime by
  what the processor reports and by which one is measured fastest on it. The lab shows them and times
  each: on a Snapdragon 8 Elite Gen 5, SME computes the key's matrix product 9× faster than NEON.
- **In-app log capture** — Real-time `logcat` with colour-coded levels, tag/level filtering,
  autoscroll and export. Persisted to `filesDir`, so it survives a crash.
- **Tests** — About 470 in the Rust engine (unit and end-to-end; clippy-clean and enforced in CI), 20
  in `rustify-server`, and about 475 in Kotlin across the app, the shared code and the shared
  interface — the common ones run on the JVM and on iOS too. `scripts/test.ps1` runs the engine and
  the app; CI runs every platform's.
- **Baseline profiles** — Faster startup via Jetpack ProfileInstaller.
- **Internationalization** — English, Spanish and Japanese.

---

## 🏗️ Architecture & Tech Stack

Rustify is a Kotlin app over a Rust engine, and the split is deliberate: everything that is a
*decision* — what a link means, which audio matches which track, what "previous" does, whether a
failure is worth retrying — lives in the core, in one place, testable without a phone. Kotlin keeps
what is genuinely each platform's.

The 3.x series made the engine stop assuming it runs on Android; the 5.x series used that to bring
the same app to Windows, Linux, macOS, iOS and the web:

| Piece | What it is |
| --- | --- |
| `core_engine/` | The Rust core. JNI for Android and the desktop; a C ABI (`include/rustify.h`, one JSON dispatcher) for iOS and the server, answering exactly what the JNI bridge answers. |
| `shared/` | Kotlin Multiplatform: models, the bridge to the core (`NativeEngine`, an `expect object`), the queue player for the platforms that use the core's audio engine. |
| `sharedUi/` | Compose Multiplatform: the screens and `RustifyApp`, the root every platform draws. What only one platform can do goes behind four seams — `PlayerController`, `AppPlatform`, `AppServices`, `AppShell`. |
| `app/` | Android: Media3, Android Auto, widgets, the DSP offload, Gemini Nano. |
| `desktopApp/` | Windows, Linux and macOS: a JVM app with jpackage installers, and `login/`, the Spotify sign-in window over the system webview (wry + tao). |
| `iosApp/` | The iOS host (XcodeGen), embedding the shared interface as a framework. |
| `server/` | `rustify-server`: the core behind an HTTP API (axum), the web build of `sharedUi` (Kotlin/Wasm) as static files, and the sessions in front of both. |

### 1. The Core Engine (Rust 🦀)

`core_engine/`, compiled to `libcore_engine.so`:

| Module | What lives there |
| --- | --- |
| `env/` | The platform boundary: HTTP, storage, clock, background work, logging. `android.rs` is the only place `reqwest` appears; `mock.rs` is what makes the engine testable with no network; `schema.rs` versions everything persisted. |
| `spotify/` | The web client — GraphQL query hashes kept current, `sp_dc` session, token refresh, `Retry-After` — plus album, artist, playlist, track, search, browse, user, and the hand-rolled Canvas protobuf. |
| `youtube/` | RustyPipe 0.11 for YouTube Music, and a general YouTube scraper for the "find alternatives" dialog and the Explore tab. |
| `audio/` | Deezer's Blowfish/CBC stripe scheme. |
| `server/` | The local streaming server: disk cache, range requests, handles, the decrypt-in-transit proxy, and `lan.rs` — the single place that binds a real network interface. |
| `addon/` | The add-on protocol and its host security: resolve the name, judge every address it resolves to, pin the connection to what was approved. |
| `matcher/` | `algorithm.rs` matches a Spotify track to an audio source; `local.rs` matches it to a file on your phone. |
| `search/` | Accent-folding index and query, shared by every list in the app. |
| `player/` | The queue reducer: next, previous, repeat, end of queue. |
| `links/` | Every link shape Rustify reads or writes. |
| `listened/` | "Already heard" as a bitfield that survives a playlist being edited. |
| `calendar/` | Release dates at the three precisions Spotify actually returns. |
| `export/` | The user-owned data export, and the deny-list of what must never be in it. |
| `types/id.rs` | `TrackId` / `PlaylistId` — content identity as a type rather than a string. |
| `errors.rs` | What kind of failure it was: `auth`, `rateLimited`, `transient`, `permanent`. |
| `adblock_engine.rs` | Brave's adblock-rust, parsing EasyList / uBO syntax for the in-app web player. |
| `lib.rs` | The JNI bridge — every Rust capability exposed to Kotlin over a JSON contract. |
| `ffi.rs` | The same capabilities over a C ABI, for iOS and `rustify-server`. |
| `engine/` | The audio engine for the platforms without Media3: decoding (Symphonia), output (cpal), and the effects. |

Five things the core is built on, each of them the answer to a bug that shipped:

- **Content identity is a type.** A track id is never inspected with `startsWith` or split on `:`.
  Two shipped crashes came from doing that by hand, and both are now unrepresentable. The Kotlin and
  Rust codecs are pinned by tests covering the same cases on each side.
- **Nothing touches the platform directly.** No `reqwest`, no filesystem, no clock outside `Env`.
  That is what a second platform would implement, and what `MockEnv` replaces in tests.
- **A JNI call blocks the thread that makes it.** So the rule lives in the API: anything that can
  touch the network or the disk is `suspend` over `Dispatchers.IO`, and the blocking `external fun`
  underneath it is private. No lock is ever held across an `.await` — that was a real ANR.
- **Failure kind travels as a field, not as prose.** The engine knows whether a failure was auth,
  rate limiting, transport, or final; flattening that into a sentence and recovering it with a regex
  is one rewording away from silently breaking every retry.
- **Persisted state is versioned.** New shape, new migration step — never a stored shape changed in
  place.

### 2. The Android Client (Kotlin ☕)

`app/`, handling the experience and the OS integration:

- **UI layer** — Jetpack Compose and Material 3 throughout, unidirectional state over `StateFlow`.
- **Playback service** — `AudioPlayerService` owns the `ExoPlayer` instance, the URL cache and its
  expiry, the retry logic, and the cast session when there is one.
- **Media session** — `RustifyForegroundService`, a Media3 `MediaLibraryService` bound to the lock
  screen and Bluetooth controls, exposing the browsable tree Android Auto reads.
- **Audio chain** — `AudioSourceChain`, the provider chain with configurable order and per-provider
  timeouts, shared by playback and downloads.
- **Bridge layer** — `bridge/` is the Kotlin half of every core contract: `TrackRef`, `LocalMatcher`,
  `PlayerQueue`, `SpotifyRepository` and the rest.
- **Google Drive sync** — Browser-based OAuth (PKCE, AppAuth) with the `drive.appdata` scope, over
  OkHttp and REST v3.

### 3. Casting, and the doors that are opened on purpose

Casting is the only feature that binds anything to a real network interface, and four independent
things hold it together: nothing is open until a cast session starts; the bind is to **one interface
address**, never `0.0.0.0` (the core refuses that, and refuses loopback too); every accepted
connection's peer address is checked against the one device being cast to, before a byte is read; and
it closes on stop, on failure, and when the switch goes off.

Two honest limits: the phone is the source, so if it sleeps or leaves the wifi the music stops — this
is not Spotify Connect — and networks that isolate clients from one another (hotels, cafés) make it
impossible.

`rustify-server` is the second door, and exists to be reached: loopback by default, a session in
front of everything, and every core method that takes a path or opens a port refused from the
browser. A page opened at the server's own machine needs nothing (the connection, the `Host` and the
browser all have to say so); anything else needs the account.

### 4. What deliberately stays in Kotlin

Not everything belongs in the core, and the line is drawn on purpose. Shuffle stays in Kotlin because
the ordering is Rustify's own — a hand-queued block keeps its place behind the current track — and
the reducer refuses to hold a random source at all. Local music is deliberately **not** routed
through the local server: it already plays, and HTTP would add a hop, a port and a token to something
with no problem.

---

## 🛠️ Building the Project

### Prerequisites
- **JDK 21** and the **Rust toolchain** (`rustup`), for everything.
- **Android**: Android Studio (or the SDK), the **NDK**, and `cargo-ndk`:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install cargo-ndk
  ```
- **Linux desktop**: `libasound2-dev libdbus-1-dev pkg-config libwebkit2gtk-4.1-dev libgtk-3-dev`
  (and `rpm` to make the `.rpm`).
- **iOS**: a Mac with Xcode, `brew install xcodegen`, and the iOS Rust targets
  (`aarch64-apple-ios`, `aarch64-apple-ios-sim`).

The desktop and the web are opt-in Gradle properties, so an Android build never configures them. In
PowerShell, quote them (`"-Prustify.desktop=true"`).

### Android
`build.gradle.kts` invokes `cargo-ndk` to compile the Rust core before packaging the APKs (one per
ABI).

```bash
./gradlew assembleDebug        # debug
./gradlew assembleBenchmark    # release build (R8) under the debug key, installs beside the real app
./gradlew assembleRelease      # release
```

Google Drive needs an OAuth client of your own: `drive_appauth_client_id` and
`drive_appauth_client_secret` in `local.properties` (or `DRIVE_APPAUTH_CLIENT_ID` /
`DRIVE_APPAUTH_CLIENT_SECRET` in the environment). Without them, the build simply has no Drive.

### Windows, Linux, macOS

```bash
./gradlew -Prustify.desktop=true :desktopApp:run                              # run it
./gradlew -Prustify.desktop=true :desktopApp:packageDistributionForCurrentOS  # .msi/.exe, .deb/.rpm/AppImage or .dmg
```

The build compiles the core (`desktop-audio`) and the sign-in window for the host it runs on;
jpackage cannot cross-build, so each format is made on its own system.

### iOS

```bash
tools/ios/build-core.sh     # the core as a static library, once per core change
cd iosApp && xcodegen generate && open Rustify.xcodeproj
```

Xcode builds the Kotlin side itself (the shared interface as a framework) in a pre-build step.

### Web and `rustify-server`

```bash
./gradlew -Prustify.web=true :sharedUi:wasmJsBrowserDistribution
cd server && cargo build --release
RUSTIFY_WEB_ROOT=../sharedUi/build/dist/wasmJs/productionExecutable target/release/rustify-server
```

### Tests

```powershell
pwsh scripts/test.ps1              # engine and app
pwsh scripts/test.ps1 -Rust        # engine only
pwsh scripts/test.ps1 -Kotlin      # app only
pwsh scripts/coverage.ps1          # engine coverage
./gradlew "-Prustify.desktop=true" :sharedUi:desktopTest   # shared interface, against the real core
cd server; cargo test              # rustify-server
```

Worth knowing: `assembleRelease` does not build the test sources, and the Gradle build never invokes
`cargo test`. Run the script.

### Sideloading with AOT optimization
The release APK embeds its baseline profiles (`assets/dexopt/baseline.prof`) for background
optimization. To force immediate AOT compilation, install the APK alongside its Dex Metadata (`.dm`):

```bash
# ARM64 devices
adb install-multiple app/build/outputs/apk/release/app-arm64-v8a-release.apk app/build/outputs/apk/release/baselineProfiles/0/app-arm64-v8a-release.dm

# 32-bit ARM devices
adb install-multiple app/build/outputs/apk/release/app-armeabi-v7a-release.apk app/build/outputs/apk/release/baselineProfiles/0/app-armeabi-v7a-release.dm

# x86_64 emulators
adb install-multiple app/build/outputs/apk/release/app-x86_64-release.apk app/build/outputs/apk/release/baselineProfiles/0/app-x86_64-release.dm
```

> `baselineProfiles/0/` holds the profile for API 31 and above, `1/` the one for API 28-30. These come
> out of Gradle's output directory: Android Studio's signed-APK wizard writes to `app/release/` instead
> and does not copy the `.dm` files there.

---

## 🤝 Contributing

Contributions are welcome — open an Issue or a Pull Request. A few things worth knowing before you
start:

- Changes to the Rust core must compile for Android's three ABIs and the desktop, and the suites
  should pass (`pwsh scripts/test.ps1`, plus `:sharedUi:desktopTest` for the shared interface).
  CI builds and tests every platform, iOS included; `cargo clippy -- -D warnings` is enforced.
- **A screen is written once**, in `sharedUi/`. Nothing Android, JVM or `org.json` in `commonMain`;
  what only one platform can do goes behind one of the four seams, and a platform that lacks a
  feature says so (`can(...)`) rather than showing a button that does nothing.
- **A new core capability is four places**: the JNI function, its `ffi::dispatch` arm (with
  `METHODS` and `rustify.h`), the `expect` member in `NativeEngine`, and its C-ABI `actual`. A method
  that takes a path must also be refused from the browser in `server/` (`core::vet`).
- Several contracts are deliberately stated in two languages — track ids, error kinds, the wire
  models, the Deezer stripe scheme — and each is pinned by tests covering the same cases on both
  sides. If you change one half, change the test on the other.
- Strings live in `composeResources` for English, Spanish and Japanese; placeholders are positional
  (`%1$s`).

---

## 🌟 Acknowledgements

- The app icon uses the logo of the
  [Lightkeepers](https://genshin-impact.fandom.com/wiki/Lightkeepers) from Genshin Impact.

---

## ⚖️ License

Copyright (C) 2026 Pablo Soage Rodas

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU
General Public License v3.0** as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the `LICENSE`
file for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see https://www.gnu.org/licenses/.
