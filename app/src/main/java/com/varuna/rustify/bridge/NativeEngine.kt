// app/src/main/java/com/varuna/rustify/bridge/NativeEngine.kt

package com.varuna.rustify.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JNI bridge to the Rust `core_engine`.
 *
 * ## Why half of this file is `suspend`
 *
 * **A JNI call blocks the thread that makes it.** Every bridge in `lib.rs` that talks to the
 * network runs `get_runtime().block_on(...)`, so the calling thread sits there until the request
 * comes back. On the main thread, on a slow connection, that is an ANR — the dialog that says
 * Rustify is not responding while the app is, in fact, perfectly alive.
 *
 * Before 3.1 the discipline for that lived at the call sites: forty-two plain `external fun`s and
 * eighty-odd places that were each individually expected to remember `Dispatchers.IO`. That is not
 * a discipline, it is an invitation, and one forgetful call site is enough.
 *
 * So the rule is now in the API, where the compiler enforces it:
 *
 * - **Anything that can touch the network or the filesystem is `suspend`** and wraps itself in
 *   `Dispatchers.IO`. Calling one from the main thread is not something you can do by accident.
 * - **Anything that only reads or writes memory stays plain**, and says so.
 * - The blocking `external fun` underneath is **private**. It keeps its name, because the JNI
 *   symbol is derived from it; what changes is that nothing outside this file can reach it.
 *
 * Same philosophy as `TrackRef`: make the mistake unrepresentable rather than documented. See
 * `docs/stremio-core/PLAN-3.x.md` §7.
 */
object NativeEngine {
    init {
        System.loadLibrary("core_engine")
    }

    /** Every blocking bridge goes through here, so there is exactly one place that names the pool. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // =====================================================================
    // YOUTUBE ENGINE
    // =====================================================================

    private external fun searchYouTubeNative(query: String): String

    /**
     * Searches YouTube Music through the InnerTube API.
     * @param query Example: "USUM71700966" or "Bohemian Rhapsody Queen".
     * @return JSON: a list of YouTubeTrack objects.
     */
    suspend fun searchYouTube(query: String): String = io { searchYouTubeNative(query) }

    private external fun resolveYouTubeIdNative(spotifyId: String, youtubeId: String): String

    /** Resolves a Spotify track id to a YouTube video id. Network. */
    suspend fun resolveYouTubeId(spotifyId: String, youtubeId: String): String =
        io { resolveYouTubeIdNative(spotifyId, youtubeId) }

    private external fun initCacheDirNative(cacheDir: String)

    /**
     * Points the resolver at its cache directory and loads the persisted
     * spotify-id to youtube-id matches. Reads a file, so it is not free.
     *
     * @param cacheDir absolute path; must be `filesDir`, not `cacheDir` — the matches are the
     *   user's own choices and the OS may clear the cache directory at any time.
     */
    suspend fun initCacheDir(cacheDir: String) = io { initCacheDirNative(cacheDir) }

    /** In memory. Makes a Spotify track matchable by the YouTube resolver. */
    external fun registerTrackMetadataNative(
        id: String,
        name: String,
        artistsJson: String,
        durationMs: Int,
        isrc: String
    )

    /** In memory, with the write to disk handed to a background task in Rust. */
    external fun setAlternativeTrackNative(spotifyId: String, youtubeId: String)

    /** In memory: reads the live match map. Empty string when there is no match. */
    external fun getAlternativeTrackNative(spotifyId: String): String

    /** In memory. Tells the engine what is queued so it can pre-buffer. */
    external fun updateQueueNative(trackIdsJson: String)

    // ── Add-ons: installable audio backends ─────────────────────────────────────────────────
    //
    // A backend stops being "code plus a release" and becomes a URL. What an add-on is told about a
    // track is the whole of `AddonTrackQuery` and nothing else: no Spotify token, no cookie, no
    // account id ever reaches one.

    private external fun installAddonNative(url: String): String

    /**
     * Installs the add-on served at [url]. Fetches and validates its manifest first, so one that is
     * unreachable, served over plain http, pointing at a private address or badly described never
     * reaches the installed list.
     *
     * @return the add-on as JSON, or `{"success":false,"error":"..."}`.
     */
    suspend fun installAddon(url: String): String = io { installAddonNative(url) }

    private external fun listAddonsNative(): String

    /** The installed add-ons as a JSON array, in the order they are tried. Reads storage. */
    suspend fun listAddons(): String = io { listAddonsNative() }

    private external fun uninstallAddonNative(id: String): String

    suspend fun uninstallAddon(id: String): String = io { uninstallAddonNative(id) }

    private external fun setAddonEnabledNative(id: String, enabled: Boolean): String

    /** Turns an add-on off without losing the installation. */
    suspend fun setAddonEnabled(id: String, enabled: Boolean): String =
        io { setAddonEnabledNative(id, enabled) }

    private external fun reorderAddonsNative(idsJson: String): String

    /** Reorders the fallback chain. [idsJson] is a JSON array of add-on ids. */
    suspend fun reorderAddons(idsJson: String): String = io { reorderAddonsNative(idsJson) }

    private external fun resolveViaAddonNative(addonId: String, queryJson: String): String

    /**
     * Asks one add-on to resolve a track.
     *
     * @return the answer as JSON, `{}` when the add-on does not have this track — a normal outcome
     *   meaning "move on to the next provider" — or `{"success":false,...}` on failure.
     */
    suspend fun resolveViaAddon(addonId: String, queryJson: String): String =
        io { resolveViaAddonNative(addonId, queryJson) }

    // ── Local streaming server and the stream cache ─────────────────────────────────────────
    //
    // Lets Media3 play an ordinary http:// URL instead of needing a custom DataSource for the disk
    // cache and for decryption. The server binds 127.0.0.1 only, on an ephemeral port, and every
    // request carries a per-process token: on Android any installed app can reach localhost, so
    // binding to loopback is necessary and nowhere near sufficient.

    private external fun startLocalServerNative(): String

    /**
     * Starts the loopback streaming server, or returns the one already running.
     * @return `{"port":N,"token":"..."}`, or `{"success":false,"error":"..."}` on failure.
     */
    suspend fun startLocalServer(): String = io { startLocalServerNative() }

    private external fun registerLocalStreamNative(requestJson: String): String

    /**
     * Registers a track with the local server and asks for the URL to play.
     *
     * [requestJson] carries `upstreamUrl`, `cacheKey`, `cacheRoot`, and optionally `mime`,
     * `deezerSngId` and `ttlMs`. JSON rather than positional arguments because this signature has
     * already changed twice, and a changed JNI signature is a `NoSuchMethodError` at run time
     * rather than an error at build time.
     *
     * @return `{"status":"ready","url":"http://127.0.0.1:…"}` when the bytes are already on disk,
     *   `{"status":"notCached"}` when they are not — in which case **play the upstream URL**, which
     *   is what the app did before this server existed, while a background fill runs — or
     *   `{"status":"refused","reason":"…"}`.
     */
    suspend fun registerLocalStream(requestJson: String): String =
        io { registerLocalStreamNative(requestJson) }

    /** In memory. Drops a registration once the player is done with it. */
    external fun forgetLocalStreamNative(handle: String)

    private external fun streamCacheSizeNative(cacheRoot: String): Long

    /** Bytes the stream cache is currently using. Walks a directory. */
    suspend fun streamCacheSize(cacheRoot: String): Long = io { streamCacheSizeNative(cacheRoot) }

    private external fun clearStreamCacheNative(cacheRoot: String): Long

    /** Empties the stream cache and returns the bytes freed. */
    suspend fun clearStreamCache(cacheRoot: String): Long = io { clearStreamCacheNative(cacheRoot) }

    /** In memory. Sets the Accept-Language header the Spotify client presents. */
    external fun setLanguageNative(langCode: String)

    // =====================================================================
    // SPOTIFY — AUTHENTICATION
    // =====================================================================

    private external fun loginSpotifyNative(spDcCookie: String): String

    /**
     * Authenticates with the intercepted session cookie, running the full TOTP token flow.
     * @return `{"success":true,"user":{...}}` or `{"success":false,"error":"..."}`.
     */
    suspend fun loginSpotify(spDcCookie: String): String = io { loginSpotifyNative(spDcCookie) }

    /** In memory. Clears the session, both the token and the cookie behind it. */
    external fun logoutSpotifyNative()

    private external fun refreshSpotifyTokenNative(): String

    /** Refreshes the access token from the stored cookie. */
    suspend fun refreshSpotifyToken(): String = io { refreshSpotifyTokenNative() }

    /**
     * In memory: one `Option` read behind a lock held for the length of a comparison.
     *
     * This one is worth a note. It used to be able to block for as long as a token refresh took,
     * because the engine held a process-wide write lock across the network call — and it is called
     * from the main thread at start-up. That is fixed on the Rust side (see `SPOTIFY_CLIENT`), not
     * by moving this call, which is why it is still allowed to be plain.
     */
    external fun isSpotifyAuthenticatedNative(): Boolean

    private external fun restoreSpotifySessionNative(
        spDcCookie: String,
        accessToken: String,
        expirationMs: Long
    ): String

    /**
     * Restores a previous session from a saved cookie and cached token.
     *
     * Free when the cached token is still live — no request at all — and a full login when it is
     * not, which is why it is `suspend` either way.
     */
    suspend fun restoreSpotifySession(
        spDcCookie: String,
        accessToken: String,
        expirationMs: Long
    ): String = io { restoreSpotifySessionNative(spDcCookie, accessToken, expirationMs) }

    // =====================================================================
    // SPOTIFY — USER / LIBRARY
    // =====================================================================

    private external fun getSpotifyMeNative(): String

    suspend fun getSpotifyMe(): String = io { getSpotifyMeNative() }

    private external fun getSpotifySavedTracksNative(limit: Int, offset: Int): String

    /** The user's liked songs. `limit` maxes out at 50. */
    suspend fun getSpotifySavedTracks(limit: Int, offset: Int): String =
        io { getSpotifySavedTracksNative(limit, offset) }

    private external fun getSpotifySavedAlbumsNative(limit: Int, offset: Int): String

    suspend fun getSpotifySavedAlbums(limit: Int, offset: Int): String =
        io { getSpotifySavedAlbumsNative(limit, offset) }

    private external fun getSpotifySavedPlaylistsNative(limit: Int, offset: Int): String

    suspend fun getSpotifySavedPlaylists(limit: Int, offset: Int): String =
        io { getSpotifySavedPlaylistsNative(limit, offset) }

    private external fun getSpotifyFollowedArtistsNative(limit: Int, offset: Int): String

    suspend fun getSpotifyFollowedArtists(limit: Int, offset: Int): String =
        io { getSpotifyFollowedArtistsNative(limit, offset) }

    // =====================================================================
    // SPOTIFY — ALBUMS
    // =====================================================================

    private external fun getSpotifyAlbumNative(albumId: String): String

    suspend fun getSpotifyAlbum(albumId: String): String = io { getSpotifyAlbumNative(albumId) }

    private external fun getSpotifyAlbumTracksNative(
        albumId: String,
        limit: Int,
        offset: Int
    ): String

    suspend fun getSpotifyAlbumTracks(albumId: String, limit: Int, offset: Int): String =
        io { getSpotifyAlbumTracksNative(albumId, limit, offset) }

    private external fun getSpotifyNewReleasesNative(limit: Int, offset: Int): String

    suspend fun getSpotifyNewReleases(limit: Int, offset: Int): String =
        io { getSpotifyNewReleasesNative(limit, offset) }

    private external fun saveSpotifyAlbumsNative(idsJson: String): String

    suspend fun saveSpotifyAlbums(idsJson: String): String = io { saveSpotifyAlbumsNative(idsJson) }

    private external fun unsaveSpotifyAlbumsNative(idsJson: String): String

    suspend fun unsaveSpotifyAlbums(idsJson: String): String =
        io { unsaveSpotifyAlbumsNative(idsJson) }

    // =====================================================================
    // SPOTIFY — ARTISTS
    // =====================================================================

    private external fun getSpotifyArtistNative(artistId: String): String

    suspend fun getSpotifyArtist(artistId: String): String = io { getSpotifyArtistNative(artistId) }

    private external fun getSpotifyArtistTopTracksNative(
        artistId: String,
        limit: Int,
        offset: Int
    ): String

    suspend fun getSpotifyArtistTopTracks(artistId: String, limit: Int, offset: Int): String =
        io { getSpotifyArtistTopTracksNative(artistId, limit, offset) }

    private external fun getSpotifyArtistAlbumsNative(
        artistId: String,
        limit: Int,
        offset: Int
    ): String

    suspend fun getSpotifyArtistAlbums(artistId: String, limit: Int, offset: Int): String =
        io { getSpotifyArtistAlbumsNative(artistId, limit, offset) }

    private external fun getSpotifyRelatedArtistsNative(
        artistId: String,
        limit: Int,
        offset: Int
    ): String

    suspend fun getSpotifyRelatedArtists(artistId: String, limit: Int, offset: Int): String =
        io { getSpotifyRelatedArtistsNative(artistId, limit, offset) }

    private external fun followSpotifyArtistsNative(idsJson: String): String

    suspend fun followSpotifyArtists(idsJson: String): String =
        io { followSpotifyArtistsNative(idsJson) }

    private external fun unfollowSpotifyArtistsNative(idsJson: String): String

    suspend fun unfollowSpotifyArtists(idsJson: String): String =
        io { unfollowSpotifyArtistsNative(idsJson) }

    // =====================================================================
    // SPOTIFY — PLAYLISTS
    // =====================================================================

    private external fun getSpotifyPlaylistNative(playlistId: String): String

    suspend fun getSpotifyPlaylist(playlistId: String): String =
        io { getSpotifyPlaylistNative(playlistId) }

    private external fun getSpotifyPlaylistTracksNative(
        playlistId: String,
        limit: Int,
        offset: Int
    ): String

    suspend fun getSpotifyPlaylistTracks(playlistId: String, limit: Int, offset: Int): String =
        io { getSpotifyPlaylistTracksNative(playlistId, limit, offset) }

    private external fun createSpotifyPlaylistNative(
        userId: String,
        name: String,
        description: String,
        isPublic: Boolean
    ): String

    suspend fun createSpotifyPlaylist(
        userId: String,
        name: String,
        description: String,
        isPublic: Boolean
    ): String = io { createSpotifyPlaylistNative(userId, name, description, isPublic) }

    private external fun updateSpotifyPlaylistNative(
        playlistId: String,
        name: String,
        description: String
    ): String

    /** Empty [name] or [description] means "leave that one alone". */
    suspend fun updateSpotifyPlaylist(
        playlistId: String,
        name: String,
        description: String
    ): String = io { updateSpotifyPlaylistNative(playlistId, name, description) }

    private external fun addTracksToPlaylistNative(
        playlistId: String,
        trackIdsJson: String,
        position: Int
    ): String

    /** [position] of -1 appends. */
    suspend fun addTracksToPlaylist(
        playlistId: String,
        trackIdsJson: String,
        position: Int
    ): String = io { addTracksToPlaylistNative(playlistId, trackIdsJson, position) }

    private external fun removeTracksFromPlaylistNative(
        playlistId: String,
        trackIdsJson: String
    ): String

    suspend fun removeTracksFromPlaylist(playlistId: String, trackIdsJson: String): String =
        io { removeTracksFromPlaylistNative(playlistId, trackIdsJson) }

    private external fun followPlaylistNative(playlistId: String): String

    suspend fun followPlaylist(playlistId: String): String = io { followPlaylistNative(playlistId) }

    private external fun unfollowPlaylistNative(playlistId: String): String

    suspend fun unfollowPlaylist(playlistId: String): String =
        io { unfollowPlaylistNative(playlistId) }

    // =====================================================================
    // SPOTIFY — TRACKS
    // =====================================================================

    private external fun getSpotifyTrackNative(trackId: String): String

    suspend fun getSpotifyTrack(trackId: String): String = io { getSpotifyTrackNative(trackId) }

    private external fun saveSpotifyTracksNative(idsJson: String): String

    suspend fun saveSpotifyTracks(idsJson: String): String = io { saveSpotifyTracksNative(idsJson) }

    private external fun unsaveSpotifyTracksNative(idsJson: String): String

    suspend fun unsaveSpotifyTracks(idsJson: String): String =
        io { unsaveSpotifyTracksNative(idsJson) }

    private external fun getSpotifyTrackRadioNative(trackId: String): String

    /** Builds a "radio" for a track by finding a matching radio playlist. */
    suspend fun getSpotifyTrackRadio(trackId: String): String =
        io { getSpotifyTrackRadioNative(trackId) }

    private external fun getSpotifyCanvasNative(trackUri: String): String

    /**
     * The Spotify Canvas — the short looping mp4 behind the cover art. Accepts a track id or a
     * full `spotify:track:<id>` URI.
     *
     * @return `{"url":"<mp4>"}`, `{"url":null}` when the track has no canvas, or
     *   `{"success":false,"error":"..."}`.
     */
    suspend fun getSpotifyCanvas(trackUri: String): String = io { getSpotifyCanvasNative(trackUri) }

    // =====================================================================
    // SPOTIFY — SEARCH
    // =====================================================================

    private external fun searchSpotifyNative(
        query: String,
        searchType: String,
        limit: Int,
        offset: Int
    ): String

    /**
     * @param searchType one of "all", "tracks", "albums", "artists", "playlists".
     * @return NormalizedSearchResults for "all", a PaginatedResponse otherwise.
     */
    suspend fun searchSpotify(query: String, searchType: String, limit: Int, offset: Int): String =
        io { searchSpotifyNative(query, searchType, limit, offset) }

    // =====================================================================
    // SPOTIFY — BROWSE
    // =====================================================================

    private external fun getSpotifyBrowseNative(limit: Int): String

    suspend fun getSpotifyBrowse(limit: Int): String = io { getSpotifyBrowseNative(limit) }

    private external fun initSpotifyCacheDirNative(cacheDir: String)

    /** Points the Spotify client at its cache directory and reads the cached GQL hashes off disk. */
    suspend fun initSpotifyCacheDir(cacheDir: String) = io { initSpotifyCacheDirNative(cacheDir) }

    /** Returns immediately: the scrape itself runs on the Rust runtime. */
    external fun warmupSpotifyHashesNative()

    /**
     * In memory: a snapshot of the GQL operation-name to sha256 map.
     * Example: `{"libraryV3":"2de10199b244...","fetchLibraryTracks":"087278b20b74..."}`, or `{}`.
     */
    external fun getSpotifyHashesNative(): String

    // =====================================================================
    // YOUTUBE MUSIC
    // =====================================================================

    private external fun searchYtMusicNative(query: String): String

    suspend fun searchYtMusic(query: String): String = io { searchYtMusicNative(query) }

    private external fun getYtmAlbumNative(browseId: String): String

    suspend fun getYtmAlbum(browseId: String): String = io { getYtmAlbumNative(browseId) }

    private external fun getYtmArtistNative(channelId: String): String

    suspend fun getYtmArtist(channelId: String): String = io { getYtmArtistNative(channelId) }

    private external fun getYtmPlaylistNative(playlistId: String): String

    suspend fun getYtmPlaylist(playlistId: String): String = io { getYtmPlaylistNative(playlistId) }

    // =====================================================================
    // ADBLOCK — network filtering for the in-app Spotify Web Player
    // =====================================================================

    /**
     * Compiles filter lists (uBlock Origin / EasyList syntax) into the blocking engine.
     *
     * Plain rather than `suspend` because it touches nothing outside memory — but it is CPU-heavy
     * enough to matter, and its one caller already runs it on `Dispatchers.IO`.
     */
    external fun adblockLoadRulesNative(rules: String): Boolean

    /**
     * Should this request be blocked?
     *
     * Called once per WebView request from the WebView's own thread, so it is deliberately **not**
     * `suspend`: it has to answer synchronously or the page waits. Every failure path answers false,
     * so a filtering problem can never break the page.
     *
     * @param resourceType adblock-rust vocabulary: "script", "image", "xmlhttprequest", "media"…
     */
    external fun adblockMatchesNative(url: String, sourceUrl: String, resourceType: String): Boolean

    /** True once a filter list has been compiled. */
    external fun adblockIsReadyNative(): Boolean

    /** Releases the compiled engine (frees its memory when the web player closes). */
    external fun adblockClearNative()
}
