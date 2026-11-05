// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/NativeEngine.kt

package com.varuna.rustify.bridge

/**
 * The core (`core_engine`) as every platform reaches it: one API, one implementation per way in.
 *
 * - **Android and the desktop** (`jvmCommonMain`): JNI, one `external fun` per method.
 * - **iOS** (`iosMain`): the C ABI, `rustify_call` in `core_engine/include/rustify.h`, which answers
 *   what the JNI bridge of the same name answers.
 * - Later, the web (docs `serie-4.x/28` §2.2): the same calls over HTTP.
 *
 * Every answer is the JSON the JNI bridge answers, so the decoders in `SpotifyModels.kt` read it the
 * same on all of them, and so does a failure: `{"success":false,"error":…,"kind":…}`.
 *
 * ## `suspend` or plain
 *
 * A call into the core blocks the thread that makes it until the core answers. So:
 *
 * - **Anything that can touch the network or the filesystem is `suspend`** and moves itself off the
 *   calling thread. Calling one from the main thread is not something you can do by accident.
 * - **Anything that only reads or writes memory stays plain**, and says so. The ones named `…Native`
 *   kept that name because the JNI symbol is derived from it.
 * - A plain call that is documented as blocking (`readReplayGain`, `openCastListenerNative`) is the
 *   caller's to keep off the main thread.
 *
 * What only Android has a use for — the WebView ad blocker (`adblock*`) — is not here: it is on
 * the JVM implementation alone, which Android code reaches directly.
 */
expect object NativeEngine {

    // ── Listening history (4.0). Storage, so `suspend`. ─────────────────────────────────────────

    /** Appends one of the app's own plays. */
    suspend fun recordPlay(eventJson: String): String

    /** Imports a Rustify, Spotify Extended or legacy history file; answers an import report. */
    suspend fun importPlayHistory(historyJson: String): String

    /** Every play, oldest first, as a JSON array. */
    suspend fun allPlays(): String

    /** The Metrics screen's numbers for `[fromMs, toMs)`, in the zone described by [zoneJson]. */
    suspend fun playStats(fromMs: Long, toMs: Long, zoneJson: String): String

    /** The ReplayGain tags of a local file opened at [path], as JSON. Blocking. */
    fun readReplayGain(path: String): String

    /** The lyrics embedded in a local file opened at [path], as JSON. Blocking. */
    fun readEmbeddedLyrics(path: String): String

    // ── A local file's tags (`audio::tags`). The file, so `suspend`. ───────────────────────────

    /** The tags and duration of the file at [path], as a [LocalTags] JSON (or a failure). */
    suspend fun readTags(path: String): String

    /** The file's embedded cover as a [LocalCover] JSON, or `null`. */
    suspend fun readCover(path: String): String

    /** Writes [tagsJson] (a [TagsToWrite]) into the file at [path]; `{"success":true}` or a failure. */
    suspend fun writeTags(path: String, tagsJson: String): String

    // ── ListenBrainz. Network. ──────────────────────────────────────────────────────────────────

    /** Queues a listen and sends the queue; answers a report (sent, pending, rejected). */
    suspend fun listenBrainzSubmit(token: String, listenJson: String): String
    suspend fun listenBrainzPlayingNow(token: String, listenJson: String): String

    /** The token's user name as a JSON string, or `null` when ListenBrainz says it is invalid. */
    suspend fun listenBrainzValidate(token: String): String

    // ── Jam. Network, except the code, which is memory only. ───────────────────────────────────

    /** A fresh room code, as a JSON string. Memory only. */
    fun jamNewCode(): String
    suspend fun jamJoin(configJson: String): String

    /** Waits up to [timeoutMs] for the room to say something; never on the calling thread. */
    suspend fun jamPoll(timeoutMs: Int): String
    suspend fun jamPublishState(stateJson: String): String
    suspend fun jamSend(commandJson: String): String
    suspend fun jamKick(id: String): String
    suspend fun jamLeave(): String

    // ── Last.fm. Network. ───────────────────────────────────────────────────────────────────────

    suspend fun lastFmLogin(apiKey: String, secret: String, user: String, password: String): String
    suspend fun lastFmSubmit(credentialsJson: String, listenJson: String): String
    suspend fun lastFmNowPlaying(credentialsJson: String, listenJson: String): String

    /** The tempo of an audio file or stream at [path], as JSON `{bpm, confidence}` or `null`. Off the caller's thread. */
    suspend fun bpmAnalyze(path: String): String

    /** The tempo of mono PCM, as JSON `{bpm, confidence}` or `null`. CPU-bound, so off the caller's thread. */
    suspend fun tempoOf(samples: FloatArray, sampleRate: Int): String

    // ── Subsonic / OpenSubsonic. Network. ───────────────────────────────────────────────────────

    /** The server's name and version as a JSON string, or the failure envelope. */
    suspend fun subsonicPing(configJson: String): String

    /** The server's copy of a track (`{id, streamUrl, suffix, bitRate}`), `null`, or the failure envelope. */
    suspend fun subsonicFind(configJson: String, title: String, artistsJson: String, durationMs: Long): String

    // ── AutoEq. Search and profile touch the network; bands is arithmetic. ──────────────────────

    suspend fun autoEqSearch(query: String): String
    suspend fun autoEqProfile(path: String): String

    /** Memory only: parses a parametric EQ and evaluates it at [n] bands. */
    fun autoEqBands(peqText: String, n: Int): String

    /** The whole ranking of `[fromMs, toMs)` as CSV; [kind] is `tracks`, `artists` or `albums`. */
    suspend fun topCsv(fromMs: Long, toMs: Long, kind: String): String

    // ── Continue listening, and the place in a long track. Storage. ────────────────────────────

    /** Records where playback is in a context; anything not worth resuming removes the entry. */
    suspend fun recordListening(sessionJson: String): String

    /** The contexts to offer, newest first, as a JSON array. */
    suspend fun listContinueListening(): String

    /** Drops one context, or every one when [id] is empty. */
    suspend fun forgetListening(id: String): String

    /** Keeps the place in a long track, or drops it once finished or barely started. */
    suspend fun recordTrackPlace(trackId: String, positionMs: Long, durationMs: Long): String

    /** Where to pick a long track up again, or -1 when there is no place to offer. */
    suspend fun trackPlace(trackId: String): Long

    /** Forgets the place in a track, because the person chose to start it over. */
    suspend fun forgetTrackPlace(trackId: String): String

    /** This device's places (contexts, long tracks, their removals) as they travel in the backup. */
    suspend fun placesExport(): String

    /** Two backups' places as one, newest per place. Memory only. */
    fun placesMerge(a: String, b: String): String

    /** Takes in a backup's places, in this device's ids; answers `{sessions, bookmarks, skipped}`. */
    suspend fun placesApply(blockJson: String): String

    // ── Local search. Memory only, safe from the composition. ──────────────────────────────────

    /** Hands a list over to be folded and kept under [name]; `[{"id":…,"fields":[…]}]`, most important field first. */
    fun searchIndexNative(name: String, itemsJson: String): String

    /** Matching ids from a named index, best first, as a JSON array. [limit] 0 means all. */
    fun searchQueryNative(name: String, query: String, limit: Int): String

    /** Drops a named index. */
    fun searchForgetNative(name: String): String

    // ── Matching a Spotify track to a local file. Memory only; use [LocalMatcher]. ─────────────

    /** Hands the local library over, replacing what was there. */
    fun indexLocalTracksNative(itemsJson: String): String

    /** The id of the local file that is this track, as `{"id":…}`, or `{}`. */
    fun findLocalMatchNative(trackJson: String): String

    // ── Already listened. Storage. ──────────────────────────────────────────────────────────────

    /** Marks a track as listened within a context; [queueJson] is the context's ids in order, every time. */
    suspend fun markListened(contextId: String, queueJson: String, trackId: String): String

    /** One boolean per position in the queue, as a JSON array. */
    suspend fun listenedState(contextId: String, queueJson: String): String

    /** Drops one context's marks, or every one when [contextId] is empty. */
    suspend fun forgetListened(contextId: String): String

    // ── Data export. Storage. ───────────────────────────────────────────────────────────────────

    /** The whole export document as pretty-printed JSON text. Never carries a credential. */
    suspend fun exportData(producedBy: String): String

    /** Restores a document produced by [exportData]. **Replaces rather than merges.** */
    suspend fun restoreData(json: String): String

    // ── Release calendar and the player reducer. Memory only. ──────────────────────────────────

    /** Places releases on the calendar, newest first, undated last: `[{"id":…,"bucket":…,"day":…}]`. */
    fun arrangeReleasesNative(entriesJson: String, nowMs: Long): String

    /** Applies one action to the player state: `{"state":{…},"effects":[…]}`. Runs on a button press. */
    fun playerReduceNative(stateJson: String, actionJson: String): String

    // ── Casting: the one place the local server is not loopback-only. ──────────────────────────

    /** Opens the local server to one interface, answering one device. Binds a socket: blocking. */
    fun openCastListenerNative(bindTo: String, device: String): String

    /** Ends the cast session, immediately. Memory only. */
    fun closeCastListenerNative(): String

    /** The URL to hand the cast device for a registered handle, `{"url":…}`, or `{}`. */
    fun castUrlForNative(handle: String): String

    /** The session as the core sees it: `{"active":true,…}` or `{"active":false}`. */
    fun castSessionNative(): String

    // ── YouTube. ────────────────────────────────────────────────────────────────────────────────

    /** Searches YouTube; a list of `YouTubeTrack`. */
    suspend fun searchYouTube(query: String): String

    /** Spotify's own lyrics for a track (needs a session). */
    suspend fun getSpotifyLyrics(trackId: String): String

    /** A YouTube video's audio stream from one player request; an `InnertubeAudio` or the failure envelope. */
    suspend fun resolveInnertubeAudio(videoId: String, prefsJson: String): String

    /** A YouTube video with its sound, from one player request; an `InnertubeVideo` or the failure envelope. */
    suspend fun resolveInnertubeVideo(videoId: String): String

    /** Replaces the client table; `[]` restores the built-in one. */
    suspend fun setInnertubeClients(clientsJson: String): String

    /** Resolves a Spotify track id to a YouTube video id, `""` when none was found. Network. */
    suspend fun resolveYouTubeId(spotifyId: String, youtubeId: String): String

    /** Points the resolver at its directory and loads the persisted matches. Reads a file. */
    suspend fun initCacheDir(cacheDir: String)

    /** Memory only. Makes a Spotify track matchable by the YouTube resolver. */
    fun registerTrackMetadataNative(id: String, name: String, artistsJson: String, durationMs: Int, isrc: String)

    /** Memory only; the write to disk is handed to the core's background work. */
    fun setAlternativeTrackNative(spotifyId: String, youtubeId: String)

    /** Memory only: the chosen video id, or `""` when there is no match. */
    fun getAlternativeTrackNative(spotifyId: String): String

    /** Memory only. Tells the engine what is queued so it can pre-buffer. */
    fun updateQueueNative(trackIdsJson: String)

    // ── Links. Memory only and pure. ────────────────────────────────────────────────────────────

    /** Any link Rustify understands, as `{"kind":…,"id":…,…}`, or `{}` when it is not ours. */
    fun parseLinkNative(text: String): String

    /** Wraps a link so tapping it opens Rustify. An empty [host] falls back to `rustify://`. */
    fun wrapLinkNative(url: String, host: String): String

    /** The link inside a wrapper URL, or `""`. [knownHostsJson] is a JSON array, checked. */
    fun unwrapLinkNative(url: String, knownHostsJson: String): String

    // ── Add-ons. ────────────────────────────────────────────────────────────────────────────────

    /** Installs the add-on served at [url], after fetching and validating its manifest. */
    suspend fun installAddon(url: String): String

    /** The installed add-ons as a JSON array, in the order they are tried. */
    suspend fun listAddons(): String
    suspend fun uninstallAddon(id: String): String

    /** Turns an add-on off without losing the installation. */
    suspend fun setAddonEnabled(id: String, enabled: Boolean): String

    /** Reorders the fallback chain. [idsJson] is a JSON array of add-on ids. */
    suspend fun reorderAddons(idsJson: String): String

    /** Asks one add-on to resolve a track: the answer, `{}` when it does not have it, or a failure. */
    suspend fun resolveViaAddon(addonId: String, queryJson: String): String

    // ── Local streaming server and the stream cache. ───────────────────────────────────────────

    /** Starts the loopback streaming server, or returns the one running: `{"port":N,"token":…}`. */
    suspend fun startLocalServer(): String

    /** Registers a track with the local server: `ready` with a URL, `notCached`, or `refused`. */
    suspend fun registerLocalStream(requestJson: String): String

    /** Memory only. Drops a registration once the player is done with it. */
    fun forgetLocalStreamNative(handle: String)

    /** Bytes the stream cache is using. Walks a directory. */
    suspend fun streamCacheSize(cacheRoot: String): Long

    /** Empties the stream cache and returns the bytes freed. */
    suspend fun clearStreamCache(cacheRoot: String): Long

    /** Memory only. Sets the Accept-Language the Spotify client presents. */
    fun setLanguageNative(langCode: String)

    // ── Spotify: authentication. ────────────────────────────────────────────────────────────────

    /** Authenticates with the session cookie: `{"success":true,"user":{…}}` or a failure. */
    suspend fun loginSpotify(spDcCookie: String): String

    /** Memory only. Clears the session, the token and the cookie behind it. */
    fun logoutSpotifyNative()

    /** Refreshes the access token from the stored cookie. */
    suspend fun refreshSpotifyToken(): String

    /** Memory only: whether there is a session. Called from the main thread at start-up. */
    fun isSpotifyAuthenticatedNative(): Boolean

    /** Restores a session from a saved cookie and cached token; a full login when the token is stale. */
    suspend fun restoreSpotifySession(spDcCookie: String, accessToken: String, expirationMs: Long): String

    // ── Spotify: user and library. ──────────────────────────────────────────────────────────────

    suspend fun getSpotifyMe(): String

    /** The user's liked songs. [limit] maxes out at 50. */
    suspend fun getSpotifySavedTracks(limit: Int, offset: Int): String
    suspend fun getSpotifySavedAlbums(limit: Int, offset: Int): String
    suspend fun getSpotifySavedPlaylists(limit: Int, offset: Int): String
    suspend fun getSpotifyFollowedArtists(limit: Int, offset: Int): String

    // ── Spotify: albums. ────────────────────────────────────────────────────────────────────────

    suspend fun getSpotifyAlbum(albumId: String): String
    suspend fun getSpotifyAlbumTracks(albumId: String, limit: Int, offset: Int): String
    suspend fun getSpotifyNewReleases(limit: Int, offset: Int): String
    suspend fun saveSpotifyAlbums(idsJson: String): String
    suspend fun unsaveSpotifyAlbums(idsJson: String): String

    // ── Spotify: artists. ───────────────────────────────────────────────────────────────────────

    suspend fun getSpotifyArtist(artistId: String): String
    suspend fun getSpotifyArtistTopTracks(artistId: String, limit: Int, offset: Int): String
    suspend fun getSpotifyArtistAlbums(artistId: String, limit: Int, offset: Int): String
    suspend fun getSpotifyRelatedArtists(artistId: String, limit: Int, offset: Int): String
    suspend fun followSpotifyArtists(idsJson: String): String
    suspend fun unfollowSpotifyArtists(idsJson: String): String

    // ── Spotify: playlists. ─────────────────────────────────────────────────────────────────────

    suspend fun getSpotifyPlaylist(playlistId: String): String
    suspend fun getSpotifyPlaylistTracks(playlistId: String, limit: Int, offset: Int): String
    suspend fun createSpotifyPlaylist(userId: String, name: String, description: String, isPublic: Boolean): String

    /** Empty [name] or [description] means "leave that one alone". */
    suspend fun updateSpotifyPlaylist(playlistId: String, name: String, description: String): String

    /** [position] of -1 appends. */
    suspend fun addTracksToPlaylist(playlistId: String, trackIdsJson: String, position: Int): String
    suspend fun removeTracksFromPlaylist(playlistId: String, trackIdsJson: String): String
    suspend fun followPlaylist(playlistId: String): String
    suspend fun unfollowPlaylist(playlistId: String): String

    // ── Spotify: tracks. ────────────────────────────────────────────────────────────────────────

    suspend fun getSpotifyTrack(trackId: String): String
    suspend fun saveSpotifyTracks(idsJson: String): String
    suspend fun unsaveSpotifyTracks(idsJson: String): String

    /** Builds a "radio" for a track by finding a matching radio playlist. */
    suspend fun getSpotifyTrackRadio(trackId: String): String

    /** Biography, listeners, top cities and concerts of an artist. */
    suspend fun getArtistAbout(artistId: String): String

    /** Who made a track, grouped by role. No session needed. */
    suspend fun getTrackCredits(trackId: String): String

    /** Tracks that fit a playlist and are not in it. No session needed. */
    suspend fun extendPlaylist(playlistId: String, skipJson: String, count: Int): String

    /** The Canvas behind the cover art: `{"url":…}`, `{"url":null}`, or a failure. */
    suspend fun getSpotifyCanvas(trackUri: String): String

    // ── Spotify: search and browse. ─────────────────────────────────────────────────────────────

    /** [searchType] is "all", "tracks", "albums", "artists" or "playlists". */
    suspend fun searchSpotify(query: String, searchType: String, limit: Int, offset: Int): String
    suspend fun getSpotifyBrowse(limit: Int): String

    /** Points the Spotify client at its cache directory and reads the cached GQL hashes. */
    suspend fun initSpotifyCacheDir(cacheDir: String)

    /** Returns immediately: the scrape runs on the core's runtime. */
    fun warmupSpotifyHashesNative()

    /** Memory only: a snapshot of the GQL operation-name to sha256 map, or `{}`. */
    fun getSpotifyHashesNative(): String

    // ── YouTube Music. Network. ─────────────────────────────────────────────────────────────────

    suspend fun searchYtMusic(query: String): String
    suspend fun getYtmAlbum(browseId: String): String
    suspend fun getYtmArtist(channelId: String): String
    suspend fun getYtmPlaylist(playlistId: String): String

    // ── Audio lab (4.7.1). CPU only, no I/O: plain calls, but keep them off the main thread. ──

    /**
     * One frame of mono PCM (length a power of two, ≥ 64) → [bands] dB values, then RMS and peak.
     * Empty on unusable input. [kernel] is an id from [dspInfoNative], or "" for the best one.
     */
    fun dspAnalyzeNative(samples: FloatArray, sampleRate: Int, bands: Int, kernel: String): FloatArray?

    /** `{"features":[…],"kernels":[{"id","name"}],"best":"…"}` — the CPU's ARM extensions and kernels. */
    fun dspInfoNative(): String

    /** `[{"id","name","ns"}]` — nanoseconds per [n]-sample frame for each kernel, [iterations] runs. */
    fun dspBenchNative(n: Int, iterations: Int): String

    /**
     * Pushes one frame into the key and mel analysis: `[columns, key, r, chroma×12, columns×52 mel dB]`.
     * `columns` is 0 or 16 (a batch went through the matrix product); `key` 0–11 major on C…B, 12–23
     * minor, -1 for none yet. [reset] starts over. Empty on unusable input.
     */
    fun dspTonalNative(samples: FloatArray, sampleRate: Int, reset: Boolean): FloatArray?

    /** `[{"id","name","ns"}]` — nanoseconds per 64×k by k×16 matrix product, k = [n] / 2. */
    fun dspMatBenchNative(n: Int, iterations: Int): String

    /** `{"sampleRate":44100,"samples":[...]}` — latest [n] mono samples from audio output. */
    fun engineTapNative(n: Int): String
}
