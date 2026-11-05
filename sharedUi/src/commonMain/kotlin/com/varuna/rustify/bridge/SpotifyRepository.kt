// app/src/main/java/com/varuna/rustify/bridge/SpotifyRepository.kt
@file:Suppress("SpellCheckingInspection")

package com.varuna.rustify.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.currentTimeMillis
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.deleteFile
import com.varuna.rustify.ui.fileExists
import com.varuna.rustify.ui.fileName
import com.varuna.rustify.ui.fullPath
import com.varuna.rustify.ui.listChildren
import com.varuna.rustify.ui.makeDirs
import com.varuna.rustify.ui.readTextOrNull
import com.varuna.rustify.ui.writeAllText
import com.varuna.rustify.ui.writeTextAtomically
import com.varuna.rustify.util.LockedMap
import com.varuna.rustify.util.classifyError
import com.varuna.rustify.util.optString
import com.varuna.rustify.util.optStringOrNull
import com.varuna.rustify.util.parseArray
import com.varuna.rustify.util.parseObject
import com.varuna.rustify.util.retrying
import kotlinx.coroutines.Dispatchers
import com.varuna.rustify.platform.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// SpotifyEngineException lives in the shared module (bridge/SpotifyEngineException.kt) since 5.x:
// util/Retry.kt classifies it and moved there with it. Same package, so nothing here imports it.

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
 *   val repo = SpotifyRepository(platform, scanner)
 *   val result = repo.login(spDcCookie)
 *   val tracks = repo.getSavedTracks(20, 0)
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class SpotifyRepository(private val platform: AppPlatform, private val scanner: LocalMusicScanner) {
    companion object {
        private const val PREFS_NAME = "rustify_spotify_prefs"
        private const val KEY_SP_DC = "sp_dc_cookie"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_EXPIRATION = "expiration_timestamp"

        /** In `rustify_settings`: absolute folder paths the local scan skips. */
        const val LOCAL_EXCLUDED_FOLDERS = "local_excluded_folders"
        /** In `rustify_settings`: local files shorter than this many seconds are left out (0 = none). */
        const val LOCAL_MIN_DURATION_S = "local_min_duration_s"
        /** In `rustify_settings`: the artist separators the cached local tracks were split with. */
        private const val SEPARATORS_APPLIED = "local_artist_separators_applied"

        /**
         * How much life a stored token needs before [ensureSession] will trust it without
         * refreshing. Deliberately longer than the engine's own 30 s refusal margin, so this side is
         * never the more optimistic of the two.
         */
        private const val SESSION_MARGIN_MS = 60_000L

        @Volatile
        // internal (not private): the Android Auto MediaLibrarySession (RustifyForegroundService)
        // reads the live repo to build its browsable tree.
        var instance: SpotifyRepository? = null

        // Local music bridge: temporary storage for local album/artist tracks
        // Used to pass local track data to AlbumScreen/ArtistScreen without API calls
val localAlbumTracks = mutableMapOf<String, List<FullTrack>>()
    val localArtistTracks = mutableMapOf<String, List<FullTrack>>()
    // App-lifetime cache of already-loaded Spotify Album/Artist data, so the screen does not have to
    // reload (spinner) when it is recreated after opening/closing the miniplayer.
    val albumTracksCache: MutableMap<String, List<FullTrack>> = LockedMap()
    val artistTopTracksCache: MutableMap<String, List<FullTrack>> = LockedMap()
    val artistAlbumsCache: MutableMap<String, List<SimpleAlbum>> = LockedMap()
    // Cache of resolved tracks for a local playlist (same pattern as localAlbumTracks). Accessed from
    // the main thread (UI: PlaylistScreen/LibraryScreen) and from IO, so every call holds a lock
    // to avoid races when reading/writing/clearing the map concurrently.
    val localPlaylistTracksCache: MutableMap<String, List<FullTrack>> = LockedMap()

        // `normalizeName` and `isLocalMatch` used to live here. They are now
        // `core_engine/src/matcher/local.rs`, reached through [LocalMatcher] — one rule about what
        // counts as the same recording, next to the accent fold it always needed and never had.

        /** [filesDir] is where the scan cache lives, read when no repository is alive (a cold start from the notification). */
        fun findLocalMatch(filesDir: AppFile, track: FullTrack): FullTrack? {
            // First try the in-memory localTracks from the repository instance
            val repo = instance ?: return findLocalMatchFromDisk(filesDir, track)
            return repo.findLocalMatch(track)
        }

        private var memoryCacheLocalTracks: List<FullTrack>? = null

        private val trackList = ListSerializer(FullTrack.serializer())

        // The track caches as the org.json helpers read and wrote them: an array of the wire form,
        // every element through the generated decoder (one that is not a track fails the whole read).
        private fun decodeTracks(json: String): List<FullTrack> = parseArray(json).map { decodeTrack(it) }

        private fun decodeTrack(element: JsonElement): FullTrack =
            decodeWire(element.jsonObjectOrThrow().toString())

        private fun JsonElement.jsonObjectOrThrow(): JsonObject =
            this as? JsonObject ?: throw IllegalArgumentException("not a track: $this")

        private fun encodeTracks(tracks: List<FullTrack>): String = RustifyJson.encodeToString(trackList, tracks)

        /** The local library as last scanned, from its cache on disk; empty when there is none. */
        fun cachedLocalTracks(filesDir: AppFile): List<FullTrack> {
            if (memoryCacheLocalTracks == null) {
                try {
                    val jsonStr = filesDir.child("local_music_cache.json").readTextOrNull() ?: return emptyList()
                    memoryCacheLocalTracks = decodeTracks(jsonStr)
                } catch (e: Exception) {
                    return emptyList()
                }
            }
            return memoryCacheLocalTracks ?: emptyList()
        }

        /**
         * Hands the local library to the core's matcher now — the screen's list when it is loaded,
         * the scan's cache when not — for what describes a local song by its tags or looks one up
         * (a backup's places, `player::places`).
         */
        fun registerLocalLibrary(filesDir: AppFile) {
            val loaded = instance?.localTracks?.toList().orEmpty()
            if (loaded.isNotEmpty()) LocalMatcher.ensureRegistered("repo", loaded)
            else LocalMatcher.ensureRegistered("disk", cachedLocalTracks(filesDir))
        }

        private fun findLocalMatchFromDisk(filesDir: AppFile, track: FullTrack): FullTrack? {
            val pool = cachedLocalTracks(filesDir).ifEmpty { return null }
            LocalMatcher.ensureRegistered("disk", pool)
            val id = LocalMatcher.matchId(track) ?: return null
            return pool.firstOrNull { it.id == id }
        }
    }

    private val prefs = platform.prefs(PREFS_NAME)
    private val likedTrackIds = mutableStateMapOf<String, Boolean>()

    /** Home's sections as they last loaded, for a start with no network. Personal: cleared on logout. */
    private val homeCacheFile = platform.filesDir.child("home_browse_cache.json")
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    val likedTracks = androidx.compose.runtime.mutableStateListOf<FullTrack>()
    var isSyncingLikedTracks by mutableStateOf(false)
        private set

    // Spotify playlist tracks cached at the repository level (like likedTracks): they survive screen
    // recreation (e.g. opening/closing the miniplayer) and ALL pages are loaded in the background. So
    // returning to a playlist neither re-waits on the spinner nor "jumps" to track 50 because the rest
    // had not loaded yet.
    private val spotifyPlaylistTracks = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<FullTrack>>()
    private val spotifyPlaylistJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    // Observable (Compose) so the spinner turns off by itself once loading finishes.
    private val spotifyPlaylistLoading = mutableStateMapOf<String, Boolean>()

    /** Live (observable) list of a Spotify playlist's tracks; filled in the background. */
    fun playlistTracksLive(id: String): androidx.compose.runtime.snapshots.SnapshotStateList<FullTrack> =
        spotifyPlaylistTracks.getOrPut(id) { androidx.compose.runtime.mutableStateListOf() }

    /** Whether this playlist is currently loading (to choose spinner vs "no songs"). */
    fun isPlaylistLoading(id: String): Boolean = spotifyPlaylistLoading[id] == true

    /**
     * Loads ALL pages of a Spotify playlist in the background (100 per page) into
     * [playlistTracksLive]. Idempotent: does not relaunch if tracks already exist or a job is active,
     * unless [force] is set.
     */
    fun loadFullPlaylist(id: String, force: Boolean = false) {
        if (PlaylistRef.isLocal(id)) return
        if (spotifyPlaylistJobs[id]?.isActive == true) return
        val list = playlistTracksLive(id)
        if (list.isNotEmpty() && !force) return
        spotifyPlaylistLoading[id] = true
        spotifyPlaylistJobs[id] = repositoryScope.launch {
            try {
                if (force) list.clear()
                var offset = list.size
                while (true) {
                    val resp = getPlaylistTracks(id, limit = 100, offset = offset)
                    if (resp.items.isEmpty()) break
                    list.addAll(resp.items)
                    offset += resp.items.size
                    if (!resp.hasMore) break
                }
            } catch (_: Exception) {
                // Keep whatever was loaded; the spinner turns off anyway in finally.
            } finally {
                spotifyPlaylistLoading[id] = false
            }
        }
    }

    // An artist's "all tracks": their whole discography (albums + singles), in release order (most
    // recent first), deduplicated by name+artist. Live list + background loading.
    private val artistAllTracks = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<FullTrack>>()
    private val artistAllJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val artistAllLoading = mutableStateMapOf<String, Boolean>()

    fun artistAllTracksLive(id: String): androidx.compose.runtime.snapshots.SnapshotStateList<FullTrack> =
        artistAllTracks.getOrPut(id) { androidx.compose.runtime.mutableStateListOf() }

    fun isArtistAllLoading(id: String): Boolean = artistAllLoading[id] == true

    fun loadArtistAllTracks(id: String, force: Boolean = false) {
        if (artistAllJobs[id]?.isActive == true) return
        val list = artistAllTracksLive(id)
        if (list.isNotEmpty() && !force) return
        artistAllLoading[id] = true
        artistAllJobs[id] = repositoryScope.launch {
            try {
                if (force) list.clear()
                // 1) All of the artist's albums/singles (paginated).
                val albums = ArrayList<SimpleAlbum>()
                var offset = 0
                while (true) {
                    val resp = getArtistAlbums(id, limit = 50, offset = offset)
                    if (resp.items.isEmpty()) break
                    albums.addAll(resp.items)
                    offset += resp.items.size
                    if (!resp.hasMore) break
                }
                // 2) Release order (most recent first).
                val ordered = albums.sortedByDescending { it.releaseDate ?: "" }
                // 3) Tracks of each album in order, deduplicated by name+artist, enriched with the
                //    album cover so artwork shows up in the list.
                val seen = HashSet<String>()
                for (al in ordered) {
                    val tr = runCatching { getAlbumTracks(al.id, limit = 50, offset = 0).items }.getOrDefault(emptyList())
                    for (t in tr) {
                        val key = (t.name + "|" + t.artists.joinToString { it.name }).lowercase()
                        if (seen.add(key)) list.add(if (t.album == null) t.copy(album = al) else t)
                    }
                }
            } catch (_: Exception) {
            } finally {
                artistAllLoading[id] = false
            }
        }
    }

    val localTracks = androidx.compose.runtime.mutableStateListOf<FullTrack>()
    var isScanningLocalTracks by mutableStateOf(false)
        private set

    // Local playlists and favorites (JSON in filesDir, "local:..." ids).
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

    private fun getLocalTracksCacheFile(): AppFile {
        return platform.filesDir.child("local_music_cache.json")
    }

    private fun getLikedTracksCacheFile(): AppFile {
        return platform.filesDir.child("spotify_liked_tracks_cache.json")
    }

    // Local data files (playlists + favorites).
    private fun getLocalPlaylistsFile(): AppFile =
        platform.filesDir.child("local_playlists.json")

    private fun getLocalFavoritesFile(): AppFile =
        platform.filesDir.child("local_favorites.json")

    private fun loadLocalUserData() {
        runCatching {
            val text = getLocalPlaylistsFile().readTextOrNull()
            if (text != null) {
                val list = decodeWire<List<LocalPlaylist>>(parseArray(text).toString())
                repositoryScope.launch(Dispatchers.Main) {
                    localPlaylists.clear()
                    localPlaylists.addAll(list)
                }
            }
        }
        runCatching {
            val text = getLocalFavoritesFile().readTextOrNull()
            if (text != null) {
                val ids = parseArray(text).map { it.optStringOrNull() ?: error("not an id: $it") }
                repositoryScope.launch(Dispatchers.Main) {
                    ids.forEach { localFavoriteIds[it] = true }
                }
            }
        }
    }

    /** Reload local playlists + favorites from disk (e.g. after an import). */
    fun reloadLocalUserData() {
        repositoryScope.launch(Dispatchers.IO) {
            // SnapshotStateList/Map must be mutated on the main thread; only the I/O runs on IO.
            withContext(Dispatchers.Main) {
                localPlaylists.clear()
                localFavoriteIds.clear()
                localPlaylistTracksCache.clear()
            }
            loadLocalUserData()
        }
    }

    private fun saveLocalPlaylists() {
        // Snapshot on the caller thread (avoids ConcurrentModification), then write off the main
        // thread. These are called from Compose click handlers, where synchronous disk I/O on the UI
        // thread would cause jank/ANR when mutating local playlists.
        val snapshot = localPlaylists.toList()
        repositoryScope.launch(Dispatchers.IO) {
            runCatching {
                getLocalPlaylistsFile().writeTextAtomically(encodeWireString(snapshot))
            }
        }
    }

    private fun saveLocalFavorites() {
        val keys = localFavoriteIds.filterValues { it }.keys.toList()
        repositoryScope.launch(Dispatchers.IO) {
            runCatching {
                getLocalFavoritesFile().writeTextAtomically(encodeWireString(keys))
            }
        }
    }

    private fun loadLikedTracksFromCache() {
        val file = getLikedTracksCacheFile()
        if (!file.fileExists()) return
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = file.readTextOrNull() ?: return@launch
                val array = parseArray(jsonStr)

                // Fast path for first 50 items
                val initialBatchSize = kotlin.math.min(50, array.size)
                val initialTracks = mutableListOf<FullTrack>()
                for (i in 0 until initialBatchSize) {
                    initialTracks.add(decodeTrack(array[i]))
                }
                
                withContext(Dispatchers.Main) {
                    likedTracks.clear()
                    likedTracks.addAll(initialTracks)
                    initialTracks.forEach { track ->
                        track.id?.let { likedTrackIds[it] = true }
                    }
                }
                
                // Lazy load the rest in batches
                if (array.size > initialBatchSize) {
                    var currentIdx = initialBatchSize
                    while (currentIdx < array.size) {
                        val batchSize = kotlin.math.min(200, array.size - currentIdx)
                        val batchTracks = mutableListOf<FullTrack>()
                        for (i in 0 until batchSize) {
                            batchTracks.add(decodeTrack(array[currentIdx + i]))
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
            file.writeAllText(encodeTracks(likedTracks.toList()))
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
        cacheFile: AppFile,
        stateList: androidx.compose.runtime.snapshots.SnapshotStateList<T>,
        serializer: KSerializer<T>
    ) = withContext(Dispatchers.IO) {
        val text = cacheFile.readTextOrNull() ?: return@withContext
        try {
            val array = parseArray(text)
            val items = mutableListOf<T>()
            for (element in array) {
                items.add(RustifyJson.decodeFromString(serializer, element.jsonObjectOrThrow().toString()))
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
        cacheFile: AppFile,
        serializer: KSerializer<T>,
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
                    val newOffset = page.nextOffset
                    if (page.hasMore && newOffset != null) {
                        // Use server-provided nextOffset to avoid infinite loop when
                        // client-side filtering discards items (e.g. PlaylistResponseWrapper filter)
                        if (newOffset <= offset) break  // safety: offset didn't advance
                        offset = newOffset
                    } else {
                        break
                    }
                }

                // Only replace cached data after a successful API call (VPN resilience).
                withContext(Dispatchers.Main) {
                    stateList.clear()
                    stateList.addAll(allItems)
                }

                cacheFile.writeAllText(RustifyJson.encodeToString(ListSerializer(serializer), allItems))
            }
        } catch (e: Exception) {
            // API failed — keep existing cached data, don't clear the list.
            Log.w("SpotifyRepository", "Sync failed, keeping cached data: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { setSyncingFlag(false) }
        }
    }

    suspend fun syncPlaylists() {
        if (isSyncingPlaylists) return
        syncLibraryItems(
            fetchItems = { limit, offset -> getSavedPlaylists(limit, offset) },
            stateList = savedPlaylists,
            cacheFile = platform.filesDir.child("spotify_saved_playlists_cache.json"),
            serializer = SimplePlaylist.serializer(),
            setSyncingFlag = { isSyncingPlaylists = it }
        )
    }

    suspend fun syncAlbums() {
        if (isSyncingAlbums) return
        syncLibraryItems(
            fetchItems = { limit, offset -> getSavedAlbums(limit, offset) },
            stateList = savedAlbums,
            cacheFile = platform.filesDir.child("spotify_saved_albums_cache.json"),
            serializer = FullAlbum.serializer(),
            setSyncingFlag = { isSyncingAlbums = it }
        )
    }

    suspend fun syncArtists() {
        if (isSyncingArtists) return
        syncLibraryItems(
            fetchItems = { limit, offset -> getFollowedArtists(limit, offset) },
            stateList = followedArtists,
            cacheFile = platform.filesDir.child("spotify_followed_artists_cache.json"),
            serializer = FullArtist.serializer(),
            setSyncingFlag = { isSyncingArtists = it }
        )
    }

    fun isTrackLiked(id: String): Boolean = likedTrackIds[id] == true

    // -------------------------------------------------------------------
    // Local playlists and favorites API ("local:..." ids).
    // -------------------------------------------------------------------

    /** Whether this local track is marked as a favorite. */
    fun isLocalFavorite(id: String): Boolean = localFavoriteIds[id] == true

    /** Marks/unmarks a local track as a favorite. No-op if it is not a "local:" id. */
    fun toggleLocalFavorite(id: String) {
        if (!TrackRef.isLocal(id)) return
        localFavoriteIds[id] = localFavoriteIds[id] != true
        saveLocalFavorites()
        LibraryChanges.notifyChanged()
    }

    /** Local tracks marked as favorites (live, with cover/duration). Ignores orphans. */
    fun localFavoriteTracks(): List<FullTrack> =
        localTracks.filter { it.id != null && localFavoriteIds[it.id] == true }

    // Kotlin's Uuid prints as java.util.UUID did: 36 lowercase characters, version 4.
    @OptIn(ExperimentalUuidApi::class)
    fun createLocalPlaylist(name: String): LocalPlaylist {
        val pl = LocalPlaylist(
            id = "localpl:${Uuid.random()}",
            name = name,
            trackIds = emptyList()
        )
        localPlaylists.add(pl)
        saveLocalPlaylists()
        LibraryChanges.notifyChanged()
        return pl
    }

    fun addToLocalPlaylist(playlistId: String, trackId: String) {
        // Local playlists resolve their track ids against scanned local files only
        // (see localPlaylistTracks), so a non-"local:" id (Spotify/YTM) would be stored but silently
        // never render — "added" yet invisible. Keep non-local tracks out.
        if (!TrackRef.isLocal(trackId)) return
        val i = localPlaylists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return
        val pl = localPlaylists[i]
        if (trackId in pl.trackIds) return
        localPlaylists[i] = pl.copy(
            trackIds = pl.trackIds + trackId,
            updatedAt = currentTimeMillis()
        )
        localPlaylistTracksCache.remove(playlistId)
        saveLocalPlaylists()
        LibraryChanges.notifyChanged()
    }

    fun removeFromLocalPlaylist(playlistId: String, trackId: String) {
        val i = localPlaylists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return
        val pl = localPlaylists[i]
        localPlaylists[i] = pl.copy(
            trackIds = pl.trackIds - trackId,
            updatedAt = currentTimeMillis()
        )
        localPlaylistTracksCache.remove(playlistId)
        saveLocalPlaylists()
        LibraryChanges.notifyChanged()
    }

    fun deleteLocalPlaylist(playlistId: String) {
        localPlaylists.removeAll { it.id == playlistId }
        localPlaylistTracksCache.remove(playlistId)
        saveLocalPlaylists()
        LibraryChanges.notifyChanged()
    }

    fun renameLocalPlaylist(playlistId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val i = localPlaylists.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return
        val pl = localPlaylists[i]
        if (pl.name == trimmed) return
        localPlaylists[i] = pl.copy(name = trimmed, updatedAt = currentTimeMillis())
        saveLocalPlaylists()
        LibraryChanges.notifyChanged()
    }

    private fun known(t: FullTrack): M3u.Known? {
        val id = t.id ?: return null
        return M3u.Known(id, M3u.pathOf(id), t.artists.joinToString(", ") { it.name }, t.name)
    }

    /**
     * A new local playlist from an M3U/M3U8 file, its lines matched against the scanned library
     * (see [M3u.resolve]). Answers the playlist and how many lines found no track.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun importM3u(name: String, text: String): Pair<LocalPlaylist, Int> {
        val resolved = M3u.resolve(M3u.parse(text), localTracks.mapNotNull { known(it) })
        val pl = LocalPlaylist(
            id = "localpl:${Uuid.random()}",
            name = name,
            trackIds = resolved.ids.distinct()
        )
        localPlaylists.add(pl)
        saveLocalPlaylists()
        LibraryChanges.notifyChanged()
        return pl to resolved.missing.size
    }

    /** Leaves [folder] (an absolute path) and everything below it out of the library, and rescans. */
    fun excludeLocalFolder(folder: String) {
        val prefs = platform.prefs()
        prefs.putStringSet(LOCAL_EXCLUDED_FOLDERS, prefs.getStringSet(LOCAL_EXCLUDED_FOLDERS, emptySet()) + folder)
        // Out of sight at once; the rescan makes it stick.
        localTracks.removeAll { t -> t.id?.let { M3u.pathOf(it) }?.let { LocalFolders.isUnder(it, folder) } == true }
        scanLocalMusic()
    }

    /** A local playlist as M3U8, or null when it does not exist. */
    fun exportM3u(playlistId: String): String? {
        val pl = localPlaylists.firstOrNull { it.id == playlistId } ?: return null
        val tracks = localPlaylistTracks(playlistId)
        val rows = tracks.mapNotNull { t -> known(t)?.let { it to (t.durationMs / 1000) } }
        return M3u.write(pl.name, rows.map { it.first }, rows.map { it.second })
    }

    /** Resolves "local:" ids to live FullTrack objects (with cover/duration). Ignores orphans. */
    fun localPlaylistTracks(playlistId: String): List<FullTrack> {
        val byId = localTracks.associateBy { it.id }
        return localPlaylists.firstOrNull { it.id == playlistId }
            ?.trackIds?.mapNotNull { byId[it] } ?: emptyList()
    }

    init {
        instance = this

        // Trigger background hash check/warmup. Returns immediately — the scrape runs on the Rust
        // runtime — so it is the one thing here that can stay on the constructor's thread.
        NativeEngine.warmupSpotifyHashesNative()

        val cacheDirPath = platform.filesDir.fullPath

        // Load cache and trigger background sync
        repositoryScope.launch {
            // Pointing the Spotify client at its cache directory reads the stored GQL hashes off
            // disk, synchronously, inside the JNI call. This is a repository constructed from a
            // composable, so until 3.1 that file read happened on the main thread on every launch.
            NativeEngine.initSpotifyCacheDir(cacheDirPath)

            withContext(Dispatchers.IO) {
                loadLikedTracksFromCache()
loadLocalTracksFromCache()
                loadLocalUserData()   // Local playlists + favorites
                loadLibraryItemsFromCache(platform.filesDir.child("spotify_saved_playlists_cache.json"), savedPlaylists, SimplePlaylist.serializer())
                loadLibraryItemsFromCache(platform.filesDir.child("spotify_saved_albums_cache.json"), savedAlbums, FullAlbum.serializer())
                loadLibraryItemsFromCache(platform.filesDir.child("spotify_followed_artists_cache.json"), followedArtists, FullArtist.serializer())
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
        try {
            val jsonStr = file.readTextOrNull() ?: return
            val initialTracks = decodeTracks(jsonStr)
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
            file.writeAllText(encodeTracks(tracks))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun scanLocalMusic() {
        if (isScanningLocalTracks) return
        repositoryScope.launch(Dispatchers.IO) {
            isScanningLocalTracks = true
            try {
                val settingsPrefs = platform.prefs()
                val localMusicDirs = settingsPrefs.getStringSet("local_music_directories", emptySet())
                if (localMusicDirs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        localTracks.clear()
                    }
                    saveLocalTracksToCache(emptyList())
                    return@launch
                }

                // Folder view (4.10): folders the user excluded, and a floor for voice notes.
                val minDurationMs = settingsPrefs.getInt(LOCAL_MIN_DURATION_S, 0) * 1000L
                // A change of artist separators re-reads every file once: the cached track holds the
                // artists already split, not the tag they came from.
                val separators = settingsPrefs.getString(ArtistSeparators.PREF) ?: ArtistSeparators.DEFAULT
                val coversDir = platform.filesDir.child("covers").also { it.makeDirs() }
                // Covers are deduplicated per album (see the scanner). Resolution mode: full-res (default,
                // sharpest) vs downscaled 512px (smaller). Wipe & rebuild covers when the mode changes —
                // this also migrates away from the old per-track full-res scheme that bloated User Data.
                val coversFullRes = settingsPrefs.getBoolean("settings_local_covers_full_res", true)
                val coversMode = if (coversFullRes) "full" else "512"
                if (settingsPrefs.getString("covers_res_mode") != coversMode) {
                    coversDir.listChildren().forEach { runCatching { it.deleteFile() } }
                    settingsPrefs.putString("covers_res_mode", coversMode)
                }

                val scanned = scanner.scan(
                    LocalScanRequest(
                        dirs = localMusicDirs,
                        existing = localTracks.associateBy { it.id },
                        excludedFolders = settingsPrefs.getStringSet(LOCAL_EXCLUDED_FOLDERS, emptySet()),
                        separators = separators,
                        resplit = settingsPrefs.getString(SEPARATORS_APPLIED) != separators,
                        coversDir = coversDir,
                        coversFullRes = coversFullRes,
                    )
                )
                // A duration of 0 is unknown, not short: keep it.
                val newLocalTracks = scanned.filter { it.durationMs <= 0 || it.durationMs >= minDurationMs }

                // Reclaim orphaned covers (albums/tracks no longer present in the library).
                runCatching {
                    val referenced = newLocalTracks.mapNotNull {
                        it.externalUri.removePrefix("file://").substringAfterLast('/').ifBlank { null }
                    }.toHashSet()
                    coversDir.listChildren().forEach { f -> if (f.fileName !in referenced) runCatching { f.deleteFile() } }
                }

                withContext(Dispatchers.Main) {
                    localTracks.clear()
                    localTracks.addAll(newLocalTracks)
                }
                saveLocalTracksToCache(newLocalTracks)
                settingsPrefs.putString(SEPARATORS_APPLIED, separators)

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
     * Decode an engine answer into [T], refusing a failure envelope on the way.
     *
     * A failure is `{"success": false, "error": "...", "kind": "..."}` and becomes a
     * [SpotifyEngineException] rather than a parse of nonsense. The `kind` travels with it: dropping
     * it here was the whole reason the retry policy had to reconstruct the shape of the failure by
     * matching substrings of the message.
     *
     * One parse, and the payload is then built by the generated decoder instead of being walked
     * field by field — see `SpotifyModels.kt`. An array answer is not an envelope and never throws
     * here, which is what the old `checkForErrorArray` existed to say.
     */
    private inline fun <reified T> decodeChecked(json: String): T {
        val element = RustifyJson.parseToJsonElement(json)
        val obj = element as? JsonObject
        if (obj != null && obj["success"]?.jsonPrimitive?.booleanOrNull == false) {
            throw SpotifyEngineException(
                obj["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error",
                obj["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            )
        }
        return RustifyJson.decodeFromJsonElement(element)
    }

    // =========================================================================
    // AUTHENTICATION
    // =========================================================================

    /**
     * Login with a sp_dc cookie intercepted from the WebView.
     * Persists the cookie for future session restoration.
     */
    suspend fun login(spDcCookie: String): LoginResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.loginSpotify(spDcCookie)
        val result = decodeWire<LoginResult>(json)
        if (result.success) {
            prefs.putString(KEY_SP_DC, spDcCookie)
            prefs.putString(KEY_ACCESS_TOKEN, result.accessToken)
            prefs.putLong(KEY_EXPIRATION, result.expiration ?: 0L)
            triggerBackgroundSync()
        }
        result
    }

    /**
     * Logout: clears Rust state and persisted cookie/tokens. Preserves developer settings.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        NativeEngine.logoutSpotifyNative()
        prefs.remove(KEY_SP_DC)
        prefs.remove(KEY_ACCESS_TOKEN)
        prefs.remove(KEY_EXPIRATION)
        // Home is personal; the next account must not start on the previous one's.
        homeCacheFile.deleteFile()
    }

    /**
     * Check if user is currently authenticated with a valid token.
     */
    fun isAuthenticated(): Boolean = NativeEngine.isSpotifyAuthenticatedNative()

    /**
     * Recover a hot Spotify session in-memory (token expired mid-session).
     * Re-runs the native restore flow without wiping credentials on transient network errors.
     * Safe to call from any coroutine; returns true if the session is (now) valid.
     *
     * The margin is the point. [isAuthenticated] asks the engine whether the token has expired, but
     * the engine refuses to *hand one out* for the last thirty seconds before it does — deliberately,
     * so a request that is valid when it is built is still valid when it lands. Those two rules
     * disagree for half a minute in every hour, and in that window the old short-circuit answered
     * "already fine" to a call that had just been refused for being too old, so all three retries
     * went back to the same wall. Checking a full minute of remaining life means this side is never
     * more optimistic than the engine is.
     */
    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        val expiration = prefs.getLong(KEY_EXPIRATION, 0L)
        if (NativeEngine.isSpotifyAuthenticatedNative() &&
            currentTimeMillis() + SESSION_MARGIN_MS < expiration
        ) return@withContext true
        val savedCookie = prefs.getString(KEY_SP_DC) ?: ""
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
        val savedCookie = prefs.getString(KEY_SP_DC) ?: ""
        val cachedToken = prefs.getString(KEY_ACCESS_TOKEN) ?: ""
        val cachedExp = prefs.getLong(KEY_EXPIRATION, 0L)
        
        if (savedCookie.isEmpty()) {
            return@withContext null
        }
        
        val json = try {
            NativeEngine.restoreSpotifySession(savedCookie, cachedToken, cachedExp)
        } catch (e: Exception) {
            Log.w("SpotifyRepository", "restoreSession native threw: ${e.message}")
            return@withContext LoginResult(success = false, user = null, error = e.message,
                accessToken = null, expiration = null)
        }
        val result = decodeWire<LoginResult>(json)
        if (result.success) {
            prefs.putString(KEY_ACCESS_TOKEN, result.accessToken)
            prefs.putLong(KEY_EXPIRATION, result.expiration ?: 0L)
            triggerBackgroundSync()
        } else {
            // Only wipe credentials when the failure is NOT a transient network problem.
            // A network blip — or a rate limit — while restoring must not log the user out.
            //
            // This asks the engine what kind of failure it was rather than fabricating an exception
            // in order to read its message back. Worth being exact about, because the answer decides
            // whether the user's credentials are deleted: a reworded message used to be able to turn
            // "the wifi dropped" into "log them out".
            val errKind = com.varuna.rustify.util.errorKindOf(result.kind, result.error)
            if (errKind != com.varuna.rustify.util.ErrorKind.TRANSIENT &&
                errKind != com.varuna.rustify.util.ErrorKind.RATE_LIMITED
            ) {
                prefs.remove(KEY_SP_DC)
                prefs.remove(KEY_ACCESS_TOKEN)
                prefs.remove(KEY_EXPIRATION)
            }
        }
        result
    }

    /**
     * Refresh the access token.
     */
    suspend fun refreshToken(): OperationResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.refreshSpotifyToken()
        decodeWire<OperationResult>(json)
    }

    /** The saved sp_dc cookie, for handing the session to the person's own server (ServerLink); null signed out. */
    fun linkableCookie(): String? = prefs.getString(KEY_SP_DC)?.takeIf { it.isNotBlank() }

    /**
     * Check if there's a saved session available (sp_dc cookie).
     */
    fun hasSavedSession(): Boolean = 
        prefs.getString(KEY_SP_DC) != null

    // =========================================================================
    // USER / LIBRARY
    // =========================================================================

    /**
     * Get the current authenticated user's profile.
     * @throws SpotifyEngineException on API errors (rate limit, auth, etc.)
     */
    suspend fun getMe(): SpotifyUser = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyMe()
            decodeChecked<SpotifyUser>(json)
        }
    }

    /**
     * Get the user's saved/liked tracks.
     * Returns FullTrack directly (GraphQL + REST batch approach).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getSavedTracks(limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifySavedTracks(limit, offset)
            val paginated = decodeChecked<PaginatedResponse<FullTrack>>(json)
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
                    // "2026-11-04T10:20:30Z", whole seconds in UTC: SimpleDateFormat's
                    // yyyy-MM-dd'T'HH:mm:ss'Z' as it was written here, and Instant prints no fraction
                    // when there is none.
                    val nowStr = Instant.fromEpochSeconds(currentTimeMillis().floorDiv(1000L)).toString()
                    val newTrack = track.copy(addedAt = nowStr)
                    likedTracks.add(0, newTrack)
                }
                saveLikedTracksToCache()
                LibraryChanges.notifyChanged()
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
            LibraryChanges.notifyChanged()
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
            LibraryChanges.notifyChanged()
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
            LibraryChanges.notifyChanged()
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
            val json = NativeEngine.getSpotifySavedAlbums(limit, offset)
            decodeChecked<PaginatedResponse<FullAlbum>>(json)
        }
    }

    /**
     * Get the user's saved/created playlists.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getSavedPlaylists(limit: Int = 20, offset: Int = 0): PaginatedResponse<SimplePlaylist> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifySavedPlaylists(limit, offset)
            decodeChecked<PaginatedResponse<SimplePlaylist>>(json)
        }
    }

    /**
     * Get the artists the user follows.
     * Uses offset-based pagination (GraphQL libraryV3).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getFollowedArtists(limit: Int = 20, offset: Int = 0): PaginatedResponse<FullArtist> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyFollowedArtists(limit, offset)
            decodeChecked<PaginatedResponse<FullArtist>>(json)
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
            val json = NativeEngine.getSpotifyAlbum(id)
            decodeChecked<FullAlbum>(json)
        }
    }

    /**
     * Get tracks within an album.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getAlbumTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyAlbumTracks(id, limit, offset)
            decodeChecked<PaginatedResponse<FullTrack>>(json)
        }
    }

    /** Every track on an album, however many pages that takes. See [allPages]. */
    suspend fun getAllAlbumTracks(id: String): List<FullTrack> =
        allPages { limit, offset -> getAlbumTracks(id, limit, offset) }

    /** Every track in a playlist, however many pages that takes. See [allPages]. */
    suspend fun getAllPlaylistTracks(id: String): List<FullTrack> =
        allPages { limit, offset -> getPlaylistTracks(id, limit, offset) }

    /**
     * Every page of a paginated track endpoint, not just the first.
     *
     * This is the answer to a whole family of the same bug: a screen holds "the tracks it has
     * scrolled to", and everything downstream reads that as "the album". Two of those shipped —
     * resuming a long playlist landed on track 1 because the track it wanted was never fetched, and
     * "add all to a playlist" on a 142-track list added the 50 that happened to be on screen and
     * reported success. The second one is worse than it sounds: the other 92 are not missing, they
     * are *silently* missing, and undoing it means deleting 50 songs by hand.
     *
     * Bounded at 1 000 tracks. That bound is a real limit and not a formality, so anything relying
     * on it should say so rather than claim completeness.
     */
    private suspend fun allPages(
        page: suspend (limit: Int, offset: Int) -> PaginatedResponse<FullTrack>
    ): List<FullTrack> {
        val pageSize = 50
        val maxPages = 20
        val all = mutableListOf<FullTrack>()
        var offset = 0
        repeat(maxPages) {
            val response = page(pageSize, offset)
            all.addAll(response.items)
            val next = response.nextOffset
            if (!response.hasMore || next == null || response.items.isEmpty()) return all
            offset = next
        }
        return all
    }

    /**
     * Get newly released albums.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getNewReleases(limit: Int = 20, offset: Int = 0): PaginatedResponse<SimpleAlbum> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyNewReleases(limit, offset)
            decodeChecked<PaginatedResponse<SimpleAlbum>>(json)
        }
    }

    /**
     * Save albums to the user's library.
     */
    suspend fun saveAlbums(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.saveSpotifyAlbums(idsJson(ids)) }
    }

    /**
     * Remove albums from the user's library.
     */
    suspend fun unsaveAlbums(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.unsaveSpotifyAlbums(idsJson(ids)) }
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
            val json = NativeEngine.getSpotifyArtist(id)
            decodeChecked<FullArtist>(json)
        }
    }

    /**
     * Get an artist's top tracks.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getArtistTopTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyArtistTopTracks(id, limit, offset)
            decodeChecked<PaginatedResponse<FullTrack>>(json)
        }
    }

    /**
     * Get albums by an artist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getArtistAlbums(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<SimpleAlbum> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyArtistAlbums(id, limit, offset)
            decodeChecked<PaginatedResponse<SimpleAlbum>>(json)
        }
    }

    /**
     * Get artists related to a given artist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getRelatedArtists(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullArtist> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyRelatedArtists(id, limit, offset)
            decodeChecked<PaginatedResponse<FullArtist>>(json)
        }
    }

    /**
     * Follow artists.
     */
    suspend fun followArtists(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.followSpotifyArtists(idsJson(ids)) }
    }

    /**
     * Unfollow artists.
     */
    suspend fun unfollowArtists(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.unfollowSpotifyArtists(idsJson(ids)) }
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
            val json = NativeEngine.getSpotifyPlaylist(id)
            decodeChecked<FullPlaylist>(json)
        }
    }

    /**
     * Get tracks within a playlist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getPlaylistTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyPlaylistTracks(id, limit, offset)
            decodeChecked<PaginatedResponse<FullTrack>>(json)
        }
    }

    /**
     * Create a new playlist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun createPlaylist(userId: String, name: String, description: String = "", public: Boolean = false): FullPlaylist = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.createSpotifyPlaylist(userId, name, description, public)
            decodeChecked<FullPlaylist>(json)
        }
    }

    /**
     * Runs a native call that answers with an `OperationResult`, refreshing the session and retrying
     * when it fails for a recoverable reason.
     *
     * These endpoints report failure in their payload instead of throwing, so they slipped past
     * [retrying] entirely — which is how "add to playlist" could come back `401 Missing/invalid/
     * expired access token` and simply stay failed, with a session that one refresh would have
     * fixed. Turning the failed result into a throw puts it back under the same policy every read
     * already follows, and the result is rebuilt for the caller at the end.
     *
     * **Every write goes through here.** When this was written only three of the twelve were moved
     * onto it, and the other nine — the heart button, saving an album, following an artist or a
     * playlist — kept the exact bug it was built to fix. The symptom is specific and was reported
     * from a phone: leave the app open for an hour and liking a song stops working *silently*,
     * everywhere at once (notification, miniplayer, track screen), until the app is restarted. It
     * silently is the operative word: a read that fails this way throws and something shows an
     * error, whereas these answer `success = false` and the caller simply does nothing.
     *
     * The only write that must **not** come through here is [refreshToken], which is the recovery
     * itself.
     */
    // `suspend () -> String` since 3.1: the native write calls are suspending now, which is the
    // point of point N — a JNI call blocks its thread, so reaching one has to be something the
    // compiler can see.
    private suspend fun writeOperation(call: suspend () -> String): OperationResult =
        runCatching {
            retrying(onAuthError = { ensureSession() }) {
                val res = decodeWire<OperationResult>(call())
                if (!res.success) {
                    throw SpotifyEngineException(res.error ?: "operation failed", res.kind)
                }
                res
            }
        }.getOrElse { OperationResult(success = false, error = it.message) }

    /**
     * Update an existing playlist.
     */
    suspend fun updatePlaylist(id: String, name: String = "", description: String = ""): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.updateSpotifyPlaylist(id, name, description) }
    }

    /**
     * Add tracks to a playlist.
     */
    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>, position: Int = -1): OperationResult = withContext(Dispatchers.IO) {
        writeOperation {
            NativeEngine.addTracksToPlaylist(playlistId, idsJson(trackIds), position)
        }
    }

    /**
     * Add many tracks to a playlist (e.g. "add whole album/playlist to a playlist").
     *
     * The Rust `add_tracks_to_playlist` sends every uri in a single POST, but the Spotify REST
     * endpoint accepts at most 100 uris per call. So we chunk [trackIds] into batches of 100 and
     * call [addTracksToPlaylist] per chunk, accumulating how many were successfully added.
     *
     * @return number of tracks added.
     * @throws SpotifyEngineException if a chunk fails (the UI reports the partial count via the message).
     */
    suspend fun addAllTracksToPlaylist(playlistId: String, trackIds: List<String>): Int = withContext(Dispatchers.IO) {
        var added = 0
        // Filter out blanks / local tracks (no valid Spotify uri).
        val validIds = trackIds.filter { it.isNotBlank() && !TrackRef.isLocal(it) }
        for (chunk in validIds.chunked(100)) {
            val res = addTracksToPlaylist(playlistId, chunk)
            if (res.success) {
                added += chunk.size
            } else {
                // Surface partial progress in the error so the UI can inform the user.
                throw SpotifyEngineException(
                    res.error ?: "add_tracks_to_playlist failed after $added tracks",
                    res.kind
                )
            }
        }
        added
    }

    /**
     * Remove tracks from a playlist.
     */
    suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>): OperationResult = withContext(Dispatchers.IO) {
        writeOperation {
            NativeEngine.removeTracksFromPlaylist(playlistId, idsJson(trackIds))
        }
    }

    /**
     * Follow/save a playlist.
     */
    suspend fun followPlaylist(id: String): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.followPlaylist(id) }
    }

    /**
     * Unfollow/unsave a playlist.
     */
    suspend fun unfollowPlaylist(id: String): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.unfollowPlaylist(id) }
    }

    // =========================================================================
    // TRACKS
    // =========================================================================

    /**
     * Get full track details.
     *
     * The auth retry is not optional here: this is what a shared `open.spotify.com/track/…` link
     * lands on, and opening a link is exactly the case where the app has been closed for days and
     * the access token is long dead. Without it the expired token went out as-is and the raw
     * `API error 401: Missing/invalid/expired access token` was rendered on the track screen —
     * which reads as "the link expired", when the link is a plain URL that carries no token at all.
     *
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getTrack(id: String): FullTrack = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyTrack(id)
            decodeChecked<FullTrack>(json)
        }
    }

    /**
     * Save tracks to the user's library — the heart button.
     */
    suspend fun saveTracks(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.saveSpotifyTracks(idsJson(ids)) }
    }

    /**
     * Remove tracks from the user's library.
     */
    suspend fun unsaveTracks(ids: List<String>): OperationResult = withContext(Dispatchers.IO) {
        writeOperation { NativeEngine.unsaveSpotifyTracks(idsJson(ids)) }
    }

    /**
     * The Spotify Canvas for a track — the short looping mp4 shown behind the cover art.
     *
     * `null` means "this track has no canvas", which is the common answer and not a problem. A
     * failure is a different thing and is **not** flattened into `null` here: it throws, so a caller
     * that cares can tell the two apart. The track screen still shows no tab either way — there is
     * nothing useful to offer — but it now logs, because the two used to be indistinguishable and
     * that is why "the canvas tab stopped appearing" had no evidence behind it.
     *
     * The retry is the point: the canvas endpoint needs the **user** token (an app token cannot ask
     * for it), so it is one of the calls that simply stops working when the session ages out. It was
     * reaching the engine raw.
     *
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getTrackCanvas(trackId: String): String? = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = parseObject(NativeEngine.getSpotifyCanvas(trackId))
            // The bridge answers `{"url":…}` on success and an OperationResult on failure, so a
            // missing "url" key is the failure shape rather than "no canvas".
            val url = json["url"]
                ?: throw SpotifyEngineException(
                    json["error"].optString("canvas lookup failed"),
                    json["kind"].optString().takeIf { it.isNotBlank() }
                )
            if (url is JsonNull) null else url.optString().takeIf { it.isNotBlank() }
        }
    }

    /**
     * Get track radio (list of tracks from a matching radio playlist).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getTrackRadio(trackId: String): List<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.getSpotifyTrackRadio(trackId)
            decodeChecked<List<FullTrack>>(json)
        }
    }

    /** An artist's biography, listeners, top cities and upcoming concerts. No account needed. */
    suspend fun getArtistAbout(artistId: String): ArtistAbout = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            decodeChecked<ArtistAbout>(NativeEngine.getArtistAbout(artistId))
        }
    }

    /** Who wrote, played and produced a track. Public catalogue: no account needed. */
    suspend fun getTrackCredits(trackId: String): TrackCredits = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            decodeChecked<TrackCredits>(NativeEngine.getTrackCredits(trackId))
        }
    }

    /**
     * Tracks that would fit [playlistId] — Spotify's "Recommended" block under a playlist. [skip] is
     * what is already in it and what was already offered; the service leaves those out itself.
     */
    suspend fun extendPlaylist(playlistId: String, skip: List<String>, count: Int = 10): List<FullTrack> =
        withContext(Dispatchers.IO) {
            val skipJson = RustifyJson.encodeToString(skip)
            retrying(onAuthError = { ensureSession() }) {
                decodeChecked<List<FullTrack>>(NativeEngine.extendPlaylist(playlistId, skipJson, count))
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
            val json = NativeEngine.searchSpotify(query, "all", limit, 0)
            decodeChecked<NormalizedSearchResults>(json)
        }
    }

    /**
     * Search for tracks only.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchTracks(query: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.searchSpotify(query, "tracks", limit, offset)
            decodeChecked<PaginatedResponse<FullTrack>>(json)
        }
    }

    /**
     * Search for albums only.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchAlbums(query: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<SimpleAlbum> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.searchSpotify(query, "albums", limit, offset)
            decodeChecked<PaginatedResponse<SimpleAlbum>>(json)
        }
    }

    /**
     * Search for artists only.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchArtists(query: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullArtist> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.searchSpotify(query, "artists", limit, offset)
            decodeChecked<PaginatedResponse<FullArtist>>(json)
        }
    }

    /**
     * Search for playlists only.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchPlaylists(query: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<SimplePlaylist> = withContext(Dispatchers.IO) {
        retrying(onAuthError = { ensureSession() }) {
            val json = NativeEngine.searchSpotify(query, "playlists", limit, offset)
            decodeChecked<PaginatedResponse<SimplePlaylist>>(json)
        }
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
            val json = NativeEngine.getSpotifyBrowse(limit)
            decodeChecked<List<BrowseSection>>(json).also { sections ->
                // Kept for the next start without network: Home used to be a spinner and then an
                // error with nothing else on screen until a connection came back.
                if (sections.isNotEmpty()) runCatching { homeCacheFile.writeTextAtomically(json) }
            }
        }
    }

    /** Home as it was the last time it loaded, or null when it never has. Reads disk only. */
    suspend fun cachedBrowseSections(): List<BrowseSection>? = withContext(Dispatchers.IO) {
        runCatching {
            homeCacheFile.readTextOrNull()?.let { decodeChecked<List<BrowseSection>>(it) }
        }.getOrNull()
    }

    /**
     * Find a local music track that matches the given Spotify track.
     *
     * The rule — ISRC, then folded title, then duration, then artist — is
     * `core_engine/src/matcher/local.rs`, and it is asked rather than repeated here. What comes back
     * is an id, which is then located in the list this side already holds.
     */
    /** The ids a write bridge takes, as the JSON array `JSONArray(ids)` printed. */
    private fun idsJson(ids: List<String>): String = RustifyJson.encodeToString(ids)

    fun findLocalMatch(track: FullTrack): FullTrack? {
        val pool = localTracks.toList()
        LocalMatcher.ensureRegistered("repo", pool)
        val id = LocalMatcher.matchId(track) ?: return null
        return pool.firstOrNull { it.id == id }
    }
}
