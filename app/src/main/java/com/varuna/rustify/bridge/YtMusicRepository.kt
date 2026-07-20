package com.varuna.rustify.bridge

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import com.varuna.rustify.bridge.maximiseThumbnail
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** E105 — Una fila del home de YTM: título de la categoría, la query que la generó y sus pistas. */
data class YtmHomeRow(val title: String, val query: String, val tracks: List<YtmTrack>)

/**
 * E40 — Repositorio de YouTube Music. Persiste favoritos, playlists, álbumes y
 * artistas YTM guardados localmente en `filesDir/ytm_library.json`. La resolución
 * de datos vivos (búsqueda, navegación) se hace vía JNI → [NativeEngine].
 */
class YtMusicRepository(private val appContext: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val libFile get() = File(appContext.filesDir, "ytm_library.json")

    // El estado de la biblioteca es COMPARTIDO entre todas las instancias (la app crea varias y el
    // servicio de Android Auto crea la suya). Si cada una tuviera su propia lista, un favorito
    // añadido en la app no se vería en el coche (cada instancia cargaría su snapshot del JSON y nunca
    // se enteraría). Compartiendo las listas, cualquier instancia vé los cambios al instante.
    val favorites  = State.favorites
    val playlists  = State.playlists
    val savedAlbums  = State.savedAlbums
    val savedArtists = State.savedArtists
    // E105 — Home feed cacheado (secciones dinámicas por mood/género vía search, con TTL en disco).
    val homeRows = State.homeRows
    val homeFetchedAt: Long get() = State.homeFetchedAt

    init {
        synchronized(State) {
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
            @Volatile var homeFetchedAt = 0L
            @Volatile var homeLoading = false
            @Volatile var loaded = false
        }
    }

    fun reloadLibrary() { scope.launch { loadLibrary(); com.varuna.rustify.player.MediaBrowserNotifier.notifyLibraryChanged() } }

    private suspend fun loadLibrary() = withContext(Dispatchers.IO) {
        runCatching {
            if (!libFile.exists()) return@withContext
            val json = JSONObject(libFile.readText())
            withContext(Dispatchers.Main) {
                favorites.clear(); favorites.addAll(YtmTrack.listFromJsonArray(json.optJSONArray("favorites")))
                playlists.clear(); playlists.addAll(YtmLocalPlaylist.listFromJsonArray(json.optJSONArray("playlists")))
                savedAlbums.clear(); savedAlbums.addAll(YtmAlbumSlim.listFromJsonArray(json.optJSONArray("savedAlbums")))
                savedArtists.clear(); savedArtists.addAll(YtmArtistRef.listFromJsonArray(json.optJSONArray("savedArtists")))
            }
        }
    }

    private suspend fun saveLibrary() = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().apply {
                put("version", 1)
                put("favorites",   YtmTrack.toJsonArray(favorites.toList()))
                put("playlists",   YtmLocalPlaylist.toJsonArray(playlists.toList()))
                put("savedAlbums", YtmAlbumSlim.toJsonArray(savedAlbums.toList()))
                put("savedArtists",YtmArtistRef.toJsonArray(savedArtists.toList()))
            }
            // Atomic write (tmp + rename) so a crash mid-write can't corrupt the library.
            val tmp = File(appContext.filesDir, "ytm_library.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(libFile)) {
                // Fallback: rename can fail across some FS states; copy then delete tmp.
                libFile.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    fun isFavorite(videoId: String) = favorites.any { it.videoId == videoId }

    fun toggleFavorite(track: YtmTrack) {
        val idx = favorites.indexOfFirst { it.videoId == track.videoId }
        if (idx >= 0) favorites.removeAt(idx) else favorites.add(track)
        scope.launch { saveLibrary() }
        com.varuna.rustify.player.MediaBrowserNotifier.notifyLibraryChanged()
    }

    fun createPlaylist(name: String): YtmLocalPlaylist {
        val pl = YtmLocalPlaylist(localId = "ytmpl:${java.util.UUID.randomUUID()}", name = name, items = emptyList(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        playlists.add(pl)
        scope.launch { saveLibrary() }
        com.varuna.rustify.player.MediaBrowserNotifier.notifyLibraryChanged()
        return pl
    }

    fun addToPlaylist(localId: String, track: YtmTrack) {
        val i = playlists.indexOfFirst { it.localId == localId }.takeIf { it >= 0 } ?: return
        playlists[i] = playlists[i].copy(items = playlists[i].items + track, updatedAt = System.currentTimeMillis())
        scope.launch { saveLibrary() }
        com.varuna.rustify.player.MediaBrowserNotifier.notifyLibraryChanged()
    }

    fun removeFromPlaylist(localId: String, videoId: String) {
        val i = playlists.indexOfFirst { it.localId == localId }.takeIf { it >= 0 } ?: return
        playlists[i] = playlists[i].copy(items = playlists[i].items.filter { it.videoId != videoId }, updatedAt = System.currentTimeMillis())
        scope.launch { saveLibrary() }
        com.varuna.rustify.player.MediaBrowserNotifier.notifyLibraryChanged()
    }

    fun deletePlaylist(localId: String) { playlists.removeAll { it.localId == localId }; scope.launch { saveLibrary() }; com.varuna.rustify.player.MediaBrowserNotifier.notifyLibraryChanged() }

    fun isAlbumSaved(browseId: String) = savedAlbums.any { it.browseId == browseId }
    fun toggleSavedAlbum(album: YtmAlbumSlim) {
        val idx = savedAlbums.indexOfFirst { it.browseId == album.browseId }
        if (idx >= 0) savedAlbums.removeAt(idx) else savedAlbums.add(album)
        scope.launch { saveLibrary() }
        com.varuna.rustify.player.MediaBrowserNotifier.notifyLibraryChanged()
    }

    fun isArtistSaved(channelId: String) = savedArtists.any { it.id == channelId }
    fun toggleSavedArtist(artist: YtmArtistRef) {
        val idx = savedArtists.indexOfFirst { it.id == artist.id }
        if (idx >= 0) savedArtists.removeAt(idx) else savedArtists.add(artist)
        scope.launch { saveLibrary() }
        com.varuna.rustify.player.MediaBrowserNotifier.notifyLibraryChanged()
    }

    // All native calls are wrapped in try/catch: a malformed / non-JSON payload from the
    // JNI layer must degrade to an empty result (never crash the UI).
    private val emptyResults = YtmSearchResults(emptyList(), emptyList(), emptyList(), emptyList())

    suspend fun search(query: String): YtmSearchResults = withContext(Dispatchers.IO) {
        runCatching {
            YtmSearchResults.fromJson(JSONObject(NativeEngine.searchYtMusicNative(query)))
        }.getOrDefault(emptyResults)
    }

    /** Search via the YouTube scraper (general YouTube, includes unofficial/covers). */
    suspend fun searchScraper(query: String): YtmSearchResults = withContext(Dispatchers.IO) {
        runCatching {
            val raw = NativeEngine.searchYouTubeNative(query)
            // The scraper returns a plain JSON array: [{id, title, author, duration_sec, thumbnail_url}]
            val data = JSONArray(raw)
            val tracks = (0 until data.length()).map { i ->
                val o = data.getJSONObject(i)
                YtmTrack(
                    videoId = o.optString("id", ""),
                    title = o.optString("title", ""),
                    artists = listOf(YtmArtistRef("", o.optString("author", ""))),
                    albumId = null,
                    durationSec = o.optInt("duration_sec", 0),
                    thumbnailUrl = maximiseThumbnail(o.optString("thumbnail_url", ""))
                )
            }
            YtmSearchResults(tracks, emptyList(), emptyList(), emptyList())
        }.getOrDefault(emptyResults)
    }

    suspend fun getAlbum(browseId: String): YtmAlbum? = withContext(Dispatchers.IO) {
        runCatching {
            val json = NativeEngine.getYtmAlbumNative(browseId)
            if (json == "null" || json.isBlank()) null else YtmAlbum.fromJson(JSONObject(json))
        }.getOrNull()
    }

    suspend fun getArtist(channelId: String): YtmArtist? = withContext(Dispatchers.IO) {
        runCatching {
            val json = NativeEngine.getYtmArtistNative(channelId)
            if (json == "null" || json.isBlank()) null else YtmArtist.fromJson(JSONObject(json))
        }.getOrNull()
    }

    suspend fun getPlaylist(playlistId: String): YtmPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            val json = NativeEngine.getYtmPlaylistNative(playlistId)
            if (json == "null" || json.isBlank()) null else YtmPlaylist.fromJson(JSONObject(json))
        }.getOrNull()
    }

    suspend fun getRadio(videoId: String): List<YtmTrack> = withContext(Dispatchers.IO) {
        runCatching {
            YtmTrack.listFromJsonArray(JSONArray(NativeEngine.getYtmRadioNative(videoId)))
        }.getOrDefault(emptyList())
    }

    // ── E105 — Home feed cacheado (arregla el "carga cada vez / secciones estáticas") ──────────────
    private val homeFile get() = File(appContext.filesDir, "ytm_home_cache.json")
    private val homeTtlMs = 8 * 60 * 60 * 1000L // 8h

    /** Categorías del home (título mostrado, query de búsqueda). Mezcla moods y géneros populares. */
    private val homeCategories = listOf(
        "Top hits" to "top hits", "New music" to "new music this week", "Chill" to "chill lofi relax",
        "Workout" to "energetic workout gym", "Focus" to "focus instrumental study",
        "Feel good" to "feel good happy hits", "Party" to "party dance hits", "Sleep" to "sleep calm ambient",
        "Romance" to "love romantic songs", "Latin" to "latin hits reggaeton", "Rock" to "rock classics",
        "Hip-Hop" to "hip hop rap hits", "R&B" to "rnb soul hits", "Electronic" to "electronic edm hits",
        "Throwback" to "2010s throwback hits"
    )

    /**
     * Asegura que [homeRows] esté poblado: usa la caché en disco si es fresca (< 8h), y solo si no hay
     * o [force] es true reconstruye el feed lanzando las búsquedas EN PARALELO (rápido) y lo guarda.
     */
    suspend fun ensureHome(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (State.homeLoading) return@withContext
        val fresh = System.currentTimeMillis() - State.homeFetchedAt < homeTtlMs
        if (!force) {
            if (State.homeRows.isNotEmpty() && fresh) return@withContext
            if (State.homeRows.isEmpty()) {
                loadHomeCache()
                if (State.homeRows.isNotEmpty() && System.currentTimeMillis() - State.homeFetchedAt < homeTtlMs) return@withContext
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
                val now = System.currentTimeMillis()
                withContext(Dispatchers.Main) { State.homeRows.clear(); State.homeRows.addAll(rows) }
                State.homeFetchedAt = now
                saveHomeCache(rows, now)
            }
        } finally { State.homeLoading = false }
    }

    private suspend fun loadHomeCache() = withContext(Dispatchers.IO) {
        runCatching {
            if (!homeFile.exists()) return@withContext
            val json = JSONObject(homeFile.readText())
            val fetchedAt = json.optLong("fetchedAt", 0L)
            val arr = json.optJSONArray("rows") ?: return@withContext
            val rows = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                YtmHomeRow(o.optString("title"), o.optString("query"), YtmTrack.listFromJsonArray(o.optJSONArray("tracks")))
            }.filter { it.tracks.isNotEmpty() }
            withContext(Dispatchers.Main) { State.homeRows.clear(); State.homeRows.addAll(rows) }
            State.homeFetchedAt = fetchedAt
        }
    }

    private fun saveHomeCache(rows: List<YtmHomeRow>, fetchedAt: Long) {
        runCatching {
            val arr = JSONArray()
            rows.forEach { r ->
                arr.put(JSONObject().apply {
                    put("title", r.title); put("query", r.query); put("tracks", YtmTrack.toJsonArray(r.tracks))
                })
            }
            val json = JSONObject().apply { put("fetchedAt", fetchedAt); put("rows", arr) }
            val tmp = File(appContext.filesDir, "ytm_home_cache.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(homeFile)) { homeFile.writeText(tmp.readText()); tmp.delete() }
        }
    }

    /** Local playlist lookup for the detail screen. */
    fun localPlaylist(localId: String): YtmLocalPlaylist? = playlists.firstOrNull { it.localId == localId }

    /** Serialize the whole local library to JSON (for export, E50). */
    fun exportJson(): String = JSONObject().apply {
        put("version", 1)
        put("favorites",   YtmTrack.toJsonArray(favorites.toList()))
        put("playlists",   YtmLocalPlaylist.toJsonArray(playlists.toList()))
        put("savedAlbums", YtmAlbumSlim.toJsonArray(savedAlbums.toList()))
        put("savedArtists",YtmArtistRef.toJsonArray(savedArtists.toList()))
    }.toString()
}