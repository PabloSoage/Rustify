// shared/src/iosMain/kotlin/com/varuna/rustify/spotify/IosSpotifyCore.kt
package com.varuna.rustify.spotify

import com.varuna.rustify.bridge.CoreCall
import kotlinx.serialization.json.put

/** [SpotifyCore] over the C ABI (`src/ffi.rs`), the same answers as the JNI bridges. */
object IosSpotifyCore : SpotifyCore {
    override suspend fun login(spDc: String): String = CoreCall.call("loginSpotify") { put("spDc", spDc) }

    override suspend fun restore(spDc: String, accessToken: String, expirationMs: Long): String =
        CoreCall.call("restoreSpotifySession") {
            put("spDc", spDc)
            put("accessToken", accessToken)
            put("expiration", expirationMs)
        }

    override fun isAuthenticated(): Boolean = CoreCall.callBlocking("isSpotifyAuthenticated") == "true"

    override fun logout() {
        CoreCall.callBlocking("logoutSpotify")
    }

    override suspend fun me(): String = CoreCall.call("getSpotifyMe")

    override suspend fun savedTracks(limit: Int, offset: Int): String = CoreCall.call("getSpotifySavedTracks") {
        put("limit", limit)
        put("offset", offset)
    }

    override suspend fun savedPlaylists(limit: Int, offset: Int): String = CoreCall.call("getSpotifySavedPlaylists") {
        put("limit", limit)
        put("offset", offset)
    }

    override suspend fun playlistTracks(id: String, limit: Int, offset: Int): String =
        CoreCall.call("getSpotifyPlaylistTracks") {
            put("id", id)
            put("limit", limit)
            put("offset", offset)
        }

    override suspend fun search(query: String, type: String, limit: Int, offset: Int): String =
        CoreCall.call("searchSpotify") {
            put("query", query)
            put("type", type)
            put("limit", limit)
            put("offset", offset)
        }
}
