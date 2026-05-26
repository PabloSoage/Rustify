// app/src/main/java/com/varuna/rustify/bridge/SpotifyModels.kt
package com.varuna.rustify.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin data classes that mirror the Rust/Spotify JSON structures.
 * All classes include companion factory methods to parse from JSONObject.
 */

// =============================================================================
// COMMON
// =============================================================================

data class SpotifyImage(
    val url: String,
    val height: Int?,
    val width: Int?
) {
    companion object {
        fun fromJson(json: JSONObject): SpotifyImage = SpotifyImage(
            url = json.optString("url", ""),
            height = if (json.has("height") && !json.isNull("height")) json.optInt("height") else null,
            width = if (json.has("width") && !json.isNull("width")) json.optInt("width") else null
        )

        fun listFromJsonArray(array: JSONArray?): List<SpotifyImage> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}

data class ExternalUrls(
    val spotify: String
) {
    companion object {
        fun fromJson(json: JSONObject?): ExternalUrls? {
            if (json == null) return null
            return ExternalUrls(spotify = json.optString("spotify", ""))
        }
    }
}

data class ExternalIds(
    val isrc: String?,
    val ean: String?,
    val upc: String?
) {
    companion object {
        fun fromJson(json: JSONObject?): ExternalIds? {
            if (json == null) return null
            return ExternalIds(
                isrc = json.optString("isrc", ""),
                ean = json.optString("ean", ""),
                upc = json.optString("upc", "")
            )
        }
    }
}

// =============================================================================
// AUTH
// =============================================================================

data class LoginResult(
    val success: Boolean,
    val user: SpotifyUser?,
    val error: String?,
    val accessToken: String?,
    val expiration: Long?
) {
    companion object {
        fun fromJson(json: JSONObject): LoginResult = LoginResult(
            success = json.optBoolean("success", false),
            user = if (json.has("user") && !json.isNull("user")) SpotifyUser.fromJson(json.getJSONObject("user")) else null,
            error = json.optString("error", ""),
            accessToken = if (json.has("accessToken") && !json.isNull("accessToken")) json.optString("accessToken") else null,
            expiration = if (json.has("accessTokenExpirationTimestampMs") && !json.isNull("accessTokenExpirationTimestampMs")) json.optLong("accessTokenExpirationTimestampMs") else null
        )
    }
}

data class OperationResult(
    val success: Boolean,
    val error: String?
) {
    companion object {
        fun fromJson(json: JSONObject): OperationResult = OperationResult(
            success = json.optBoolean("success", false),
            error = if (json.has("error") && !json.isNull("error")) json.optString("error") else null
        )
    }
}

// =============================================================================
// ARTISTS
// =============================================================================

data class SimpleArtist(
    val id: String,
    val name: String,
    val externalUri: String,
    val images: List<SpotifyImage>?
) {
    companion object {
        fun fromJson(json: JSONObject): SimpleArtist = SimpleArtist(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            externalUri = json.optString("externalUri", ""),
            images = if (json.has("images") && !json.isNull("images")) SpotifyImage.listFromJsonArray(json.optJSONArray("images")) else null
        )

        fun listFromJsonArray(array: JSONArray?): List<SimpleArtist> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}

data class FullArtist(
    val id: String,
    val name: String,
    val externalUri: String,
    val images: List<SpotifyImage>,
    val genres: List<String>,
    val followersTotal: Int?
) {
    companion object {
        fun fromJson(json: JSONObject): FullArtist = FullArtist(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            externalUri = json.optString("externalUri", ""),
            images = SpotifyImage.listFromJsonArray(json.optJSONArray("images")),
            genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            followersTotal = if (json.has("followers") && !json.isNull("followers")) json.optInt("followers") else null
        )

        fun listFromJsonArray(array: JSONArray?): List<FullArtist> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}

// =============================================================================
// ALBUMS
// =============================================================================

data class SimpleAlbum(
    val id: String,
    val name: String,
    val externalUri: String,
    val releaseDate: String?,
    val releaseDatePrecision: String?,
    val images: List<SpotifyImage>,
    val artists: List<SimpleArtist>,
    val albumType: String?
) {
    companion object {
        fun fromJson(json: JSONObject): SimpleAlbum = SimpleAlbum(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            externalUri = json.optString("externalUri", ""),
            releaseDate = json.optString("releaseDate", ""),
            releaseDatePrecision = json.optString("releaseDatePrecision", ""),
            images = SpotifyImage.listFromJsonArray(json.optJSONArray("images")),
            artists = SimpleArtist.listFromJsonArray(json.optJSONArray("artists")),
            albumType = json.optString("albumType", "")
        )

        fun listFromJsonArray(array: JSONArray?): List<SimpleAlbum> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}

data class FullAlbum(
    val id: String,
    val name: String,
    val externalUri: String,
    val releaseDate: String?,
    val releaseDatePrecision: String?,
    val images: List<SpotifyImage>,
    val artists: List<SimpleArtist>,
    val albumType: String?,
    val totalTracks: Int?,
    val recordLabel: String?,
    val genres: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject): FullAlbum = FullAlbum(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            externalUri = json.optString("externalUri", ""),
            releaseDate = json.optString("releaseDate", ""),
            releaseDatePrecision = json.optString("releaseDatePrecision", ""),
            images = SpotifyImage.listFromJsonArray(json.optJSONArray("images")),
            artists = SimpleArtist.listFromJsonArray(json.optJSONArray("artists")),
            albumType = json.optString("albumType", ""),
            totalTracks = if (json.has("totalTracks") && !json.isNull("totalTracks")) json.optInt("totalTracks") else null,
            recordLabel = json.optString("recordLabel", ""),
            genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        )
    }
}

// =============================================================================
// TRACKS
// =============================================================================

data class FullTrack(
    val id: String?,
    val name: String,
    val externalUri: String,
    val explicit: Boolean,
    val durationMs: Int,
    val isrc: String,
    val artists: List<SimpleArtist>,
    val album: SimpleAlbum?
) {
    companion object {
        fun fromJson(json: JSONObject): FullTrack = FullTrack(
            id = if (json.has("id") && !json.isNull("id")) json.optString("id") else null,
            name = json.optString("name", ""),
            externalUri = json.optString("externalUri", ""),
            explicit = json.optBoolean("explicit", false),
            durationMs = json.optInt("durationMs", 0),
            isrc = json.optString("isrc", ""),
            artists = SimpleArtist.listFromJsonArray(json.optJSONArray("artists")),
            album = if (json.has("album") && !json.isNull("album")) SimpleAlbum.fromJson(json.getJSONObject("album")) else null
        )

        fun listFromJsonArray(array: JSONArray?): List<FullTrack> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                fromJson(obj)
            }
        }
    }
}

data class SavedTrackItem(
    val addedAt: String,
    val track: FullTrack
) {
    companion object {
        fun fromJson(json: JSONObject): SavedTrackItem = SavedTrackItem(
            addedAt = json.optString("added_at", ""),
            track = FullTrack.fromJson(json.getJSONObject("track"))
        )

        fun listFromJsonArray(array: JSONArray?): List<SavedTrackItem> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}

data class SavedAlbumItem(
    val addedAt: String,
    val album: FullAlbum
) {
    companion object {
        fun fromJson(json: JSONObject): SavedAlbumItem = SavedAlbumItem(
            addedAt = json.optString("added_at", ""),
            album = FullAlbum.fromJson(json.getJSONObject("album"))
        )

        fun listFromJsonArray(array: JSONArray?): List<SavedAlbumItem> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}

// =============================================================================
// PLAYLISTS
// =============================================================================

typealias PlaylistOwner = SpotifyUser

data class SimplePlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val images: List<SpotifyImage>,
    val externalUri: String,
    val owner: PlaylistOwner?,
    val totalTracks: Int?
) {
    companion object {
        fun fromJson(json: JSONObject): SimplePlaylist = SimplePlaylist(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            description = json.optString("description", ""),
            images = SpotifyImage.listFromJsonArray(json.optJSONArray("images")),
            externalUri = json.optString("externalUri", ""),
            owner = if (json.has("owner") && !json.isNull("owner")) PlaylistOwner.fromJson(json.getJSONObject("owner")) else null,
            totalTracks = json.optJSONObject("tracks")?.optInt("total")
        )

        fun listFromJsonArray(array: JSONArray?): List<SimplePlaylist> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}

data class FullPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val images: List<SpotifyImage>,
    val externalUri: String,
    val owner: PlaylistOwner?,
    val totalTracks: Int?,
    val collaborative: Boolean,
    val public: Boolean?
) {
    companion object {
        fun fromJson(json: JSONObject): FullPlaylist = FullPlaylist(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            description = json.optString("description", ""),
            images = SpotifyImage.listFromJsonArray(json.optJSONArray("images")),
            externalUri = json.optString("externalUri", ""),
            owner = if (json.has("owner") && !json.isNull("owner")) PlaylistOwner.fromJson(json.getJSONObject("owner")) else null,
            totalTracks = json.optJSONObject("tracks")?.optInt("total"),
            collaborative = json.optBoolean("collaborative", false),
            public = if (json.has("public") && !json.isNull("public")) json.optBoolean("public") else null
        )
    }
}

// =============================================================================
// USER
// =============================================================================

data class SpotifyUser(
    val id: String,
    val name: String?,
    val externalUri: String,
    val images: List<SpotifyImage>,
    val followersTotal: Int?,
    val country: String?,
    val product: String?
) {
    companion object {
        fun fromJson(json: JSONObject): SpotifyUser = SpotifyUser(
            id = json.optString("id", ""),
            name = if (json.has("name") && !json.isNull("name")) json.optString("name") else null,
            externalUri = json.optString("externalUri", ""),
            images = SpotifyImage.listFromJsonArray(json.optJSONArray("images")),
            followersTotal = if (json.has("followers") && !json.isNull("followers")) json.optInt("followers") else null,
            country = json.optString("country", ""),
            product = json.optString("product", "")
        )
    }
}

// =============================================================================
// PAGINATION
// =============================================================================

data class PaginatedResponse<T>(
    val items: List<T>,
    val total: Int,
    val limit: Int,
    val nextOffset: Int?,
    val hasMore: Boolean
) {
    companion object {
        fun <T> fromJson(
            json: JSONObject,
            itemParser: (JSONObject) -> T
        ): PaginatedResponse<T> {
            val itemsArray = json.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArray.length()).mapNotNull { i ->
                val obj = itemsArray.optJSONObject(i) ?: return@mapNotNull null
                try { itemParser(obj) } catch (_: Exception) { null }
            }
            return PaginatedResponse(
                items = items,
                total = json.optInt("total", 0),
                limit = json.optInt("limit", 20),
                nextOffset = if (json.has("nextOffset") && !json.isNull("nextOffset")) json.optInt("nextOffset") else null,
                hasMore = json.optBoolean("hasMore", false)
            )
        }
    }
}

// =============================================================================
// SEARCH
// =============================================================================

data class NormalizedSearchResults(
    val tracks: List<FullTrack>,
    val albums: List<SimpleAlbum>,
    val artists: List<FullArtist>,
    val playlists: List<SimplePlaylist>
) {
    companion object {
        fun fromJson(json: JSONObject): NormalizedSearchResults = NormalizedSearchResults(
            tracks = FullTrack.listFromJsonArray(json.optJSONArray("tracks")),
            albums = SimpleAlbum.listFromJsonArray(json.optJSONArray("albums")),
            artists = FullArtist.listFromJsonArray(json.optJSONArray("artists")),
            playlists = SimplePlaylist.listFromJsonArray(json.optJSONArray("playlists"))
        )
    }
}

// =============================================================================
// BROWSE
// =============================================================================

data class BrowseSection(
    val id: String,
    val title: String,
    val items: List<BrowseSectionItem>
) {
    companion object {
        fun fromJson(json: JSONObject): BrowseSection {
            val itemsArray = json.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArray.length()).mapNotNull { i ->
                val obj = itemsArray.optJSONObject(i) ?: return@mapNotNull null
                BrowseSectionItem.fromJson(obj)
            }
            return BrowseSection(
                id = json.optString("id", ""),
                title = json.optString("title", ""),
                items = items
            )
        }

        fun listFromJsonArray(array: JSONArray?): List<BrowseSection> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}

sealed class BrowseSectionItem {
    data class PlaylistItem(val playlist: SimplePlaylist) : BrowseSectionItem()
    data class AlbumItem(val album: SimpleAlbum) : BrowseSectionItem()
    data class ArtistItem(val artist: SimpleArtist) : BrowseSectionItem()

    companion object {
        fun fromJson(json: JSONObject): BrowseSectionItem? {
            val type = json.optString("type", json.optString("objectType", "")).lowercase()
            return when (type) {
                "playlist" -> PlaylistItem(SimplePlaylist.fromJson(json))
                "album" -> AlbumItem(SimpleAlbum.fromJson(json))
                "artist" -> ArtistItem(SimpleArtist.fromJson(json))
                else -> null
            }
        }
    }
}
