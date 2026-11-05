// shared/src/coreCallMain/kotlin/com/varuna/rustify/bridge/NativeEngine.coreCall.kt
package com.varuna.rustify.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [NativeEngine] where the core is one JSON call away: iOS, by the C ABI's `rustify_call`, and the web,
 * by `rustify-server`'s `/api/call`, which is the same dispatcher. Every method goes through [CoreCall].
 *
 * Each method calls the ABI method of the JNI bridge's name without `Native`, with the arguments
 * `core_engine/include/rustify.h` lists for it, and answers what the JNI twin answers:
 *
 * - An argument the JNI twin takes as a string of JSON is passed as the JSON value
 *   ([CoreDispatch.value]); everything else as itself.
 * - Where the twin answers a bare string, a `long` or a `boolean`, the ABI's JSON form of it is read
 *   back ([CoreDispatch]); where the twin answers nothing, the ABI's `{"success":true}` is dropped.
 *
 * `suspend` methods run on `Dispatchers.IO` inside [CoreCall.call] (`tempoOf`, which is CPU only, on
 * `Dispatchers.Default`, as on the JVM); plain ones call on the caller's thread, as their JNI twins do.
 */
actual object NativeEngine {
    private fun now(method: String, build: JsonObjectBuilder.() -> Unit = {}): String =
        CoreCall.callBlocking(method, buildJsonObject(build))

    private suspend fun io(method: String, build: JsonObjectBuilder.() -> Unit = {}): String =
        CoreCall.call(method, buildJsonObject(build))

    private fun json(text: String) = CoreDispatch.value(text)

    // ── Listening history ───────────────────────────────────────────────────────────────────────

    actual suspend fun recordPlay(eventJson: String): String = io("recordPlay") { put("event", json(eventJson)) }

    actual suspend fun importPlayHistory(historyJson: String): String =
        io("importPlayHistory") { put("history", historyJson) }

    actual suspend fun allPlays(): String = io("allPlays")

    actual suspend fun playStats(fromMs: Long, toMs: Long, zoneJson: String): String = io("playStats") {
        put("fromMs", fromMs)
        put("toMs", toMs)
        put("zone", json(zoneJson))
    }

    actual fun readReplayGain(path: String): String = now("readReplayGain") { put("path", path) }

    actual fun readEmbeddedLyrics(path: String): String = now("readEmbeddedLyrics") { put("path", path) }

    actual suspend fun readTags(path: String): String = io("readTags") { put("path", path) }

    actual suspend fun readCover(path: String): String = io("readCover") { put("path", path) }

    actual suspend fun writeTags(path: String, tagsJson: String): String = io("writeTags") {
        put("path", path)
        put("tags", json(tagsJson))
    }

    // ── ListenBrainz ────────────────────────────────────────────────────────────────────────────

    actual suspend fun listenBrainzSubmit(token: String, listenJson: String): String = io("listenBrainzSubmit") {
        put("token", token)
        put("listen", json(listenJson))
    }

    actual suspend fun listenBrainzPlayingNow(token: String, listenJson: String): String =
        io("listenBrainzPlayingNow") {
            put("token", token)
            put("listen", json(listenJson))
        }

    actual suspend fun listenBrainzValidate(token: String): String = io("listenBrainzValidate") { put("token", token) }

    // ── Jam ─────────────────────────────────────────────────────────────────────────────────────

    actual fun jamNewCode(): String = now("jamNewCode")

    actual suspend fun jamJoin(configJson: String): String = io("jamJoin") { put("config", json(configJson)) }

    actual suspend fun jamPoll(timeoutMs: Int): String = io("jamPoll") { put("timeoutMs", timeoutMs) }

    actual suspend fun jamPublishState(stateJson: String): String =
        io("jamPublishState") { put("state", json(stateJson)) }

    actual suspend fun jamSend(commandJson: String): String = io("jamSend") { put("command", json(commandJson)) }

    actual suspend fun jamKick(id: String): String = io("jamKick") { put("id", id) }

    actual suspend fun jamLeave(): String = io("jamLeave")

    // ── Last.fm ─────────────────────────────────────────────────────────────────────────────────

    actual suspend fun lastFmLogin(apiKey: String, secret: String, user: String, password: String): String =
        io("lastFmLogin") {
            put("apiKey", apiKey)
            put("secret", secret)
            put("user", user)
            put("password", password)
        }

    actual suspend fun lastFmSubmit(credentialsJson: String, listenJson: String): String = io("lastFmSubmit") {
        put("credentials", json(credentialsJson))
        put("listen", json(listenJson))
    }

    actual suspend fun lastFmNowPlaying(credentialsJson: String, listenJson: String): String =
        io("lastFmNowPlaying") {
            put("credentials", json(credentialsJson))
            put("listen", json(listenJson))
        }

    /**
     * The samples cross as a JSON array. A NaN or an infinity has no JSON form and would make the
     * whole argument object unreadable, so it is sent as silence.
     */
    actual suspend fun bpmAnalyze(path: String): String = io("bpmAnalyze") { put("path", path) }

    actual suspend fun tempoOf(samples: FloatArray, sampleRate: Int): String = withContext(Dispatchers.Default) {
        now("tempoOf") {
            put("samples", JsonArray(samples.map { JsonPrimitive(if (it.isFinite()) it else 0f) }))
            put("sampleRate", sampleRate)
        }
    }

    // ── Subsonic ────────────────────────────────────────────────────────────────────────────────

    actual suspend fun subsonicPing(configJson: String): String = io("subsonicPing") { put("config", json(configJson)) }

    actual suspend fun subsonicFind(configJson: String, title: String, artistsJson: String, durationMs: Long): String =
        io("subsonicFind") {
            put("config", json(configJson))
            put("title", title)
            put("artists", json(artistsJson))
            put("durationMs", durationMs)
        }

    // ── AutoEq ──────────────────────────────────────────────────────────────────────────────────

    actual suspend fun autoEqSearch(query: String): String = io("autoEqSearch") { put("query", query) }

    actual suspend fun autoEqProfile(path: String): String = io("autoEqProfile") { put("path", path) }

    actual fun autoEqBands(peqText: String, n: Int): String = now("autoEqBands") {
        put("peqText", peqText)
        put("n", n)
    }

    actual suspend fun topCsv(fromMs: Long, toMs: Long, kind: String): String = io("topCsv") {
        put("fromMs", fromMs)
        put("toMs", toMs)
        put("kind", kind)
    }

    // ── Continue listening, and the place in a long track ──────────────────────────────────────

    actual suspend fun recordListening(sessionJson: String): String =
        io("recordListening") { put("session", json(sessionJson)) }

    actual suspend fun listContinueListening(): String = io("listContinueListening")

    actual suspend fun forgetListening(id: String): String = io("forgetListening") { put("id", id) }

    actual suspend fun recordTrackPlace(trackId: String, positionMs: Long, durationMs: Long): String =
        io("recordTrackPlace") {
            put("trackId", trackId)
            put("positionMs", positionMs)
            put("durationMs", durationMs)
        }

    actual suspend fun trackPlace(trackId: String): Long =
        CoreDispatch.long(io("trackPlace") { put("trackId", trackId) }, -1L)

    actual suspend fun forgetTrackPlace(trackId: String): String = io("forgetTrackPlace") { put("trackId", trackId) }

    actual suspend fun placesExport(): String = io("placesExport")

    actual fun placesMerge(a: String, b: String): String = now("placesMerge") {
        put("a", json(a))
        put("b", json(b))
    }

    actual suspend fun placesApply(blockJson: String): String = io("placesApply") { put("block", json(blockJson)) }

    // ── Local search ────────────────────────────────────────────────────────────────────────────

    actual fun searchIndexNative(name: String, itemsJson: String): String = now("searchIndex") {
        put("name", name)
        put("items", json(itemsJson))
    }

    actual fun searchQueryNative(name: String, query: String, limit: Int): String = now("searchQuery") {
        put("name", name)
        put("query", query)
        put("limit", limit)
    }

    actual fun searchForgetNative(name: String): String = now("searchForget") { put("name", name) }

    // ── Matching a Spotify track to a local file ───────────────────────────────────────────────

    actual fun indexLocalTracksNative(itemsJson: String): String =
        now("indexLocalTracks") { put("items", json(itemsJson)) }

    actual fun findLocalMatchNative(trackJson: String): String = now("findLocalMatch") { put("track", json(trackJson)) }

    // ── Already listened ────────────────────────────────────────────────────────────────────────

    actual suspend fun markListened(contextId: String, queueJson: String, trackId: String): String =
        io("markListened") {
            put("contextId", contextId)
            put("queue", json(queueJson))
            put("trackId", trackId)
        }

    actual suspend fun listenedState(contextId: String, queueJson: String): String = io("listenedState") {
        put("contextId", contextId)
        put("queue", json(queueJson))
    }

    actual suspend fun forgetListened(contextId: String): String = io("forgetListened") { put("contextId", contextId) }

    // ── Data export ─────────────────────────────────────────────────────────────────────────────

    actual suspend fun exportData(producedBy: String): String = io("exportData") { put("producedBy", producedBy) }

    /** The document crosses as text, as the core reads it (`json`), not as a parsed value. */
    actual suspend fun restoreData(json: String): String = io("restoreData") { put("json", json) }

    // ── Release calendar and the player reducer ────────────────────────────────────────────────

    actual fun arrangeReleasesNative(entriesJson: String, nowMs: Long): String = now("arrangeReleases") {
        put("entries", json(entriesJson))
        put("nowMs", nowMs)
    }

    actual fun playerReduceNative(stateJson: String, actionJson: String): String = now("playerReduce") {
        put("state", json(stateJson))
        put("action", json(actionJson))
    }

    // ── Casting ─────────────────────────────────────────────────────────────────────────────────

    actual fun openCastListenerNative(bindTo: String, device: String): String = now("openCastListener") {
        put("bindTo", bindTo)
        put("device", device)
    }

    actual fun closeCastListenerNative(): String = now("closeCastListener")

    actual fun registerLocalFileNative(path: String, mime: String): String = now("registerLocalFile") { put("path", path); put("mime", mime) }
    actual fun castUrlForNative(handle: String): String = now("castUrlFor") { put("handle", handle) }

    actual fun castSessionNative(): String = now("castSession")

    // ── YouTube ─────────────────────────────────────────────────────────────────────────────────

    actual suspend fun searchYouTube(query: String): String = io("searchYouTube") { put("query", query) }

    actual suspend fun getSpotifyLyrics(trackId: String): String = io("getSpotifyLyrics") { put("id", trackId) }

    actual suspend fun resolveInnertubeAudio(videoId: String, prefsJson: String): String =
        io("resolveInnertubeAudio") {
            put("videoId", videoId)
            put("prefs", json(prefsJson))
        }

    actual suspend fun resolveInnertubeVideo(videoId: String): String =
        io("resolveInnertubeVideo") { put("videoId", videoId) }

    actual suspend fun setInnertubeClients(clientsJson: String): String =
        io("setInnertubeClients") { put("clients", json(clientsJson)) }

    actual suspend fun resolveYouTubeId(spotifyId: String, youtubeId: String): String = CoreDispatch.string(
        io("resolveYouTubeId") {
            put("spotifyId", spotifyId)
            put("youtubeId", youtubeId)
        },
    )

    actual suspend fun initCacheDir(cacheDir: String) {
        io("initCacheDir") { put("dir", cacheDir) }
    }

    actual fun registerTrackMetadataNative(id: String, name: String, artistsJson: String, durationMs: Int, isrc: String) {
        now("registerTrackMetadata") {
            put("id", id)
            put("name", name)
            put("artists", json(artistsJson))
            put("durationMs", durationMs)
            put("isrc", isrc)
        }
    }

    actual fun setAlternativeTrackNative(spotifyId: String, youtubeId: String) {
        now("setAlternativeTrack") {
            put("spotifyId", spotifyId)
            put("youtubeId", youtubeId)
        }
    }

    actual fun getAlternativeTrackNative(spotifyId: String): String =
        CoreDispatch.string(now("getAlternativeTrack") { put("spotifyId", spotifyId) })

    actual fun updateQueueNative(trackIdsJson: String) {
        now("updateQueue") { put("trackIds", json(trackIdsJson)) }
    }

    // ── Links ───────────────────────────────────────────────────────────────────────────────────

    actual fun parseLinkNative(text: String): String = now("parseLink") { put("text", text) }

    actual fun wrapLinkNative(url: String, host: String): String = CoreDispatch.string(
        now("wrapLink") {
            put("url", url)
            put("host", host)
        },
    )

    actual fun unwrapLinkNative(url: String, knownHostsJson: String): String = CoreDispatch.string(
        now("unwrapLink") {
            put("url", url)
            put("knownHosts", json(knownHostsJson))
        },
    )

    // ── Add-ons ─────────────────────────────────────────────────────────────────────────────────

    actual suspend fun installAddon(url: String): String = io("installAddon") { put("url", url) }

    actual suspend fun listAddons(): String = io("listAddons")

    actual suspend fun uninstallAddon(id: String): String = io("uninstallAddon") { put("id", id) }

    actual suspend fun setAddonEnabled(id: String, enabled: Boolean): String = io("setAddonEnabled") {
        put("id", id)
        put("enabled", enabled)
    }

    actual suspend fun reorderAddons(idsJson: String): String = io("reorderAddons") { put("ids", json(idsJson)) }

    actual suspend fun resolveViaAddon(addonId: String, queryJson: String): String = io("resolveViaAddon") {
        put("addonId", addonId)
        put("query", json(queryJson))
    }

    // ── Local streaming server and the stream cache ────────────────────────────────────────────

    actual suspend fun startLocalServer(): String = io("startLocalServer")

    actual suspend fun registerLocalStream(requestJson: String): String =
        io("registerLocalStream") { put("request", json(requestJson)) }

    actual fun forgetLocalStreamNative(handle: String) {
        now("forgetLocalStream") { put("handle", handle) }
    }

    actual suspend fun streamCacheSize(cacheRoot: String): Long =
        CoreDispatch.long(io("streamCacheSize") { put("cacheRoot", cacheRoot) }, 0L)

    actual suspend fun clearStreamCache(cacheRoot: String): Long =
        CoreDispatch.long(io("clearStreamCache") { put("cacheRoot", cacheRoot) }, 0L)

    actual fun setLanguageNative(langCode: String) {
        now("setLanguage") { put("lang", langCode) }
    }

    // ── Spotify: authentication ─────────────────────────────────────────────────────────────────

    actual suspend fun loginSpotify(spDcCookie: String): String = io("loginSpotify") { put("spDc", spDcCookie) }

    actual fun logoutSpotifyNative() {
        now("logoutSpotify")
    }

    actual suspend fun refreshSpotifyToken(): String = io("refreshSpotifyToken")

    actual fun isSpotifyAuthenticatedNative(): Boolean = CoreDispatch.boolean(now("isSpotifyAuthenticated"))

    actual suspend fun restoreSpotifySession(spDcCookie: String, accessToken: String, expirationMs: Long): String =
        io("restoreSpotifySession") {
            put("spDc", spDcCookie)
            put("accessToken", accessToken)
            put("expiration", expirationMs)
        }

    // ── Spotify: user and library ───────────────────────────────────────────────────────────────

    actual suspend fun getSpotifyMe(): String = io("getSpotifyMe")

    actual suspend fun getSpotifySavedTracks(limit: Int, offset: Int): String = page("getSpotifySavedTracks", limit, offset)

    actual suspend fun getSpotifySavedAlbums(limit: Int, offset: Int): String = page("getSpotifySavedAlbums", limit, offset)

    actual suspend fun getSpotifySavedPlaylists(limit: Int, offset: Int): String =
        page("getSpotifySavedPlaylists", limit, offset)

    actual suspend fun getSpotifyFollowedArtists(limit: Int, offset: Int): String =
        page("getSpotifyFollowedArtists", limit, offset)

    // ── Spotify: albums ─────────────────────────────────────────────────────────────────────────

    actual suspend fun getSpotifyAlbum(albumId: String): String = io("getSpotifyAlbum") { put("id", albumId) }

    actual suspend fun getSpotifyAlbumTracks(albumId: String, limit: Int, offset: Int): String =
        page("getSpotifyAlbumTracks", limit, offset, albumId)

    actual suspend fun getSpotifyNewReleases(limit: Int, offset: Int): String = page("getSpotifyNewReleases", limit, offset)

    actual suspend fun saveSpotifyAlbums(idsJson: String): String = io("saveSpotifyAlbums") { put("ids", json(idsJson)) }

    actual suspend fun unsaveSpotifyAlbums(idsJson: String): String =
        io("unsaveSpotifyAlbums") { put("ids", json(idsJson)) }

    // ── Spotify: artists ────────────────────────────────────────────────────────────────────────

    actual suspend fun getSpotifyArtist(artistId: String): String = io("getSpotifyArtist") { put("id", artistId) }

    actual suspend fun getSpotifyArtistTopTracks(artistId: String, limit: Int, offset: Int): String =
        page("getSpotifyArtistTopTracks", limit, offset, artistId)

    actual suspend fun getSpotifyArtistAlbums(artistId: String, limit: Int, offset: Int): String =
        page("getSpotifyArtistAlbums", limit, offset, artistId)

    actual suspend fun getSpotifyRelatedArtists(artistId: String, limit: Int, offset: Int): String =
        page("getSpotifyRelatedArtists", limit, offset, artistId)

    actual suspend fun followSpotifyArtists(idsJson: String): String =
        io("followSpotifyArtists") { put("ids", json(idsJson)) }

    actual suspend fun unfollowSpotifyArtists(idsJson: String): String =
        io("unfollowSpotifyArtists") { put("ids", json(idsJson)) }

    // ── Spotify: playlists ──────────────────────────────────────────────────────────────────────

    actual suspend fun getSpotifyPlaylist(playlistId: String): String = io("getSpotifyPlaylist") { put("id", playlistId) }

    actual suspend fun getSpotifyPlaylistTracks(playlistId: String, limit: Int, offset: Int): String =
        page("getSpotifyPlaylistTracks", limit, offset, playlistId)

    actual suspend fun createSpotifyPlaylist(userId: String, name: String, description: String, isPublic: Boolean): String =
        io("createSpotifyPlaylist") {
            put("userId", userId)
            put("name", name)
            put("description", description)
            put("public", isPublic)
        }

    actual suspend fun updateSpotifyPlaylist(playlistId: String, name: String, description: String): String =
        io("updateSpotifyPlaylist") {
            put("id", playlistId)
            put("name", name)
            put("description", description)
        }

    actual suspend fun addTracksToPlaylist(playlistId: String, trackIdsJson: String, position: Int): String =
        io("addTracksToPlaylist") {
            put("id", playlistId)
            put("trackIds", json(trackIdsJson))
            put("position", position)
        }

    actual suspend fun removeTracksFromPlaylist(playlistId: String, trackIdsJson: String): String =
        io("removeTracksFromPlaylist") {
            put("id", playlistId)
            put("trackIds", json(trackIdsJson))
        }

    actual suspend fun followPlaylist(playlistId: String): String = io("followPlaylist") { put("id", playlistId) }

    actual suspend fun unfollowPlaylist(playlistId: String): String = io("unfollowPlaylist") { put("id", playlistId) }

    // ── Spotify: tracks ─────────────────────────────────────────────────────────────────────────

    actual suspend fun getSpotifyTrack(trackId: String): String = io("getSpotifyTrack") { put("id", trackId) }

    actual suspend fun saveSpotifyTracks(idsJson: String): String = io("saveSpotifyTracks") { put("ids", json(idsJson)) }

    actual suspend fun unsaveSpotifyTracks(idsJson: String): String =
        io("unsaveSpotifyTracks") { put("ids", json(idsJson)) }

    actual suspend fun getSpotifyTrackRadio(trackId: String): String = io("getSpotifyTrackRadio") { put("id", trackId) }

    actual suspend fun getArtistAbout(artistId: String): String = io("getArtistAbout") { put("id", artistId) }

    actual suspend fun getTrackCredits(trackId: String): String = io("getTrackCredits") { put("id", trackId) }

    actual suspend fun extendPlaylist(playlistId: String, skipJson: String, count: Int): String = io("extendPlaylist") {
        put("id", playlistId)
        put("skip", json(skipJson))
        put("count", count)
    }

    actual suspend fun getSpotifyCanvas(trackUri: String): String = io("getSpotifyCanvas") { put("uri", trackUri) }

    // ── Spotify: search and browse ──────────────────────────────────────────────────────────────

    actual suspend fun searchSpotify(query: String, searchType: String, limit: Int, offset: Int): String =
        io("searchSpotify") {
            put("query", query)
            put("type", searchType)
            put("limit", limit)
            put("offset", offset)
        }

    actual suspend fun getSpotifyBrowse(limit: Int): String = io("getSpotifyBrowse") { put("limit", limit) }

    actual suspend fun initSpotifyCacheDir(cacheDir: String) {
        io("initSpotifyCacheDir") { put("dir", cacheDir) }
    }

    actual fun warmupSpotifyHashesNative() {
        now("warmupSpotifyHashes")
    }

    actual fun getSpotifyHashesNative(): String = now("getSpotifyHashes")

    // ── YouTube Music ───────────────────────────────────────────────────────────────────────────

    actual suspend fun searchYtMusic(query: String): String = io("searchYtMusic") { put("query", query) }

    actual suspend fun getYtmAlbum(browseId: String): String = io("getYtmAlbum") { put("id", browseId) }

    actual suspend fun getYtmArtist(channelId: String): String = io("getYtmArtist") { put("id", channelId) }

    actual suspend fun getYtmPlaylist(playlistId: String): String = io("getYtmPlaylist") { put("id", playlistId) }

    /** A paged Spotify read: `{limit, offset}`, plus the `id` of what is paged when there is one. */
    private suspend fun page(method: String, limit: Int, offset: Int, id: String? = null): String = io(method) {
        if (id != null) put("id", id)
        put("limit", limit)
        put("offset", offset)
    }

    // ── Audio lab (4.7.1) ────────────────────────────────────────────────────────────────────────

    actual fun dspAnalyzeNative(samples: FloatArray, sampleRate: Int, bands: Int, kernel: String): FloatArray? {
        val raw = now("dspAnalyze") {
            put("samples", JsonArray(samples.map { JsonPrimitive(if (it.isFinite()) it else 0f) }))
            put("sampleRate", sampleRate)
            put("bands", bands)
            put("kernel", kernel)
        }
        return parseFloats(raw)
    }

    actual fun dspInfoNative(): String = now("dspInfo")

    actual fun dspBenchNative(n: Int, iterations: Int): String = now("dspBench") {
        put("n", n)
        put("iterations", iterations)
    }

    actual fun dspTonalNative(samples: FloatArray, sampleRate: Int, reset: Boolean): FloatArray? {
        val raw = now("dspTonal") {
            put("samples", JsonArray(samples.map { JsonPrimitive(if (it.isFinite()) it else 0f) }))
            put("sampleRate", sampleRate)
            put("reset", reset)
        }
        return parseFloats(raw)
    }

    actual fun dspMatBenchNative(n: Int, iterations: Int): String = now("dspMatBench") {
        put("n", n)
        put("iterations", iterations)
    }

    actual fun engineTapNative(n: Int): String = now("engineTap") {
        put("n", n)
    }

    private fun parseFloats(raw: String): FloatArray? {
        if (raw.isEmpty() || raw == "null") return null
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            if (element is JsonArray) {
                FloatArray(element.size) { i ->
                    (element[i] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
