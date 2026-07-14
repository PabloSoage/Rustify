package com.varuna.rustify.bridge

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import com.varuna.rustify.bridge.maximiseThumbnail
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * E40 — Repositorio de YouTube Music. Persiste favoritos, playlists, álbumes y
 * artistas YTM guardados localmente en `filesDir/ytm_library.json`. La resolución
 * de datos vivos (búsqueda, navegación) se hace vía JNI → [NativeEngine].
 */
class YtMusicRepository(private val appContext: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val libFile get() = File(appContext.filesDir, "ytm_library.json")

    val favorites  = mutableStateListOf<YtmTrack>()
    val playlists  = mutableStateListOf<YtmLocalPlaylist>()
    val savedAlbums  = mutableStateListOf<YtmAlbumSlim>()
    val savedArtists = mutableStateListOf<YtmArtistRef>()

    init { scope.launch { loadLibrary() } }

    fun reloadLibrary() { scope.launch { loadLibrary() } }

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
    }

    fun createPlaylist(name: String): YtmLocalPlaylist {
        val pl = YtmLocalPlaylist(localId = "ytmpl:${java.util.UUID.randomUUID()}", name = name, items = emptyList(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        playlists.add(pl)
        scope.launch { saveLibrary() }
        return pl
    }

    fun addToPlaylist(localId: String, track: YtmTrack) {
        val i = playlists.indexOfFirst { it.localId == localId }.takeIf { it >= 0 } ?: return
        playlists[i] = playlists[i].copy(items = playlists[i].items + track, updatedAt = System.currentTimeMillis())
        scope.launch { saveLibrary() }
    }

    fun removeFromPlaylist(localId: String, videoId: String) {
        val i = playlists.indexOfFirst { it.localId == localId }.takeIf { it >= 0 } ?: return
        playlists[i] = playlists[i].copy(items = playlists[i].items.filter { it.videoId != videoId }, updatedAt = System.currentTimeMillis())
        scope.launch { saveLibrary() }
    }

    fun deletePlaylist(localId: String) { playlists.removeAll { it.localId == localId }; scope.launch { saveLibrary() } }

    fun isAlbumSaved(browseId: String) = savedAlbums.any { it.browseId == browseId }
    fun toggleSavedAlbum(album: YtmAlbumSlim) {
        val idx = savedAlbums.indexOfFirst { it.browseId == album.browseId }
        if (idx >= 0) savedAlbums.removeAt(idx) else savedAlbums.add(album)
        scope.launch { saveLibrary() }
    }

    fun isArtistSaved(channelId: String) = savedArtists.any { it.id == channelId }
    fun toggleSavedArtist(artist: YtmArtistRef) {
        val idx = savedArtists.indexOfFirst { it.id == artist.id }
        if (idx >= 0) savedArtists.removeAt(idx) else savedArtists.add(artist)
        scope.launch { saveLibrary() }
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