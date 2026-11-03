// shared/src/commonMain/kotlin/com/varuna/rustify/spotify/SpotifyLibrary.kt
package com.varuna.rustify.spotify

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LoginResult
import com.varuna.rustify.bridge.PaginatedResponse
import com.varuna.rustify.bridge.SimplePlaylist
import com.varuna.rustify.bridge.SpotifyEngineException
import com.varuna.rustify.bridge.SpotifyUser
import com.varuna.rustify.bridge.allPages
import com.varuna.rustify.bridge.decodeCheckedWire
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.util.retrying

/**
 * The Spotify calls the desktop and iOS make, as JSON in and out — one implementation per way of
 * reaching the core: `JvmSpotifyCore` (JNI) and `IosSpotifyCore` (C ABI). Same answers on both.
 */
interface SpotifyCore {
    suspend fun login(spDc: String): String
    suspend fun restore(spDc: String, accessToken: String, expirationMs: Long): String
    fun isAuthenticated(): Boolean
    fun logout()
    suspend fun me(): String
    suspend fun savedTracks(limit: Int, offset: Int): String
    suspend fun savedPlaylists(limit: Int, offset: Int): String
    suspend fun playlistTracks(id: String, limit: Int, offset: Int): String
    suspend fun search(query: String, type: String, limit: Int, offset: Int): String
}

/**
 * The user's Spotify on the platforms without `SpotifyRepository` (which is Android): signing in
 * with the `sp_dc` cookie, as on the phone, keeping the session across restarts, and the library.
 *
 * The session lives in [store] under the same three key names the Android repository uses in its
 * own preferences file, so a future shared repository (docs 25, T1.3) reads what this wrote.
 *
 * An auth failure halfway through a call (the token expired an hour in) restores the session once
 * and tries again, through the same `retrying` the phone uses.
 */
class SpotifyLibrary(private val core: SpotifyCore, private val store: KeyValueStore) {

    val isSignedIn: Boolean get() = core.isAuthenticated()

    /** The last session back, without a prompt; null if there is none or it no longer works. */
    suspend fun restore(): SpotifyUser? {
        val spDc = store.getString(KEY_SP_DC)?.takeIf { it.isNotBlank() } ?: return null
        val result = runCatching {
            decodeCheckedWire<LoginResult>(
                core.restore(spDc, store.getString(KEY_ACCESS_TOKEN).orEmpty(), store.getLong(KEY_EXPIRATION, 0)),
            )
        }.onFailure { Log.w(TAG, "session not restored", it) }.getOrNull() ?: return null
        if (!result.success) return null
        remember(spDc, result)
        return result.user ?: runCatching { me() }.getOrNull()
    }

    /** Signs in with the `sp_dc` cookie from a browser session at open.spotify.com. */
    suspend fun login(spDc: String): SpotifyUser {
        val cookie = spDc.trim()
        val result = decodeCheckedWire<LoginResult>(core.login(cookie))
        if (!result.success) throw SpotifyEngineException(result.error ?: "Sign-in failed", result.kind)
        remember(cookie, result)
        return result.user ?: me()
    }

    fun logout() {
        core.logout()
        store.remove(KEY_SP_DC)
        store.remove(KEY_ACCESS_TOKEN)
        store.remove(KEY_EXPIRATION)
    }

    suspend fun me(): SpotifyUser = call { decodeCheckedWire(core.me()) }

    suspend fun likedSongs(): List<FullTrack> =
        allPages { limit, offset -> call { decodeCheckedWire<PaginatedResponse<FullTrack>>(core.savedTracks(limit, offset)) } }

    suspend fun playlists(): List<SimplePlaylist> =
        allPages { limit, offset ->
            call { decodeCheckedWire<PaginatedResponse<SimplePlaylist>>(core.savedPlaylists(limit, offset)) }
        }

    suspend fun playlistTracks(id: String): List<FullTrack> =
        allPages(pageSize = 100) { limit, offset ->
            call { decodeCheckedWire<PaginatedResponse<FullTrack>>(core.playlistTracks(id, limit, offset)) }
        }

    /** Songs only; the first [limit]. A search is a screen, not a whole. */
    suspend fun searchTracks(query: String, limit: Int = 30): List<FullTrack> =
        call { decodeCheckedWire<PaginatedResponse<FullTrack>>(core.search(query, "tracks", limit, 0)) }.items

    private suspend fun <T> call(block: suspend () -> T): T =
        retrying(onAuthError = { restore() != null }, block = block)

    private fun remember(spDc: String, result: LoginResult) {
        store.putString(KEY_SP_DC, spDc)
        result.accessToken?.let { store.putString(KEY_ACCESS_TOKEN, it) }
        result.expiration?.let { store.putLong(KEY_EXPIRATION, it) }
    }

    private companion object {
        const val TAG = "SpotifyLibrary"
        // The names SpotifyRepository uses (KEY_SP_DC, KEY_ACCESS_TOKEN, KEY_EXPIRATION).
        const val KEY_SP_DC = "sp_dc_cookie"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_EXPIRATION = "expiration_timestamp"
    }
}
