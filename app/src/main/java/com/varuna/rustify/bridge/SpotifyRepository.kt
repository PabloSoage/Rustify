// app/src/main/java/com/varuna/rustify/bridge/SpotifyRepository.kt
package com.varuna.rustify.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
class SpotifyRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "rustify_spotify_prefs"
        private const val KEY_SP_DC = "sp_dc_cookie"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_EXPIRATION = "expiration_timestamp"
        private const val KEY_CLIENT_ID = "developer_client_id"
        private const val KEY_CLIENT_SECRET = "developer_client_secret"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val likedTrackIds = mutableStateMapOf<String, Boolean>()

    fun isTrackLiked(id: String): Boolean = likedTrackIds[id] == true

    fun setTrackLikedState(id: String, liked: Boolean) {
        likedTrackIds[id] = liked
    }

    init {
        // Load stored developer credentials or fall back to defaults (bypass 429 errors)
        val clientId = prefs.getString(KEY_CLIENT_ID, "4c97a4b21a07409ea4017e889d701ab0") ?: "4c97a4b21a07409ea4017e889d701ab0"
        val clientSecret = prefs.getString(KEY_CLIENT_SECRET, "049386348c4146059d646b9a89d7fa2a") ?: "049386348c4146059d646b9a89d7fa2a"
        NativeEngine.setDeveloperCredentials(clientId, clientSecret)

        // Initialize cache directory in Rust engine
        val cacheDirPath = context.cacheDir.absolutePath
        NativeEngine.initSpotifyCacheDirNative(cacheDirPath)

        // Trigger background hash check/warmup
        NativeEngine.warmupSpotifyHashesNative()
    }

    /**
     * Sets custom developer credentials, updates the native engine, and persists them in SharedPreferences.
     */
    fun setDeveloperCredentials(clientId: String, clientSecret: String) {
        prefs.edit().apply {
            putString(KEY_CLIENT_ID, clientId)
            putString(KEY_CLIENT_SECRET, clientSecret)
        }.apply()
        NativeEngine.setDeveloperCredentials(clientId, clientSecret)
    }

    /**
     * Gets the saved developer Client ID, if configured.
     */
    fun getDeveloperClientId(): String? {
        val stored = prefs.getString(KEY_CLIENT_ID, null)
        return if (stored == "4c97a4b21a07409ea4017e889d701ab0") null else stored
    }

    /**
     * Gets the saved developer Client Secret, if configured.
     */
    fun getDeveloperClientSecret(): String? {
        val stored = prefs.getString(KEY_CLIENT_SECRET, null)
        return if (stored == "049386348c4146059d646b9a89d7fa2a") null else stored
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
     * Login with an sp_dc cookie intercepted from the WebView.
     * Persists the cookie for future session restoration.
     */
    suspend fun login(spDcCookie: String): LoginResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.loginSpotifyNative(spDcCookie)
        val result = LoginResult.fromJson(JSONObject(json))
        if (result.success) {
            prefs.edit().apply {
                putString(KEY_SP_DC, spDcCookie)
                putString(KEY_ACCESS_TOKEN, result.accessToken)
                putLong(KEY_EXPIRATION, result.expiration ?: 0L)
            }.apply()
        }
        result
    }

    /**
     * Logout: clears Rust state and persisted cookie/tokens. Preserves developer settings.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        NativeEngine.logoutSpotifyNative()
        prefs.edit().apply {
            remove(KEY_SP_DC)
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_EXPIRATION)
            remove(KEY_REFRESH_TOKEN)
        }.apply()
    }

    /**
     * Check if user is currently authenticated with a valid token.
     */
    fun isAuthenticated(): Boolean = NativeEngine.isSpotifyAuthenticatedNative()

    /**
     * Login using OAuth Authorization Code obtained from redirect.
     */
    suspend fun loginWithAuthCode(code: String, redirectUri: String): LoginResult = withContext(Dispatchers.IO) {
        val json = NativeEngine.loginSpotifyWithAuthCodeNative(code, redirectUri)
        val result = LoginResult.fromJson(JSONObject(json))
        if (result.success) {
            prefs.edit().apply {
                putString(KEY_ACCESS_TOKEN, result.accessToken)
                putLong(KEY_EXPIRATION, result.expiration ?: 0L)
                if (result.refreshToken != null && result.refreshToken.isNotEmpty()) {
                    putString(KEY_REFRESH_TOKEN, result.refreshToken)
                }
            }.apply()
        }
        result
    }

    /**
     * Attempt to restore a previous session from the saved sp_dc cookie or OAuth refresh token.
     * Call this on app startup to auto-login.
     * @return LoginResult if a saved cookie or refresh token exists, null if no saved session.
     */
    suspend fun restoreSession(): LoginResult? = withContext(Dispatchers.IO) {
        val savedCookie = prefs.getString(KEY_SP_DC, "") ?: ""
        val cachedToken = prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""
        val cachedExp = prefs.getLong(KEY_EXPIRATION, 0L)
        val savedRefreshToken = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        
        if (savedCookie.isEmpty() && savedRefreshToken.isEmpty()) {
            return@withContext null
        }
        
        val json = NativeEngine.restoreSpotifySessionNative(savedCookie, cachedToken, cachedExp, savedRefreshToken)
        val result = LoginResult.fromJson(JSONObject(json))
        if (result.success) {
            prefs.edit().apply {
                putString(KEY_ACCESS_TOKEN, result.accessToken)
                putLong(KEY_EXPIRATION, result.expiration ?: 0L)
                if (result.refreshToken != null && result.refreshToken.isNotEmpty()) {
                    putString(KEY_REFRESH_TOKEN, result.refreshToken)
                }
            }.apply()
        } else {
            // Cookie and tokens are no longer valid, clear session (preserve developer settings)
            prefs.edit().apply {
                remove(KEY_SP_DC)
                remove(KEY_ACCESS_TOKEN)
                remove(KEY_EXPIRATION)
                remove(KEY_REFRESH_TOKEN)
            }.apply()
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
     * Check if there's a saved session available (either sp_dc cookie or refresh token).
     */
    fun hasSavedSession(): Boolean = 
        (prefs.getString(KEY_SP_DC, null) != null) || (prefs.getString(KEY_REFRESH_TOKEN, null) != null)

    // =========================================================================
    // USER / LIBRARY
    // =========================================================================

    /**
     * Get the current authenticated user's profile.
     * @throws SpotifyEngineException on API errors (rate limit, auth, etc.)
     */
    suspend fun getMe(): SpotifyUser = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyMeNative()
        SpotifyUser.fromJson(checkForError(json))
    }

    /**
     * Get the user's saved/liked tracks.
     * Returns FullTrack directly (GraphQL + REST batch approach).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getSavedTracks(limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
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

    /**
     * Check if a list of tracks is saved/liked in the user's library.
     */
    suspend fun checkSavedTracks(ids: List<String>): List<Boolean> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val json = NativeEngine.checkSpotifySavedTracksNative(JSONArray(ids).toString())
        val array = JSONArray(checkForErrorArray(json))
        List(array.length()) { array.getBoolean(it) }
    }

    /**
     * Check and cache liked status for a batch of tracks.
     */
    suspend fun checkAndCacheLikedStates(trackIds: List<String>) {
        val unknownIds = trackIds.filter { it.isNotEmpty() && !likedTrackIds.containsKey(it) }
        if (unknownIds.isEmpty()) return
        try {
            unknownIds.chunked(50).forEach { chunk ->
                val contains = checkSavedTracks(chunk)
                withContext(Dispatchers.Main) {
                    chunk.zip(contains).forEach { (id, isLiked) ->
                        likedTrackIds[id] = isLiked
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Toggle like/unlike status for a track.
     */
    suspend fun toggleLikeTrack(id: String): OperationResult = withContext(Dispatchers.IO) {
        val currentlyLiked = isTrackLiked(id)
        val result = if (currentlyLiked) {
            unsaveTracks(listOf(id))
        } else {
            saveTracks(listOf(id))
        }
        if (result.success) {
            withContext(Dispatchers.Main) {
                likedTrackIds[id] = !currentlyLiked
            }
        }
        result
    }

    /**
     * Get the user's saved albums.
     * Returns FullAlbum directly (GraphQL + REST batch approach).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getSavedAlbums(limit: Int = 20, offset: Int = 0): PaginatedResponse<FullAlbum> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifySavedAlbumsNative(limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { FullAlbum.fromJson(it) }
    }

    /**
     * Get the user's saved/created playlists.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getSavedPlaylists(limit: Int = 20, offset: Int = 0): PaginatedResponse<SimplePlaylist> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifySavedPlaylistsNative(limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { SimplePlaylist.fromJson(it) }
    }

    /**
     * Get the artists the user follows.
     * Now uses offset-based pagination (GraphQL libraryV3).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getFollowedArtists(limit: Int = 20, offset: Int = 0): PaginatedResponse<FullArtist> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyFollowedArtistsNative(limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { FullArtist.fromJson(it) }
    }

    // =========================================================================
    // ALBUMS
    // =========================================================================

    /**
     * Get full album details.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getAlbum(id: String): FullAlbum = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyAlbumNative(id)
        FullAlbum.fromJson(checkForError(json))
    }

    /**
     * Get tracks within an album.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getAlbumTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyAlbumTracksNative(id, limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { FullTrack.fromJson(it) }
    }

    /**
     * Get newly released albums.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getNewReleases(limit: Int = 20, offset: Int = 0): PaginatedResponse<SimpleAlbum> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyNewReleasesNative(limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { SimpleAlbum.fromJson(it) }
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
        val json = NativeEngine.getSpotifyArtistNative(id)
        FullArtist.fromJson(checkForError(json))
    }

    /**
     * Get an artist's top tracks.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getArtistTopTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyArtistTopTracksNative(id, limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { FullTrack.fromJson(it) }
    }

    /**
     * Get albums by an artist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getArtistAlbums(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<SimpleAlbum> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyArtistAlbumsNative(id, limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { SimpleAlbum.fromJson(it) }
    }

    /**
     * Get artists related to a given artist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getRelatedArtists(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullArtist> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyRelatedArtistsNative(id, limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { FullArtist.fromJson(it) }
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
        val json = NativeEngine.getSpotifyPlaylistNative(id)
        FullPlaylist.fromJson(checkForError(json))
    }

    /**
     * Get tracks within a playlist.
     * @throws SpotifyEngineException on API errors
     */
    suspend fun getPlaylistTracks(id: String, limit: Int = 20, offset: Int = 0): PaginatedResponse<FullTrack> = withContext(Dispatchers.IO) {
        val json = NativeEngine.getSpotifyPlaylistTracksNative(id, limit, offset)
        PaginatedResponse.fromJson(checkForError(json)) { FullTrack.fromJson(it) }
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
        val json = NativeEngine.getSpotifyTrackRadioNative(trackId)
        FullTrack.listFromJsonArray(checkForErrorArray(json))
    }

    // =========================================================================
    // SEARCH
    // =========================================================================

    /**
     * Search all types (tracks, albums, artists, playlists).
     * @throws SpotifyEngineException on API errors
     */
    suspend fun searchAll(query: String, limit: Int = 20): NormalizedSearchResults = withContext(Dispatchers.IO) {
        val json = NativeEngine.searchSpotifyNative(query, "all", limit, 0)
        NormalizedSearchResults.fromJson(checkForError(json))
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
        val json = NativeEngine.getSpotifyBrowseNative(limit)
        BrowseSection.listFromJsonArray(checkForErrorArray(json))
    }
}
