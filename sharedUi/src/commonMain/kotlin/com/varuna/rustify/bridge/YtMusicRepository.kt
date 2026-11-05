package com.varuna.rustify.bridge


import androidx.compose.runtime.mutableStateListOf
import com.varuna.rustify.platform.currentTimeMillis
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.readTextOrNull
import com.varuna.rustify.ui.writeTextAtomically
import com.varuna.rustify.util.Lock
import com.varuna.rustify.util.locked
import com.varuna.rustify.util.optArray
import com.varuna.rustify.util.optInt
import com.varuna.rustify.util.optLong
import com.varuna.rustify.util.optObject
import com.varuna.rustify.util.optString
import com.varuna.rustify.util.parseArray
import com.varuna.rustify.util.parseObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.varuna.rustify.platform.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A YTM home row: the category title, the query that generated it, and its tracks. */
data class YtmHomeRow(val title: String, val query: String, val tracks: List<YtmTrack>)

/**
 * YouTube Music repository. Persists saved YTM favorites, playlists, albums and artists locally in
 * `filesDir/ytm_library.json`. Live data resolution (search, browsing) is performed via JNI through
 * [NativeEngine].
 */
class YtMusicRepository(private val filesDir: AppFile) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val libFile get() = filesDir.child("ytm_library.json")

    // The library state is SHARED across all instances (the app creates several and the Android Auto
    // service creates its own). If each had its own list, a favorite added in the app would not show
    // up in the car (each instance would load its own JSON snapshot and never notice). By sharing the
    // lists, any instance sees changes instantly.
    val favorites  = State.favorites
    val playlists  = State.playlists
    val savedAlbums  = State.savedAlbums
    val savedArtists = State.savedArtists
    // Cached home feed (dynamic mood/genre sections via search, with an on-disk TTL).
    val homeRows = State.homeRows

    init {
        State.lock.locked {
            if (!State.loaded) { State.loaded = true; scope.launch { loadLibrary() } }
        }
    }

    companion object {
        private object State {
            val favorites  = mutableStateListOf<YtmTrack>()
            val playlists  = mutableStateListOf<YtmLocalPlaylist>()
            val savedAlbums  = mutableStateListOf<YtmAlbumSlim>()
            val savedArtists = mutableStateListOf<YtmArtistRef>()
            val homeRows = mutableStateListOf<YtmHomeRow>()
            val lock = Lock()
            @Volatile var homeFetchedAt = 0L
            @Volatile var homeLoading = false
            @Volatile var loaded = false
        }
    }

    fun reloadLibrary() { scope.launch { loadLibrary(); LibraryChanges.notifyChanged() } }

    private suspend fun loadLibrary() = withContext(Dispatchers.IO) {
        runCatching {
            val json = parseObject(libFile.readTextOrNull() ?: return@withContext)
            withContext(Dispatchers.Main) {
                favorites.clear(); favorites.addAll(wireList<YtmTrack>(json["favorites"]))
                playlists.clear(); playlists.addAll(wireList<YtmLocalPlaylist>(json["playlists"]))
                savedAlbums.clear(); savedAlbums.addAll(wireList<YtmAlbumSlim>(json["savedAlbums"]))
                savedArtists.clear(); savedArtists.addAll(wireList<YtmArtistRef>(json["savedArtists"]))
            }
        }
    }

    private suspend fun saveLibrary() = withContext(Dispatchers.IO) {
        runCatching {
            // Atomic write (tmp + rename) so a crash mid-write can't corrupt the library; when the
            // rename fails across some FS states, written in place instead.
            libFile.writeTextAtomically(libraryJson())
        }
    }

    fun isFavorite(videoId: String) = favorites.any { it.videoId == videoId }

    fun toggleFavorite(track: YtmTrack) {
        val idx = favorites.indexOfFirst { it.videoId == track.videoId }
        if (idx >= 0) favorites.removeAt(idx) else favorites.add(track)
        scope.launch { saveLibrary() }
        LibraryChanges.notifyChanged()
    }

    // Kotlin's Uuid prints as java.util.UUID did: 36 lowercase characters, version 4.
    @OptIn(ExperimentalUuidApi::class)
    fun createPlaylist(name: String): YtmLocalPlaylist {
        val pl = YtmLocalPlaylist(localId = "ytmpl:${Uuid.random()}", name = name, items = emptyList(), createdAt = currentTimeMillis(), updatedAt = currentTimeMillis())
        playlists.add(pl)
        scope.launch { saveLibrary() }
        LibraryChanges.notifyChanged()
        return pl
    }

    fun addToPlaylist(localId: String, track: YtmTrack) {
        val i = playlists.indexOfFirst { it.localId == localId }.takeIf { it >= 0 } ?: return
        playlists[i] = playlists[i].copy(items = playlists[i].items + track, updatedAt = currentTimeMillis())
        scope.launch { saveLibrary() }
        LibraryChanges.notifyChanged()
    }

    fun deletePlaylist(localId: String) { playlists.removeAll { it.localId == localId }; scope.launch { saveLibrary() }; LibraryChanges.notifyChanged() }

    fun isAlbumSaved(browseId: String) = savedAlbums.any { it.browseId == browseId }
    fun toggleSavedAlbum(album: YtmAlbumSlim) {
        val idx = savedAlbums.indexOfFirst { it.browseId == album.browseId }
        if (idx >= 0) savedAlbums.removeAt(idx) else savedAlbums.add(album)
        scope.launch { saveLibrary() }
        LibraryChanges.notifyChanged()
    }

    fun isArtistSaved(channelId: String) = savedArtists.any { it.id == channelId }
    fun toggleSavedArtist(artist: YtmArtistRef) {
        val idx = savedArtists.indexOfFirst { it.id == artist.id }
        if (idx >= 0) savedArtists.removeAt(idx) else savedArtists.add(artist)
        scope.launch { saveLibrary() }
        LibraryChanges.notifyChanged()
    }

    // All native calls are wrapped in try/catch: a malformed / non-JSON payload from the
    // JNI layer must degrade to an empty result (never crash the UI).
    private val emptyResults = YtmSearchResults(emptyList(), emptyList(), emptyList(), emptyList())

    suspend fun search(query: String): YtmSearchResults = withContext(Dispatchers.IO) {
        runCatching {
            decodeWire<YtmSearchResults>(NativeEngine.searchYtMusic(query))
        }.getOrDefault(emptyResults)
    }

    /** Search via the YouTube scraper (general YouTube, includes unofficial/covers). */
    suspend fun searchScraper(query: String): YtmSearchResults = withContext(Dispatchers.IO) {
        runCatching {
            val raw = NativeEngine.searchYouTube(query)
            // The scraper returns a plain JSON array: [{id, title, author, duration_sec, thumbnail_url}]
            val data = parseArray(raw)
            val tracks = data.map { element ->
                val o = element as JsonObject
                YtmTrack(
                    videoId = o["id"].optString(),
                    title = o["title"].optString(),
                    artists = listOf(YtmArtistRef("", o["author"].optString())),
                    albumId = null,
                    durationSec = o["duration_sec"].optInt(),
                    thumbnailUrl = maximiseThumbnail(o["thumbnail_url"].optString())
                )
            }
            YtmSearchResults(tracks, emptyList(), emptyList(), emptyList())
        }.getOrDefault(emptyResults)
    }

    suspend fun getAlbum(browseId: String): YtmAlbum? = withContext(Dispatchers.IO) {
        runCatching {
            val json = NativeEngine.getYtmAlbum(browseId)
            if (json == "null" || json.isBlank()) null else decodeWire<YtmAlbum>(json)
        }.getOrNull()
    }

    suspend fun getArtist(channelId: String): YtmArtist? = withContext(Dispatchers.IO) {
        runCatching {
            val json = NativeEngine.getYtmArtist(channelId)
            if (json == "null" || json.isBlank()) null else decodeWire<YtmArtist>(json)
        }.getOrNull()
    }

    suspend fun getPlaylist(playlistId: String): YtmPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            val json = NativeEngine.getYtmPlaylist(playlistId)
            if (json == "null" || json.isBlank()) null else decodeWire<YtmPlaylist>(json)
        }.getOrNull()
    }

    // ── Cached home feed ───────────────────────────────────────────────────────────────────────────
    private val homeFile get() = filesDir.child("ytm_home_cache.json")
    private val homeTtlMs = 8 * 60 * 60 * 1000L // 8h

    /** Home categories (displayed title, search query). Mixes popular moods and genres. */
    private val homeCategories = listOf(
        "Top hits" to "top hits", "New music" to "new music this week", "Chill" to "chill lofi relax",
        "Workout" to "energetic workout gym", "Focus" to "focus instrumental study",
        "Feel good" to "feel good happy hits", "Party" to "party dance hits", "Sleep" to "sleep calm ambient",
        "Romance" to "love romantic songs", "Latin" to "latin hits reggaeton", "Rock" to "rock classics",
        "Hip-Hop" to "hip hop rap hits", "R&B" to "rnb soul hits", "Electronic" to "electronic edm hits",
        "Throwback" to "2010s throwback hits"
    )

    /**
     * Ensures [homeRows] is populated: uses the on-disk cache if it is fresh (< 8h), and only when it
     * is empty or [force] is true rebuilds the feed by running the searches in PARALLEL and saves it.
     */
    suspend fun ensureHome(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (State.homeLoading) return@withContext
        val fresh = currentTimeMillis() - State.homeFetchedAt < homeTtlMs
        if (!force) {
            if (State.homeRows.isNotEmpty() && fresh) return@withContext
            if (State.homeRows.isEmpty()) {
                loadHomeCache()
                if (State.homeRows.isNotEmpty() && currentTimeMillis() - State.homeFetchedAt < homeTtlMs) return@withContext
            }
        }
        State.homeLoading = true
        try {
            val rows = coroutineScope {
                homeCategories.map { (title, q) ->
                    async { YtmHomeRow(title, q, runCatching { search(q).tracks.take(16) }.getOrDefault(emptyList())) }
                }.awaitAll()
            }.filter { it.tracks.isNotEmpty() }
            if (rows.isNotEmpty()) {
                val now = currentTimeMillis()
                withContext(Dispatchers.Main) { State.homeRows.clear(); State.homeRows.addAll(rows) }
                State.homeFetchedAt = now
                saveHomeCache(rows, now)
            }
        } finally { State.homeLoading = false }
    }

    private suspend fun loadHomeCache() = withContext(Dispatchers.IO) {
        runCatching {
            val json = parseObject(homeFile.readTextOrNull() ?: return@withContext)
            val fetchedAt = json["fetchedAt"].optLong(0L)
            val arr = json["rows"].optArray() ?: return@withContext
            val rows = arr.mapNotNull { element ->
                val o = element.optObject() ?: return@mapNotNull null
                YtmHomeRow(o["title"].optString(), o["query"].optString(), wireList<YtmTrack>(o["tracks"]))
            }.filter { it.tracks.isNotEmpty() }
            withContext(Dispatchers.Main) { State.homeRows.clear(); State.homeRows.addAll(rows) }
            State.homeFetchedAt = fetchedAt
        }
    }

    private fun saveHomeCache(rows: List<YtmHomeRow>, fetchedAt: Long) {
        runCatching {
            val arr = buildJsonArray {
                rows.forEach { r ->
                    add(buildJsonObject {
                        put("title", r.title); put("query", r.query); put("tracks", wire(r.tracks))
                    })
                }
            }
            val json = buildJsonObject { put("fetchedAt", fetchedAt); put("rows", arr) }
            homeFile.writeTextAtomically(json.toString())
        }
    }

    /** Serialize the whole local library to JSON (for export). */
    fun exportJson(): String = libraryJson()

    /** `ytm_library.json`: what [saveLibrary] writes and [exportJson] hands out. */
    private fun libraryJson(): String = buildJsonObject {
        put("version", 1)
        put("favorites",   wire(favorites.toList()))
        put("playlists",   wire(playlists.toList()))
        put("savedAlbums", wire(savedAlbums.toList()))
        put("savedArtists",wire(savedArtists.toList()))
    }.toString()

    // The library's lists in their wire form, as the org.json helpers built them: through the
    // generated encoder and decoder, element for element.
    private inline fun <reified T> wire(values: List<T>): JsonElement = RustifyJson.parseToJsonElement(encodeWireString(values))

    private inline fun <reified T> wireList(element: JsonElement?): List<T> =
        if (element is JsonArray) decodeWire(element.toString()) else emptyList()
}