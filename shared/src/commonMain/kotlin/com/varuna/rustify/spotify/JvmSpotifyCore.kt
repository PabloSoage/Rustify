// shared/src/commonMain/kotlin/com/varuna/rustify/spotify/JvmSpotifyCore.kt
package com.varuna.rustify.spotify

import com.varuna.rustify.bridge.NativeEngine

/**
 * [SpotifyCore] over [NativeEngine], for the desktop and iOS (as `IosSpotifyCore`), and Android once
 * its repository moves here (T1.3). JNI on the JVMs, the C ABI on iOS; the name is from when it was
 * the JNI one.
 */
object JvmSpotifyCore : SpotifyCore {
    override suspend fun login(spDc: String): String = NativeEngine.loginSpotify(spDc)

    override suspend fun restore(spDc: String, accessToken: String, expirationMs: Long): String =
        NativeEngine.restoreSpotifySession(spDc, accessToken, expirationMs)

    override fun isAuthenticated(): Boolean = NativeEngine.isSpotifyAuthenticatedNative()

    override fun logout() = NativeEngine.logoutSpotifyNative()

    override suspend fun me(): String = NativeEngine.getSpotifyMe()

    override suspend fun savedTracks(limit: Int, offset: Int): String = NativeEngine.getSpotifySavedTracks(limit, offset)

    override suspend fun savedPlaylists(limit: Int, offset: Int): String =
        NativeEngine.getSpotifySavedPlaylists(limit, offset)

    override suspend fun playlistTracks(id: String, limit: Int, offset: Int): String =
        NativeEngine.getSpotifyPlaylistTracks(id, limit, offset)

    override suspend fun search(query: String, type: String, limit: Int, offset: Int): String =
        NativeEngine.searchSpotify(query, type, limit, offset)

    override suspend fun browse(limit: Int): String = NativeEngine.getSpotifyBrowse(limit)

    override suspend fun albumTracks(id: String, limit: Int, offset: Int): String =
        NativeEngine.getSpotifyAlbumTracks(id, limit, offset)
}
