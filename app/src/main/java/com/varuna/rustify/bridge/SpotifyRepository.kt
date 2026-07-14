// app/src/main/java/com/varuna/rustify/bridge/SpotifyRepository.kt
@file:Suppress("SpellCheckingInspection")
@file:SuppressLint("UseKtx")

package com.varuna.rustify.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.varuna.rustify.util.classifyError
import com.varuna.rustify.util.retrying
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exception thrown when a Rust engine operation fails.
 * Contains the error message from the native engine.
 */
class SpotifyEngineException(message: String) : Exception(message)

/**
 * High-level abstraction layer over the Rust Spotify engine.
 * Handles:
 * - Calling NativeEngine functions on Dispatchers.IO
 * - Parsing JSON responses into Kotlin data classes
 * - Detecting error responses from the engine and throwing SpotifyEngineException
 * - Persisting the sp_dc cookie in SharedPreferences for session restoration
 * - Auto-restoring sessions on app startup
 *
 * Usage:
 *   val repo = SpotifyRepository(context)
 *   val result = repo.login(spDcCookie)
 *   val tracks = repo.getSavedTracks(20, 0)
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
@SuppressLint("StaticFieldLeak")
class SpotifyRepository(context: Context) {
    companion object {
        private const val PREFS_NAME = "rustify_spotify_prefs"
        private const val KEY_SP_DC = "sp_dc_cookie"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_EXPIRATION = "expiration_timestamp"

        @Volatile
        // internal (not private): the Android Auto MediaLibrarySession (RustifyForegroundService, E96)
        // reads the live repo to build its browsable tree.
        internal var instance: SpotifyRepository? = null

        // Local music bridge: temporary storage for local album/artist tracks
        // Used to pass local track data to AlbumScreen/ArtistScreen without API calls
val localAlbumTracks = mutableMapOf<String, List<FullTrack>>()
    val localArtistTracks = mutableMapOf<String, List<FullTrack>>()
    // E30: caché de tracks resueltos para una playlist local (patrón de localAlbumTracks).
    // Accedida desde el hilo main (UI: PlaylistScreen/LibraryScreen) y desde IO → wrap
    // sincronizado para evitar carreras al leer/escribir/limpiar el mapa concurrentemente.
    val localPlaylistTracksCache: MutableMap<String, List<FullTrack>> =
        java.util.Collections.synchronizedMap(mutableMapOf())

        /**
         * Normalize a name for comparison: trim, lowercase, strip featuring tags.
         */
        private fun normalizeName(name: String): String {
            return name.trim().lowercase()
                .replace(Regex("""\s*[(\[].*?feat\..*?[)\]]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*[(\[].*?featuring.*?[)\]]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        fun findLocalMatch(context: Context, track: FullTrack): FullTrack? {
            // First try the in-memory localTracks from the repository instance
            val repo = instance ?: return findLocalMatchFromDisk(context, track)
            return repo.findLocalMatch(track)
        }

        private var memoryCacheLocalTracks: List<FullTrack>? = null

        private fun findLocalMatchFromDisk(context: Context, track: FullTrack): FullTrack? {
            if (memoryCacheLocalTracks == null) {
                val localCacheFile = java.io.File(context.filesDir, "local_music_cache.json")
                if (!localCacheFile.exists()) return null
                try {
                    val jsonStr = localCacheFile.readText()
                    val array = org.json.JSONArray(jsonStr)
                    val tracks = mutableListOf<FullTrack>()
                    for (i in 0 until array.length()) {
                        tracks.add(FullTrack.fromJson(array.getJSONObject(i)))
                    }
                    memoryCacheLocalTracks = tracks
                } catch (e: Exception) {
                    return null
                }
            }
            return memoryCacheLocalTracks?.find { isLocalMatch(track, it) }
        }

        /**
         * Conservative local match: high certainty only.
         */
        private fun isLocalMatch(spotifyTrack: FullTrack, localTrack: FullTrack): Boolean {
            // 1. ISRC match (maximum certainty)
            val spotifyIsrc = spotifyTrack.isrc.trim()
            val localIsrc = localTrack.isrc.trim()
            if (spotifyIsrc.isNotBlank() && localIsrc.isNotBlank() && spotifyIsrc == localIsrc) {
                return true
            }

            // 2. Name must match (after normalization)
            val spotifyName = normalizeName(spotifyTrack.name)
            val localName = normalizeName(localTrack.name)
            if (spotifyName.isBlank() || localName.isBlank()) return false
            if (spotifyName != localName) return false

            // 3. Duration validation: if both have duration, they must be within ±5s
            if (spotifyTrack.durationMs > 0 && localTrack.durationMs > 0) {
                val diff = kotlin.math.abs(spotifyTrack.durationMs - localTrack.durationMs)
                if (diff > 5000) return false
            }

            // 4. Artist matching: at least one artist must match across all artists
            val spotifyArtists = spotifyTrack.artists.map { normalizeName(it.name) }.toSet()
            val localArtists = localTrack.artists.map { normalizeName(it.name) }.toSet()

            if (spotifyArtists.isEmpty() || localArtists.isEmpty()) return false

            // Check intersection of all artists
            val intersection = spotifyArtists.intersect(localArtists)
            if (intersection.isNotEmpty()) return true

            // Fallback: check if any spotify artist contains or is contained by any local artist
            for (sa in spotifyArtists) {
                for (la in localArtists) {
                    if (sa.contains(la) || la.contains(sa)) return true
                }
            }

            return false
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val likedTrackIds = mutableStateMapOf<String, Boolean>()

    private val appCtx = context.applicationContext
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    val likedTracks = androidx.compose.runtime.mutableStateListOf<FullTrack>()
    var isSyncingLikedTracks by mutableStateOf(false)
        private set

    val localTracks = androidx.compose.runtime.mutableStateListOf<FullTrack>()
    var isScanningLocalTracks by mutableStateOf(false)
        private set

    // E30 — Playlists y favoritos locales (JSON en filesDir, ids "local:...").
    val localPlaylists = androidx.compose.runtime.mutableStateListOf<LocalPlaylist>()
    private val localFavoriteIds = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    val savedPlaylists = androidx.compose.runtime.mutableStateListOf<SimplePlaylist>()
    var isSyncingPlaylists by mutableStateOf(false)
        private set

    val savedAlbums = androidx.compose.runtime.mutableStateListOf<FullAlbum>()
    var isSyncingAlbums by mutableStateOf(false)
        private set

    val followedArtists = androidx.compose.runtime.mutableStateListOf<FullArtist>()
    var isSyncingArtists by mutableStateOf(false)
        private set

    private fun getLocalTracksCacheFile(): java.io.File {
        return java.io.File(appCtx.filesDir, "local_music_cache.json")
    }

    private fun getLikedTracksCacheFile(): java.io.File {
        return java.io.File(appCtx.filesDir, "spotify_liked_tracks_cache.json")
    }

    // E30 — ficheros de datos locales (playlists + favoritos).
    private fun getLocalPlaylistsFile(): java.io.File =
        java.io.File(appCtx.filesDir, "local_playlists.json")

    private fun getLocalFavoritesFile(): java.io.File =
        java.io.File(appCtx.filesDir, "local_favorites.json")

    private fun loadLocalUserData() {
        runCatching {
            val f = getLocalPlaylistsFile()
            if (f.exists()) {
                val list = LocalPlaylist.listFromJsonArray(JSONArray(f.readText()))
                repositoryScope.launch(Dispatchers.Main) {
                    localPlaylists.clear()
                    localPlaylists.addAll(list)
                }
            }
        }
        runCatching {
            val f = getLocalFavoritesFile()
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                val ids = (0 until arr.length()).map { arr.getString(it) }
                repositoryScope.launch(Dispatchers.Main) {
                    ids.forEach { localFavoriteIds[it] = true }
                }
            }
        }
    }

    /** E30 — recarga playlists + favoritos locales desde disco (tras un import). */
    fun reloadLocalUserData() {
        repositoryScope.launch(Dispatchers.IO) {
            // Las SnapshotStateList/Map deben mutarse en el hilo principal; sólo el I/O va a IO.
            withContext(Dispatchers.Main) {
                localPlaylists.clear()
                localFavoriteIds.clear()
                SpotifyRepository.localPlaylistTracksCache.clear()
            }
            loadLocalUserData()
        }
    }

    private fun saveLocalPlaylists() {
        // E30 fix: snapshot on the caller thread (avoids ConcurrentModification), then write off the
        // main thread. These were called from Compose click handlers → synchronous disk I/O on the UI
        // thread = jank/ANR (matches the "app freezes" report when mutating local playlists).
        val snapshot = localPlaylists.toList()
        repositoryScope.launch(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                atomicWrite(getLocalPlaylistsFile(), arr.toString())
            }
        }
    }

    private fun saveLocalFavorites() {
        val keys = localFavoriteIds.filterValues { it }.keys.toList()
        repositoryScope.launch(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                keys.forEach { arr.put(it) }
                atomicWrite(getLocalFavoritesFile(), arr.toString())
            }
        }
    }

    private fun atomicWrite(dst: java.io.File, content: String) {
        val tmp = java.io.File(dst.parentFile, dst.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(dst)) { dst.writeText(content); tmp.delete() }
    }

    private fun loadLikedTracksFromCache() {
        val file = getLikedTracksCacheFile()
        if (!file.exists()) return
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = file.readText()
                val array = JSONArray(jsonStr)
                
                // Fast path for first 50 items
                val initialBatchSize = kotlin.math.min(50, array.length())
                val initialTracks = mutableListOf<FullTrack>()
                for (i in 0 until initialBatchSize) {
                    initialTracks.add(FullTrack.fromJson(array.getJSONObject(i)))
                }
                
                withContext(Dispatchers.Main) {
                    likedTracks.clear()
                    likedTracks.addAll(initialTracks)
                    initialTracks.forEach { track ->
                        track.id?.let { likedTrackIds[it] = true }
                    }
                }
                
                // Lazy load the rest in batches
                if (array.length() > initialBatchSize) {
                    var currentIdx = initialBatchSize
                    while (currentIdx < array.length()) {
                        val batchSize = kotlin.math.min(200, array.length() - currentIdx)
                        val batchTracks = mutableListOf<FullTrack>()
                        for (i in 0 until batchSize) {
                            batchTracks.add(FullTrack.fromJson(array.getJSONObject(currentIdx + i)))
                        }
                        
                        withContext(Dispatchers.Main) {
                            likedTracks.addAll(batchTracks)
                            batchTracks.forEach { track ->
                                track.id?.let { likedTrackIds[it] = true }
                            }
                        }
                        currentIdx += batchSize
                        delay(10.milliseconds) // Yield UI thread
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveLikedTracksToCache() {
        val file = getLikedTracksCacheFile()
        try {
            val array = JSONArray()
            likedTracks.forEach { track ->
                array.put(track.toJson())
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerBackgroundSync() {
        repositoryScope.launch {
            if (isAuthenticated()) {
                syncLikedTracks()
                syncPlaylists()
                syncAlbums()
                syncArtists()
            }
        }
    }

    suspend fun syncLikedTracks() {
        if (isSyncingLikedTracks) return
        withContext(Dispatchers.Main) {
            isSyncingLikedTracks = true
        }
        try {
            withContext(Dispatchers.IO) {
                val response = getSavedTracks(limit = 50, offset = 0)
                if (likedTracks.isEmpty()) {
                    fetchAllSavedTracks()
                    return@withContext
                }

                val cachedFirstTrackId = likedTracks.firstOrNull()?.id
                val newFirstTrackId = response.items.firstOrNull()?.id

                if (cachedFirstTrackId == newFirstTrackId) {
                    if (response.total == likedTracks.size) {
                        return@withContext
                    } else {
                        fetchAllSavedTracks()
                        return@withContext
                    }
                }

                val newTracks = mutableListOf<FullTrack>()
                var currentOffset = 0
                var hasMore = true
                var foundMatchInCache = false

                while (hasMore) {
                    val page = getSavedTracks(limit = 50, offset = currentOffset)
                    if (page.items.isEmpty()) {
                        break
                    }

                    for (item in page.items) {
                        if (item.id == cachedFirstTrackId) {
                            foundMatchInCache = true
                            break
                        }
                        newTracks.add(item)
                    }

                    if (foundMatchInCache) {
                        break
                    }

                    if (page.hasMore) {
                        currentOffset += page.items.size
                    } else {
                        hasMore = false
                    }
                }

                if (foundMatchInCache) {
                    withContext(Dispatchers.Main) {
                        likedTracks.addAll(0, newTracks)
                        newTracks.forEach { track ->
                            track.id?.let { likedTrackIds[it] = true }
                        }
                    }
                    saveLikedTracksToCache()
                } else {
                    fetchAllSavedTracks()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            withContext(Dispatchers.Main) {
                isSyncingLikedTracks = false
            }
        }
    }

    private suspend fun fetchAllSavedTracks() = withContext(Dispatchers.IO) {
        val firstPage = getSavedTracks(limit = 50, offset = 0)
        val allTracks = mutableListOf<FullTrack>()
        allTracks.addAll(firstPage.items)

        withContext(Dispatchers.Main) {
            likedTracks.clear()
            likedTracks.addAll(allTracks)
            likedTrackIds.clear()
            allTracks.forEach { track ->
                track.id?.let { likedTrackIds[it] = true }
            }
        }

        val total = firstPage.total
        if (total > 50) {
            val offsets = (50 until total step 50).toList()
            val chunks = offsets.chunked(5) // Fetch in batches of 5 parallel requests to avoid 429
            for (chunk in chunks) {
                val pages = coroutineScope {
                    chunk.map { offset ->
                        async { getSavedTracks(limit = 50, offset = offset) }
                    }.awaitAll()
                }
                for (page in pages) {
                    allTracks.addAll(page.items)
                }

                // Update UI progressively
                withContext(Dispatchers.Main) {
                    likedTracks.clear()
                    likedTracks.addAll(allTracks)
                    allTracks.forEach { track ->
                        track.id?.let { likedTrackIds[it] = true }
                    }
                }
            }
        }
        saveLikedTracksToCache()
    }

    private suspend fun <T> loadLibraryItemsFromCache(
        cacheFile: java.io.File,
        stateList: androidx.compose.runtime.snapshots.SnapshotStateList<T>,
        fromJson: (JSONObject) -> T
    ) = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext
        try {
            val array = JSONArray(cacheFile.readText())
            val items = mutableListOf<T>()
            for (i in 0 until array.length()) {
                items.add(fromJson(array.getJSONObject(i)))
            }
            withContext(Dispatchers.Main) {
                stateList.clear()
                stateList.addAll(items)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private suspend fun <T> syncLibraryItems(
        fetchItems: suspend (limit: Int, offset: Int) -> PaginatedResponse<T>,
        stateList: androidx.compose.runtime.snapshots.SnapshotStateList<T>,
        cacheFile: java.io.File,
        toJson: (T) -> JSONObject,
        setSyncingFlag: (Boolean) -> Unit
    ) {
        setSyncingFlag(true)
        try {
            withContext(Dispatchers.IO) {
                val allItems = mutableListOf<T>()
                var offset = 0
                val limit = 50
                while (true) {
                    val page = fetchItems(limit, offset)
                    allItems.addAll(page.items)
                    if (page.hasMore && page.nextOffset != null) {
                        // Use server-provided nextOffset to avoid infinite loop when
                        // client-side filtering discards items (e.g. PlaylistResponseWrapper filter)
                        val newOffset = page.nextOffset
                        if (newOffset <= offset) break  // safety: offset didn't advance
                        offset = newOffset
                    } else {
                        break
                    }
                }

                // Only replace cached data after successful API call (BUG-05: VPN resilience)
                withContext(Dispatchers.Main) {
                    stateList.clear()
                    stateList.addAll(allItems)
                }

                val array = JSONArray()
                allItems.forEach { array.put(toJson(it)) }
                cacheFile.writeText(array.toString())
            }
        } catch (e: Exception) {
            // API failed — keep existing cached data, don't clear the list (BUG-05)
            android.util.Log.w("SpotifyRepository", "Sync failed, keeping cached data: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { setSyncingFlag(false) }
        }
    }

    suspend fun syncPlaylists() {
        if (isSyncingPlaylists) return
        syncLibraryItems(
            fetchItems = { limit, offset -> getSavedPlaylists(limit, offset) },
            stateList = savedPlaylists,
            cacheFile = java.io.File(appCtx.filesDir, "spotify_saved_playlists_cache.json"),
            toJson = { it.toJson() },
            setSyncingFlag = { isSyncingPlaylists = it }
        )
    }

    suspend fun syncAlbums() {
        if (isSyncingAlbums) return
        syncLibraryItems(
            fetchItems = { limit, offset -> getSavedAlbums(limit, offset) },
            stateList = savedAlbums,
            cacheFile = java.io.File(appCtx.filesDir, "spotify_saved_albums_cache.json"),
            toJson = { it.toJson() },
            setSyncingFlag = { isSyncingAlbums = it }
        )
    }

    suspend fun syncArtists() {
        if (isSyncingArtists) return
        syncLibraryItems(
            fetchItems = { limit, offset -> getFollowedArtists(limit, offset) },
            stateList = followedArtists,
            cacheFile = java.io.File(appCtx.filesDir, "spotify_followed_artists_cache.json"),
            toJson = { it.toJson() },
            setSyncingFlag = { isSyncingArtists = it }
        )
    }

    fun isTrackLiked(id: String): Boolean = likedTrackIds[id] == true

    // -------------------------------------------------------------------
    // E30 — API de playlists y favoritos locales (ids "local:...").
    // -------------------------------------------------------------------

    /** ¿Está este track local marcado como favorito? */
    fun isLocalFavorite(id: String): Boolean = localFavoriteIds[id] == true

    /** Marca/desmarca un track local como favorito. No-op si no es id "local:". */
    fun toggleLocalFavorite(id: String) {
        if (!id.startsWith("local:")) return
        localFavoriteIds[id] = !(localFavoriteIds[id] == true)
        saveLocalFavorites()
    }

    /** Tracks locales marcados como favoritos (vivos, con cover/duración). Ignora huérfanos. */
    fun localFavoriteTracks(): List<FullTrack> =
        localTracks.filter { it.id != null && localFavoriteIds[it.id] == true }

    fun createLocalPlaylist(name: String): LocalPlaylist {
        val pl = LocalPlaylist(
            id = "localpl:${java.util.UUID.randomUUID()}",
            name = name,
            trackIds = emptyList()
        )
        localPlaylists.add(pl)
        saveLocalPlaylists()
        return pl
    }

    fun addToLocalPlaylist(playlistId: String, trackId: String) {
        // Local playlists resolve their track ids against scanned local files only
        // (see localPlaylistTracks), so a non-"local:" id (Spotify/YTM) would be stored but silently
        // never render — "added" yet invisible. Keep non-local tracks out.
        if (!trackId.startsWith("local:")) return
        val i = localPlaylists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return
        val pl = localPlaylists[i]
        if (trackId in pl.trackIds) return
        localPlaylists[i] = pl.copy(
            trackIds = pl.trackIds + trackId,
            updatedAt = System.currentTimeMillis()
        )
        localPlaylistTracksCache.remove(playlistId)
        saveLocalPlaylists()
    }

    fun removeFromLocalPlaylist(playlistId: String, trackId: String) {
        val i = localPlaylists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return
        val pl = localPlaylists[i]
        localPlaylists[i] = pl.copy(
            trackIds = pl.trackIds - trackId,
            updatedAt = System.currentTimeMillis()
        )
        localPlaylistTracksCache.remove(playlistId)
        saveLocalPlaylists()
    }

    fun deleteLocalPlaylist(playlistId: String) {
        localPlaylists.removeAll { it.id == playlistId }
        localPlaylistTracksCache.remove(playlistId)
        saveLocalPlaylists()
    }

    fun renameLocalPlaylist(playlistId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val i = localPlaylists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return
        val pl = localPlaylists[i]
        if (pl.name == trimmed) return
        localPlaylists[i] = pl.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        saveLocalPlaylists()
    }

    /** Resuelve ids "local:" → FullTrack vivos (con cover/duración). Ignora huérfanos. */
    fun localPlaylistTracks(playlistId: String): List<FullTrack> {
        val byId = localTracks.associateBy { it.id }
        return localPlaylists.firstOrNull { it.id == playlistId }
            ?.trackIds?.mapNotNull { byId[it] } ?: emptyList()
    }

    init {
        instance = this

        // Initialize cache directory in Rust engine (now using filesDir for persistence)
        val cacheDirPath = context.filesDir.absolutePath
        NativeEngine.initSpotifyCacheDirNative(cacheDirPath)

        // Trigger background hash check/warmup
        NativeEngine.warmupSpotifyHashesNative()

        // Load cache and trigger background sync
        repositoryScope.launch {
            withContext(Dispatchers.IO) {
                loadLikedTracksFromCache()
loadLocalTracksFromCache()
                loadLocalUserData()   // E30: playlists + favoritos locales
                loadLibraryItemsFromCache(java.io.File(appCtx.filesDir, "spotify_saved_playlists_cache.json"), savedPlaylists) { SimplePlaylist.fromJson(it) }
                loadLibraryItemsFromCache(java.io.File(appCtx.filesDir, "spotify_saved_albums_cache.json"), savedAlbums) { FullAlbum.fromJson(it) }
                loadLibraryItemsFromCache(java.io.File(appCtx.filesDir, "spotify_followed_artists_cache.json"), followedArtists) { FullArtist.fromJson(it) }
            }
            if (isAuthenticated()) {
                syncLikedTracks()
                syncPlaylists()
                syncAlbums()
                syncArtists()
            }
            scanLocalMusic()
        }
    }

    private fun loadLocalTracksFromCache() {
        val file = getLocalTracksCacheFile()
        if (!file.exists()) return
        try {
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            val initialTracks = mutableListOf<FullTrack>()
            for (i in 0 until array.length()) {
                initialTracks.add(FullTrack.fromJson(array.getJSONObject(i)))
            }
            repositoryScope.launch(Dispatchers.Main) {
                localTracks.clear()
                localTracks.addAll(initialTracks)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveLocalTracksToCache(tracks: List<FullTrack>) {
        val file = getLocalTracksCacheFile()
        try {
            val array = JSONArray()
            tracks.forEach { track ->
                array.put(track.toJson())
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun scanLocalMusic() {
        if (isScanningLocalTracks) return
        repositoryScope.launch(Dispatchers.IO) {
            isScanningLocalTracks = true
            try {
                val settingsPrefs = appCtx.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
                val localMusicDirs = settingsPrefs.getStringSet("local_music_directories", emptySet()) ?: emptySet()
                if (localMusicDirs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        localTracks.clear()
                    }
                    saveLocalTracksToCache(emptyList())
                    return@launch
                }

                val existingTracks = localTracks.associateBy { it.id }
                val supportedExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "webm", "aac")
                val coversDir = java.io.File(appCtx.filesDir, "covers").also { it.mkdirs() }
                // Covers are deduplicated per album (see below). Resolution mode: full-res (default,
                // sharpest) vs downscaled 512px (smaller). Wipe & rebuild covers when the mode changes —
                // this also migrates away from the old per-track full-res scheme that bloated User Data.
                val coversFullRes = settingsPrefs.getBoolean("settings_local_covers_full_res", true)
                val coversMode = if (coversFullRes) "full" else "512"
                if (settingsPrefs.getString("covers_res_mode", null) != coversMode) {
                    coversDir.listFiles()?.forEach { runCatching { it.delete() } }
                    settingsPrefs.edit().putString("covers_res_mode", coversMode).apply()
                }
                val newLocalTracks = java.util.Collections.synchronizedList(mutableListOf<FullTrack>())
                val totalFileCount = java.util.concurrent.atomic.AtomicInteger(0)

                // Extract metadata + optional cover for a single file
                fun extractTrack(
                    file: androidx.documentfile.provider.DocumentFile,
                    existingTrack: FullTrack?
                ): FullTrack? {
                    val trackId = "local:${file.uri}"
                    val lastModifiedStr = file.lastModified().toString()
                    val name = file.name ?: return null

                    // Fast path: unchanged existing track with valid cover
                    if (existingTrack != null && existingTrack.addedAt == lastModifiedStr) {
                        val existingCoverPath = existingTrack.externalUri.removePrefix("file://")
                        if (existingCoverPath.isBlank() || java.io.File(existingCoverPath).exists()) {
                            return existingTrack
                        }
                    }

                    val retriever = android.media.MediaMetadataRetriever()
                    return try {
                        retriever.setDataSource(appCtx, file.uri)
                        val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name
                        val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                        val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"
                        val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val durationMs = durationStr?.toLongOrNull() ?: 0L

                        // Cover art: ONE file per ALBUM (deduplicated) + downscaled/recompressed, so a
                        // large local library doesn't bloat User Data. Key by album+artist so all tracks
                        // of an album share a single ~512px JPEG instead of each storing full-res art.
                        val coverKey = "$album|$artist".lowercase().hashCode()
                        val coverFile = java.io.File(coversDir, "$coverKey.jpg")
                        var coverUri = if (coverFile.exists()) "file://${coverFile.absolutePath}" else ""
                        if (coverUri.isBlank()) {
                            try {
                                val coverBytes = retriever.embeddedPicture
                                if (coverBytes != null && coverBytes.isNotEmpty()) {
                                    if (coversFullRes) {
                                        // Full resolution (default): keep the original embedded art — sharpest.
                                        // Dedup-by-album already keeps this from bloating User Data.
                                        coverFile.writeBytes(coverBytes)
                                        coverUri = "file://${coverFile.absolutePath}"
                                    } else {
                                        val bmp = android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                                        if (bmp != null) {
                                            val maxPx = 512
                                            val scale = minOf(1f, maxPx.toFloat() / maxOf(bmp.width, bmp.height))
                                            val out = if (scale < 1f)
                                                android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                                            else bmp
                                            java.io.FileOutputStream(coverFile).use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                                            if (out !== bmp) out.recycle()
                                            bmp.recycle()
                                            coverUri = "file://${coverFile.absolutePath}"
                                        } else {
                                            coverFile.writeBytes(coverBytes)
                                            coverUri = "file://${coverFile.absolutePath}"
                                        }
                                    }
                                }
                            } catch (_: Exception) { }
                        }

                        FullTrack(
                            id = trackId,
                            name = title,
                            artists = listOf(SimpleArtist("local_artist:$artist", artist, "", emptyList())),
                            album = SimpleAlbum(
                                "local_album:$album", album, "", null, null, 
                                if (coverUri.isNotBlank()) listOf(SpotifyImage(coverUri, null, null)) else emptyList(), 
                                emptyList(), null
                            ),
                            durationMs = durationMs.toInt(),
                            explicit = false,
                            isrc = "",
                            addedAt = lastModifiedStr,
                            externalUri = coverUri
                        )
                    } catch (e: Exception) {
                        null
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }

                // Process directories in parallel: one coroutine per root directory
                val scanDispatcher = Dispatchers.IO.limitedParallelism(32)
                coroutineScope {
                    localMusicDirs.map { dirUriStr ->
                        async(Dispatchers.IO) {
                            val dirTracks = java.util.Collections.synchronizedList(mutableListOf<FullTrack>())
                            try {
                                val treeUri = android.net.Uri.parse(dirUriStr)
                                val dirFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(appCtx, treeUri)

                                // Recursive directory scanner
                                suspend fun processDirectory(df: androidx.documentfile.provider.DocumentFile?) {
                                    if (df == null) return
                                    val files = df.listFiles()
                                    
                                    val audioFiles = mutableListOf<androidx.documentfile.provider.DocumentFile>()
                                    val subdirs = mutableListOf<androidx.documentfile.provider.DocumentFile>()
                                    
                                    for (file in files) {
                                        if (file.isDirectory) {
                                            subdirs.add(file)
                                        } else {
                                            val mime = file.type
                                            val fname = file.name?.lowercase() ?: ""
                                            val hasAudioExtension = supportedExtensions.any { fname.endsWith(".$it") }
                                            if (mime?.startsWith("audio/") == true || hasAudioExtension) {
                                                audioFiles.add(file)
                                            }
                                        }
                                    }
                                    
                                    coroutineScope {
                                        val fileJob = async(scanDispatcher) {
                                            for (file in audioFiles) {
                                                if (totalFileCount.incrementAndGet() % 16 == 0) {
                                                    delay(1.milliseconds)
                                                }
                                                val trackId = "local:${file.uri}"
                                                val existing = existingTracks[trackId]
                                                val track = extractTrack(file, existing)
                                                if (track != null) dirTracks.add(track)
                                            }
                                        }
                                        
                                        val subdirJobs = subdirs.map { subdir ->
                                            async(scanDispatcher) {
                                                processDirectory(subdir)
                                            }
                                        }
                                        
                                        fileJob.await()
                                        subdirJobs.awaitAll()
                                    }
                                }
                                processDirectory(dirFile)
                            } catch (_: Exception) { }
                            dirTracks
                        }
                    }.awaitAll().forEach { dirTracks ->
                        newLocalTracks.addAll(dirTracks)
                    }
                }

                // Reclaim orphaned covers (albums/tracks no longer present in the library).
                runCatching {
                    val referenced = newLocalTracks.mapNotNull {
                        it.externalUri.removePrefix("file://").substringAfterLast('/').ifBlank { null }
                    }.toHashSet()
                    coversDir.listFiles()?.forEach { f -> if (f.name !in referenced) runCatching { f.delete() } }
                }

                withContext(Dispatchers.Main) {
                    localTracks.clear()
                    localTracks.addAll(newLocalTracks)
                }
                saveLocalTracksToCache(newLocalTracks)

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScanningLocalTracks = false
            }
        }
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    /**
     * Parse a JSON response from the engine.
     * If the response is an error object ({"success": false, "error": "..."}),
     * throws SpotifyEngineException instead of trying to parse it as data.
     */
    private fun checkForError(json: String): JSONObject {
        val obj = JSONObject(json)
        // Check if this is an error response from the engine
        if (obj.has("success") && !obj.optBoolean("success", true)) {
            val errorMsg = obj.optString("error", "Unknown engine error")
            throw SpotifyEngineException(errorMsg)
        }
        return obj
    }

    /**
     * Parse a JSON response that should be a JSONArray.
     * If it's actually an error object, throws SpotifyEngineException.
     */
    private fun checkForErrorArray(json: String): JSONArray {
        // Try array first
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            return JSONArray(trimmed)
        }
        // It's probably an error object
        checkForError(json)
        // If checkForError didn't throw, return empty array
        return JSONArray()
    }

    // =========================================================================
    // AUTHENTICATION
    // =========================================================================

    /**
     * Login with a sp_dc cookie intercepted from the WebView.
     * Persists the cookie for future session restoration.
     */
    suspend fun login(spDcCookie: String): LoginResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.loginSpotifyNative(spDcCookie)
        val result = LoginResult.fromJson(JSONObject(json))
        if (result.success) {
            prefs.edit {
                putString(KEY_SP_DC, spDcCookie)
                putString(KEY_ACCESS_TOKEN, result.accessToken)
                putLong(KEY_EXPIRATION, result.expiration ?: 0L)
            }
            triggerBackgroundSync()
        }
        result
    }

    /**
     * Logout: clears Rust state and persisted cookie/tokens. Preserves developer settings.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        NativeEngine.logoutSpotifyNative()
        prefs.edit {
            remove(KEY_SP_DC)
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_EXPIRATION)
        }
    }

    /**
     * Check if user is currently authenticated with a valid token.
     */
    fun isAuthenticated(): Boolean = NativeEngine.isSpotifyAuthenticatedNative()

    /**
     * Recover a hot Spotify session in-memory (token expired mid-session, E10 RC-2).
     * Re-runs the native restore flow without wiping credentials on transient network errors.
     * Safe to call from any coroutine; returns true if the session is (now) valid.
     */
    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        if (NativeEngine.isSpotifyAuthenticatedNative()) return@withContext true
        val savedCookie = prefs.getString(KEY_SP_DC, "") ?: ""
        if (savedCookie.isEmpty()) return@withContext false
        val result = restoreSession() ?: return@withContext false
        result.success
    }

    /**
     * Attempt to restore a previous session from the saved sp_dc cookie.
     * Call this on app startup to auto-login.
     * @return LoginResult if a saved cookie exists, null if no saved session.
     */
    suspend fun restoreSession(): LoginResult? = withContext(Dispatchers.IO) {
        val savedCookie = prefs.getString(KEY_SP_DC, "") ?: ""
        val cachedToken = prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""
        val cachedExp = prefs.getLong(KEY_EXPIRATION, 0L)
        
        if (savedCookie.isEmpty()) {
            return@withContext null
        }
        
        val json = try {
            NativeEngine.restoreSpotifySessionNative(savedCookie, cachedToken, cachedExp)
        } catch (e: Exception) {
            android.util.Log.w("SpotifyRepository", "restoreSession native threw: ${e.message}")
            return@withContext LoginResult(success = false, user = null, error = e.message,
                accessToken = null, expiration = null)
        }
        val result = LoginResult.fromJson(JSONObject(json))
        if (result.success) {
            prefs.edit {
                putString(KEY_ACCESS_TOKEN, result.accessToken)
                putLong(KEY_EXPIRATION, result.expiration ?: 0L)
            }
            triggerBackgroundSync()
        } else {
            // Only wipe credentials when the failure is NOT a transient network problem.
            // A network blip while restoring must not log the user out (E10 RC-2 / E11).
            val errKind = classifyError(SpotifyEngineException(result.error ?: ""))
            if (errKind != com.varuna.rustify.util.ErrorKind.TRANSIENT) {
                prefs.edit {
                    remove(KEY_SP_DC)
                    remove(KEY_ACCESS_TOKEN)
                    remove(KEY_EXPIRATION)
                }
            }
        }
        result
    }

    /**
     * Refresh the access token.
     */
    suspend fun refreshToken(): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.refreshSpotifyTokenNative()
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Check if there's a saved session available (sp_dc cookie).
     */
    fun hasSavedSession(): Boolean = 
        prefs.getString(KEY_SP_DC, null) != null

    // =========================================================================
    // USER / LIBRARY
    // =========================================================================

    /**
     * Get the current authenticated user's profile.
     * @throws SpotifyEngineException on API errors (rate limit, auth, etc.)
     */
    suspend fun getMe(): SpotifyUser = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyMeNative()
            SpotifyUser.fromJson(checkForError(json))
        }
    }

    /**
     * Get the user's saved/liked tracks.
     * Returns FullTrack directly (GraphQL + REST batch approach).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getSavedTracks(limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifySavedTracksNative(limit, offset)
            val paginated = PaginatedResponse.fromJson(checkForError(json)) { FullTrack.fromJson(it) }
            withContext(Dispatchers.Main) {
                paginated.items.forEach { track ->
                    track.id?.let { id ->
                        likedTrackIds[id] = true
                    }
                }
            }
            paginated
        }
    }

    // Removed REST-based checkSavedTracks and checkAndCacheLikedStates

    /**
     * Toggle like/unlike status for a track.
     */
    /**
     * Toggle like/unlike status for a track by object.
     */
    suspend fun toggleLikeTrack(track: FullTrack): OperationResult = withContext(Dispatchers.IO) {
        val id = track.id ?: return@withContext OperationResult(false, "Track ID is missing")
        val currentlyLiked = isTrackLiked(id)
        val result = if (currentlyLiked) {
            unsaveTracks(listOf(id))
        } else {
            saveTracks(listOf(id))
        }
        if (result.success) {
            withContext(Dispatchers.Main) {
                if (currentlyLiked) {
                    likedTrackIds[id] = false
                    likedTracks.removeAll { it.id == id }
                } else {
                    likedTrackIds[id] = true
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val nowStr = sdf.format(java.util.Date())
                    val newTrack = track.copy(addedAt = nowStr)
                    likedTracks.add(0, newTrack)
                }
                saveLikedTracksToCache()
            }
        }
        result
    }

    // --- Library follow/save toggles (reactive over the cached state lists) --------------------

    fun isAlbumSaved(id: String): Boolean = savedAlbums.any { it.id == id }
    fun isArtistFollowed(id: String): Boolean = followedArtists.any { it.id == id }
    fun isPlaylistFollowed(id: String): Boolean = savedPlaylists.any { it.id == id }

    /** Save/unsave an album; updates [savedAlbums] on success so the UI reflects it immediately. */
    suspend fun toggleSaveAlbum(album: FullAlbum): OperationResult = withContext(Dispatchers.IO) {
        val id = album.id
        val saved = isAlbumSaved(id)
        val result = if (saved) unsaveAlbums(listOf(id)) else saveAlbums(listOf(id))
        if (result.success) withContext(Dispatchers.Main) {
            if (saved) savedAlbums.removeAll { it.id == id }
            else if (savedAlbums.none { it.id == id }) savedAlbums.add(0, album)
        }
        result
    }

    /** Follow/unfollow an artist; updates [followedArtists] on success. */
    suspend fun toggleFollowArtist(artist: FullArtist): OperationResult = withContext(Dispatchers.IO) {
        val id = artist.id
        val followed = isArtistFollowed(id)
        val result = if (followed) unfollowArtists(listOf(id)) else followArtists(listOf(id))
        if (result.success) withContext(Dispatchers.Main) {
            if (followed) followedArtists.removeAll { it.id == id }
            else if (followedArtists.none { it.id == id }) followedArtists.add(0, artist)
        }
        result
    }

    /** Follow/unfollow a playlist; updates [savedPlaylists] on success. */
    suspend fun toggleFollowPlaylist(playlist: SimplePlaylist): OperationResult = withContext(Dispatchers.IO) {
        val id = playlist.id
        val followed = isPlaylistFollowed(id)
        val result = if (followed) unfollowPlaylist(id) else followPlaylist(id)
        if (result.success) withContext(Dispatchers.Main) {
            if (followed) savedPlaylists.removeAll { it.id == id }
            else if (savedPlaylists.none { it.id == id }) savedPlaylists.add(0, playlist)
        }
        result
    }

    /**
     * Toggle like/unlike status for a track by ID (fallback).
     */
    suspend fun toggleLikeTrack(id: String): OperationResult = withContext(Dispatchers.IO) {
        val skeleton = FullTrack(
            id = id,
            name = "",
            externalUri = "",
            explicit = false,
            durationMs = 0,
            isrc = "",
            artists = emptyList(),
            album = null
        )
        toggleLikeTrack(skeleton)
    }

    /**
     * Get the user's saved albums.
     * Returns FullAlbum directly (GraphQL + REST batch approach).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getSavedAlbums(limit: Int = 20, offset: Int = 0): PaginatedResponse<FullAlbum> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifySavedAlbumsNative(limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { FullAlbum.fromJson(it) }
        }
    }

    /**
     * Get the user's saved/created playlists.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getSavedPlaylists(limit: Int = 20, offset: Int = 0): PaginatedResponse<SimplePlaylist> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifySavedPlaylistsNative(limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { SimplePlaylist.fromJson(it) }
        }
    }

    /**
     * Get the artists the user follows.
     * Now uses offset-based pagination (GraphQL libraryV3).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getFollowedArtists(limit: Int = 20, offset: Int = 0): PaginatedResponse<FullArtist> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyFollowedArtistsNative(limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { FullArtist.fromJson(it) }
        }
    }

    // =========================================================================
    // ALBUMS
    // =========================================================================

    /**
     * Get full album details.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getAlbum(id: String): FullAlbum = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyAlbumNative(id)
            FullAlbum.fromJson(checkForError(json))
        }
    }

    /**
     * Get tracks within an album.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getAlbumTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyAlbumTracksNative(id, limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { FullTrack.fromJson(it) }
        }
    }

    /**
     * Get newly released albums.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getNewReleases(limit: Int = 20, offset: Int = 0): PaginatedResponse<SimpleAlbum> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyNewReleasesNative(limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { SimpleAlbum.fromJson(it) }
        }
    }

    /**
     * Save albums to the user's library.
     */
    suspend fun saveAlbums(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.saveSpotifyAlbumsNative(JSONArray(ids).toString())
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Remove albums from the user's library.
     */
    suspend fun unsaveAlbums(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.unsaveSpotifyAlbumsNative(JSONArray(ids).toString())
        OperationResult.fromJson(JSONObject(json))
    }

    // =========================================================================
    // ARTISTS
    // =========================================================================

    /**
     * Get full artist details.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getArtist(id: String): FullArtist = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyArtistNative(id)
            FullArtist.fromJson(checkForError(json))
        }
    }

    /**
     * Get an artist's top tracks.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getArtistTopTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyArtistTopTracksNative(id, limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { FullTrack.fromJson(it) }
        }
    }

    /**
     * Get albums by an artist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getArtistAlbums(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<SimpleAlbum> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyArtistAlbumsNative(id, limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { SimpleAlbum.fromJson(it) }
        }
    }

    /**
     * Get artists related to a given artist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getRelatedArtists(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullArtist> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyRelatedArtistsNative(id, limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { FullArtist.fromJson(it) }
        }
    }

    /**
     * Follow artists.
     */
    suspend fun followArtists(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.followSpotifyArtistsNative(JSONArray(ids).toString())
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Unfollow artists.
     */
    suspend fun unfollowArtists(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.unfollowSpotifyArtistsNative(JSONArray(ids).toString())
        OperationResult.fromJson(JSONObject(json))
    }

    // =========================================================================
    // PLAYLISTS
    // =========================================================================

    /**
     * Get full playlist details.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getPlaylist(id: String): FullPlaylist = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyPlaylistNative(id)
            FullPlaylist.fromJson(checkForError(json))
        }
    }

    /**
     * Get tracks within a playlist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getPlaylistTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyPlaylistTracksNative(id, limit, offset)
            PaginatedResponse.fromJson(checkForError(json)) { FullTrack.fromJson(it) }
        }
    }

    /**
     * Create a new playlist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun createPlaylist(userId: String, name: String, description: String = "", public: Boolean = false): FullPlaylist = withContext(Dispatchers.IO) {
        val json = NativeEngine.createSpotifyPlaylistNative(userId, name, description, public)
        FullPlaylist.fromJson(checkForError(json))
    }

    /**
     * Update an existing playlist.
     */
    suspend fun updatePlaylist(id: String, name: String = "", description: String = ""): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.updateSpotifyPlaylistNative(id, name, description)
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Add tracks to a playlist.
     */
    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>, position: Int = -1): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.addTracksToPlaylistNative(playlistId, JSONArray(trackIds).toString(), position)
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Add many tracks to a playlist (E30-ctx: "add whole album/playlist to a playlist").
     *
     * The Rust `add_tracks_to_playlist` sends every uri in a single POST, but the Spotify REST
     * endpoint accepts at most 100 uris per call. So we chunk [trackIds] into batches of 100 and
     * call [addTracksToPlaylist] per chunk, accumulating how many were successfully added.
     *
     * @return number of tracks added.
     * @throws SpotifyEngineException if a chunk fails (the UI reports the partial count via the message).
     */
    suspend fun addAllTracksToPlaylist(context: Context, playlistId: String, trackIds: List<String>): Int = withContext(Dispatchers.IO) {
        var added = 0
        // Filter out blanks / local tracks (no valid Spotify uri).
        val validIds = trackIds.filter { it.isNotBlank() && !it.startsWith("local:") }
        for (chunk in validIds.chunked(100)) {
            val res = addTracksToPlaylist(playlistId, chunk)
            if (res.success) {
                added += chunk.size
            } else {
                // Surface partial progress in the error so the UI can inform the user.
                throw SpotifyEngineException(res.error ?: "add_tracks_to_playlist failed after $added tracks")
            }
        }
        added
    }

    /**
     * Remove tracks from a playlist.
     */
    suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.removeTracksFromPlaylistNative(playlistId, JSONArray(trackIds).toString())
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Follow/save a playlist.
     */
    suspend fun followPlaylist(id: String): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.followPlaylistNative(id)
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Unfollow/unsave a playlist.
     */
    suspend fun unfollowPlaylist(id: String): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.unfollowPlaylistNative(id)
        OperationResult.fromJson(JSONObject(json))
    }

    // =========================================================================
    // TRACKS
    // =========================================================================

    /**
     * Get full track details.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getTrack(id: String): FullTrack = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyTrackNative(id)
        FullTrack.fromJson(checkForError(json))
    }

    /**
     * Save tracks to the user's library.
     */
    suspend fun saveTracks(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.saveSpotifyTracksNative(JSONArray(ids).toString())
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Remove tracks from the user's library.
     */
    suspend fun unsaveTracks(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.unsaveSpotifyTracksNative(JSONArray(ids).toString())
        OperationResult.fromJson(JSONObject(json))
    }

    /**
     * Get track radio (list of tracks from a matching radio playlist).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getTrackRadio(trackId: String): List<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyTrackRadioNative(trackId)
            FullTrack.listFromJsonArray(checkForErrorArray(json))
        }
    }

    // =========================================================================
    // SEARCH
    // =========================================================================

    /**
     * Search all types (tracks, albums, artists, playlists).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchAll(query: String, limit: Int = 20): NormalizedSearchResults = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.searchSpotifyNative(query, "all", limit, 0)
            NormalizedSearchResults.fromJson(checkForError(json))
        }
    }

    /**
     * Search for tracks only.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchTracks(query: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        val json = NativeEngine.searchSpotifyNative(query, "tracks", limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { FullTrack.fromJson(it) }
    }

    /**
     * Search for albums only.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchAlbums(query: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<SimpleAlbum> = withContext(Dispatchers.IO) {
        val json = NativeEngine.searchSpotifyNative(query, "albums", limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { SimpleAlbum.fromJson(it) }
    }

    /**
     * Search for artists only.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchArtists(query: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullArtist> = withContext(Dispatchers.IO) {
        val json = NativeEngine.searchSpotifyNative(query, "artists", limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { FullArtist.fromJson(it) }
    }

    /**
     * Search for playlists only.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchPlaylists(query: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<SimplePlaylist> = withContext(Dispatchers.IO) {
        val json = NativeEngine.searchSpotifyNative(query, "playlists", limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { SimplePlaylist.fromJson(it) }
    }

    // =========================================================================
    // BROWSE
    // =========================================================================

    /**
     * Get browse/home sections (featured playlists + new releases).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getBrowseSections(limit: Int = 20): List<BrowseSection> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyBrowseNative(limit)
            BrowseSection.listFromJsonArray(checkForErrorArray(json))
        }
    }

    /**
     * Find a local music track that matches the given Spotify track.
     * Uses conservative matching: ISRC, normalized name + artist + duration.
     */
    fun findLocalMatch(track: FullTrack): FullTrack? {
        return localTracks.firstOrNull { localTrack ->
            isLocalMatch(track, localTrack)
        }
    }
}
