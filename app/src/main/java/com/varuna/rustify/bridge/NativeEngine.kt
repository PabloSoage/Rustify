// app/src/main/java/com/varuna/rustify/bridge/NativeEngine.kt

package com.varuna.rustify.bridge

/**
 * JNI bridge to the Rust core_engine library.
 * All functions delegate to native Rust implementations for maximum performance.
 * Strings are passed as JSON for complex data structures.
 */
object NativeEngine {
    init {
        System.loadLibrary("core_engine")
    }

    // =====================================================================
    // YOUTUBE ENGINE
    // =====================================================================

    /**
     * Searches for tracks in YouTube Music using the InnerTube API via Rust.
     * @param query Example: "USUM71700966" or "Bohemian Rhapsody Queen".
     * @return A JSON string containing a list of YouTubeTrack objects.
     */
    external fun searchYouTubeNative(query: String): String

    /**
     * Resolves a Spotify track ID to a YouTube video ID.
     */
    external fun resolveYouTubeIdNative(spotifyId: String, youtubeId: String): String

    /**
     * Initializes the YouTube resolver cache directory and loads persisted
     * spotify-id -> youtube-id mappings. (Replaces the removed loopback HTTP server, E11.)
     * @param cacheDir Absolute path of the application cache directory.
     */
    external fun initCacheDirNative(cacheDir: String)

    /**
     * Registers a Spotify track's metadata to memory to enable automatic YouTube Music matching.
     */
    external fun registerTrackMetadataNative(id: String, name: String, artistsJson: String, durationMs: Int, isrc: String)

    /**
     * Manually overrides the YouTube video ID mapping for a given Spotify track ID.
     */
    external fun setAlternativeTrackNative(spotifyId: String, youtubeId: String)

    /**
     * Returns the user-confirmed alternative YouTube ID for a Spotify track,
     * or an empty string if no mapping exists.
     */
    external fun getAlternativeTrackNative(spotifyId: String): String



    /**
     * Notifies the Rust engine of the current playback queue to schedule pre-buffering.
     * @param trackIdsJson JSON array of Spotify track IDs.
     */
    external fun updateQueueNative(trackIdsJson: String)

    /**
     * Sets the Accept-Language header for the Spotify Client
     */
    external fun setLanguageNative(langCode: String)

    // =====================================================================
    // SPOTIFY — AUTHENTICATION
    // =====================================================================

    /**
     * Authenticates the user in the Spotify API using the intercepted session cookie.
     * Performs the full TOTP-based token acquisition flow.
     * @param spDcCookie The raw value of the 'sp_dc' cookie obtained from the WebView.
     * @return A JSON string: {"success": true, "user": {...}} or {"success": false, "error": "..."}.
     */
    external fun loginSpotifyNative(spDcCookie: String): String

    /**
     * Logs out the user by clearing the Rust session state and in-memory credentials.
     */
    external fun logoutSpotifyNative()

    /**
     * Refreshes the Spotify access token using the stored sp_dc cookie.
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun refreshSpotifyTokenNative(): String

    /**
     * Checks if the user is currently authenticated with a valid (non-expired) token.
     * @return true if authenticated and token is valid, false otherwise.
     */
    external fun isSpotifyAuthenticatedNative(): Boolean

    /**
     * Restores a previous session from a saved sp_dc cookie or OAuth refresh token (called on app start).
     * @param spDcCookie The saved sp_dc cookie value.
     * @param accessToken The saved access token.
     * @param expirationMs The expiration timestamp in milliseconds.
     * @return A JSON string: {"success": true, "user": {...}} or {"success": false, "error": "..."}.
     */
    external fun restoreSpotifySessionNative(spDcCookie: String, accessToken: String, expirationMs: Long): String



    // =====================================================================
    // SPOTIFY — USER / LIBRARY
    // =====================================================================

    /**
     * Fetches the current authenticated user's profile.
     * @return A JSON string with user details (id, display_name, images, etc.).
     */
    external fun getSpotifyMeNative(): String

    /**
     * Fetches the user's liked songs (saved tracks) from their personal library.
     * @param limit Number of tracks to retrieve (maximum 50).
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<SavedTrackItem>.
     */
    external fun getSpotifySavedTracksNative(limit: Int, offset: Int): String

    /**
     * Fetches the user's saved albums from their personal library.
     * @param limit Number of albums to retrieve (maximum 50).
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<SavedAlbumItem>.
     */
    external fun getSpotifySavedAlbumsNative(limit: Int, offset: Int): String

    /**
     * Fetches the user's saved/created playlists.
     * @param limit Number of playlists to retrieve (maximum 50).
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<SimplePlaylist>.
     */
    external fun getSpotifySavedPlaylistsNative(limit: Int, offset: Int): String

    /**
     * Fetches the artists the user follows.
     * Now uses offset-based pagination via GraphQL libraryV3.
     * @param limit Number of artists to retrieve (maximum 50).
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<FullArtist>.
     */
    external fun getSpotifyFollowedArtistsNative(limit: Int, offset: Int): String

    // =====================================================================
    // SPOTIFY — ALBUMS
    // =====================================================================

    /**
     * Fetches full details for a specific album.
     * @param albumId The Spotify album ID.
     * @return A JSON string representing FullAlbum.
     */
    external fun getSpotifyAlbumNative(albumId: String): String

    /**
     * Fetches the tracks within a specific album.
     * @param albumId The Spotify album ID.
     * @param limit Number of tracks to retrieve.
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<FullTrack>.
     */
    external fun getSpotifyAlbumTracksNative(albumId: String, limit: Int, offset: Int): String

    /**
     * Fetches newly released albums.
     * @param limit Number of albums to retrieve.
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<SimpleAlbum>.
     */
    external fun getSpotifyNewReleasesNative(limit: Int, offset: Int): String

    /**
     * Saves albums to the user's library.
     * @param idsJson JSON array of album IDs: ["id1", "id2"].
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun saveSpotifyAlbumsNative(idsJson: String): String

    /**
     * Removes albums from the user's library.
     * @param idsJson JSON array of album IDs: ["id1", "id2"].
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun unsaveSpotifyAlbumsNative(idsJson: String): String

    // =====================================================================
    // SPOTIFY — ARTISTS
    // =====================================================================

    /**
     * Fetches full details for a specific artist.
     * @param artistId The Spotify artist ID.
     * @return A JSON string representing FullArtist.
     */
    external fun getSpotifyArtistNative(artistId: String): String

    /**
     * Fetches an artist's top tracks.
     * @param artistId The Spotify artist ID.
     * @param limit Number of tracks to retrieve.
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<FullTrack>.
     */
    external fun getSpotifyArtistTopTracksNative(artistId: String, limit: Int, offset: Int): String

    /**
     * Fetches albums by a specific artist.
     * @param artistId The Spotify artist ID.
     * @param limit Number of albums to retrieve.
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<SimpleAlbum>.
     */
    external fun getSpotifyArtistAlbumsNative(artistId: String, limit: Int, offset: Int): String

    /**
     * Fetches artists related to a given artist.
     * @param artistId The Spotify artist ID.
     * @param limit Number of artists to retrieve.
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<FullArtist>.
     */
    external fun getSpotifyRelatedArtistsNative(artistId: String, limit: Int, offset: Int): String

    /**
     * Follows artists.
     * @param idsJson JSON array of artist IDs: ["id1", "id2"].
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun followSpotifyArtistsNative(idsJson: String): String

    /**
     * Unfollows artists.
     * @param idsJson JSON array of artist IDs: ["id1", "id2"].
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun unfollowSpotifyArtistsNative(idsJson: String): String

    // =====================================================================
    // SPOTIFY — PLAYLISTS
    // =====================================================================

    /**
     * Fetches full details for a specific playlist.
     * @param playlistId The Spotify playlist ID.
     * @return A JSON string representing FullPlaylist.
     */
    external fun getSpotifyPlaylistNative(playlistId: String): String

    /**
     * Fetches tracks within a specific playlist.
     * @param playlistId The Spotify playlist ID.
     * @param limit Number of tracks to retrieve.
     * @param offset Pagination index.
     * @return A JSON string representing PaginatedResponse<FullTrack>.
     */
    external fun getSpotifyPlaylistTracksNative(playlistId: String, limit: Int, offset: Int): String

    /**
     * Creates a new playlist for the authenticated user.
     * @param userId The user's Spotify ID.
     * @param name Playlist name.
     * @param description Playlist description.
     * @param isPublic Whether the playlist should be public.
     * @return A JSON string representing the created FullPlaylist.
     */
    external fun createSpotifyPlaylistNative(userId: String, name: String, description: String, isPublic: Boolean): String

    /**
     * Updates an existing playlist's details.
     * @param playlistId The Spotify playlist ID.
     * @param name New name (empty string to skip).
     * @param description New description (empty string to skip).
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun updateSpotifyPlaylistNative(playlistId: String, name: String, description: String): String

    /**
     * Adds tracks to a playlist.
     * @param playlistId The Spotify playlist ID.
     * @param trackIdsJson JSON array of track IDs: ["id1", "id2"].
     * @param position Position to insert at (-1 to append).
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun addTracksToPlaylistNative(playlistId: String, trackIdsJson: String, position: Int): String

    /**
     * Removes tracks from a playlist.
     * @param playlistId The Spotify playlist ID.
     * @param trackIdsJson JSON array of track IDs: ["id1", "id2"].
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun removeTracksFromPlaylistNative(playlistId: String, trackIdsJson: String): String

    /**
     * Follows/saves a playlist to the user's library.
     * @param playlistId The Spotify playlist ID.
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun followPlaylistNative(playlistId: String): String

    /**
     * Unfollows/removes a playlist from the user's library.
     * @param playlistId The Spotify playlist ID.
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun unfollowPlaylistNative(playlistId: String): String

    // =====================================================================
    // SPOTIFY — TRACKS
    // =====================================================================

    /**
     * Fetches full details for a specific track.
     * @param trackId The Spotify track ID.
     * @return A JSON string representing FullTrack.
     */
    external fun getSpotifyTrackNative(trackId: String): String

    /**
     * Saves tracks to the user's library (liked songs).
     * @param idsJson JSON array of track IDs: ["id1", "id2"].
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun saveSpotifyTracksNative(idsJson: String): String

    /**
     * Removes tracks from the user's library.
     * @param idsJson JSON array of track IDs: ["id1", "id2"].
     * @return A JSON string: {"success": true} or {"success": false, "error": "..."}.
     */
    external fun unsaveSpotifyTracksNative(idsJson: String): String

    /**
     * Generates a "radio" for a track by finding a matching radio playlist.
     * @param trackId The Spotify track ID.
     * @return A JSON string representing a list of FullTrack.
     */
    external fun getSpotifyTrackRadioNative(trackId: String): String

    /**
     * Fetches the Spotify Canvas (short looping mp4 shown behind the cover art)
     * for a track. Accepts a track ID or a full `spotify:track:<id>` URI.
     * @return A JSON string: {"url":"<mp4>"} when a canvas exists, {"url":null}
     *         when the track has no canvas, or {"success":false,"error":"..."}.
     */
    external fun getSpotifyCanvasNative(trackUri: String): String

    // =====================================================================
    // SPOTIFY — SEARCH
    // =====================================================================

    /**
     * Searches Spotify for content matching the query.
     * @param query The search query string.
     * @param searchType One of: "all", "tracks", "albums", "artists", "playlists".
     * @param limit Number of results per type.
     * @param offset Pagination index (not used for "all").
     * @return A JSON string - NormalizedSearchResults for "all", or PaginatedResponse for specific types.
     */
    external fun searchSpotifyNative(query: String, searchType: String, limit: Int, offset: Int): String

    // =====================================================================
    // SPOTIFY — BROWSE
    // =====================================================================

    /**
     * Fetches browse/home sections with featured playlists and new releases.
     * @param limit Number of items per section.
     * @return A JSON string representing a list of BrowseSection.
     */
    external fun getSpotifyBrowseNative(limit: Int): String

    /**
     * Initializes the cache directory for the Spotify client in Rust.
     */
    external fun initSpotifyCacheDirNative(cacheDir: String)

    /**
     * Triggers the background scrape/warmup of Spotify GraphQL hashes in Rust.
     */
    external fun warmupSpotifyHashesNative()

    /**
     * Returns a JSON object mapping GQL operation names to their current sha256 hashes.
     * Example: `{"libraryV3":"2de10199b244...","fetchLibraryTracks":"087278b20b74..."}`
     * Returns `{}` if no hashes are cached yet.
     */
    external fun getSpotifyHashesNative(): String


    // YOUTUBE MUSIC (E40)
    // =====================================================================

    external fun searchYtMusicNative(query: String): String
    external fun getYtmAlbumNative(browseId: String): String
    external fun getYtmArtistNative(channelId: String): String
    external fun getYtmPlaylistNative(playlistId: String): String
    external fun getYtmRadioNative(videoId: String): String

}