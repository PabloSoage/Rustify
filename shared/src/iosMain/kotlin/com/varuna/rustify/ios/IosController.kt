// shared/src/iosMain/kotlin/com/varuna/rustify/ios/IosController.kt
package com.varuna.rustify.ios

import com.varuna.rustify.bridge.CoreCall
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyUser
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.decodeWire
import com.varuna.rustify.engine.IosPlayerBackend
import com.varuna.rustify.engine.PlayerUi
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.platform.UserDefaultsStore
import com.varuna.rustify.platform.defaultLanguage
import com.varuna.rustify.spotify.IosSpotifyCore
import com.varuna.rustify.spotify.SpotifyLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * What the Swift app talks to: one object, callbacks instead of coroutines, plain types.
 *
 * Swift cannot collect a Flow or call a suspend function without a wrapper, and the app does not need
 * either — it needs "search this, tell me", "play these", and "tell me when the player changes". All
 * of it runs on the main queue ([MainScope]); the core calls themselves hop to an IO thread inside
 * [CoreCall].
 */
class IosController {
    private val scope = MainScope()
    private val player = QueuePlayer(scope, IosPlayerBackend, UserDefaultsStore()).also { it.restore() }
    private val spotify = SpotifyLibrary(IosSpotifyCore, UserDefaultsStore())

    /** Call once at launch with the app's Application Support and Caches folders. */
    fun start(dataDir: String, cacheDir: String) {
        scope.launch {
            CoreCall.call("initCacheDir") { put("dir", dataDir) }
            CoreCall.call("initSpotifyCacheDir") { put("dir", cacheDir) }
            CoreCall.call("setLanguage") { put("lang", defaultLanguage()) }
            player.start()
        }
    }

    /** YouTube Music, no account needed — as on the phone. */
    fun search(query: String, onResult: (List<FullTrack>) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            val answer = CoreCall.call("searchYtMusic") { put("query", query) }
            runCatching { decodeWire<YtmSearchResults>(answer).tracks.map { it.toFullTrack() } }
                .onSuccess(onResult)
                .onFailure { onError(it.message ?: "Search failed") }
        }
    }

    /** The saved Spotify session, if any; [onUser] gets null when there is none. */
    fun restoreSpotify(onUser: (SpotifyUser?) -> Unit) {
        scope.launch { onUser(runCatching { spotify.restore() }.getOrNull()) }
    }

    /** Signs in with the `sp_dc` cookie, as on the phone. */
    fun signInSpotify(spDc: String, onUser: (SpotifyUser) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            runCatching { spotify.login(spDc) }
                .onSuccess(onUser)
                .onFailure { onError(it.message ?: "Sign-in failed") }
        }
    }

    fun signOutSpotify() = spotify.logout()

    /** Every liked song, all pages. */
    fun likedSongs(onResult: (List<FullTrack>) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            runCatching { spotify.likedSongs() }
                .onSuccess(onResult)
                .onFailure { onError(it.message ?: "Could not load liked songs") }
        }
    }

    fun playAll(tracks: List<FullTrack>, start: Int) = player.playAll(tracks, start)

    fun togglePause() = player.togglePause()

    fun next() = player.next()

    fun previous() = player.previous()

    fun seek(positionMs: Long) = player.seek(positionMs)

    fun setVolume(volume: Float) = player.setVolume(volume)

    /** [onChange] with the player's state now and on every change. Cancel the job to stop. */
    fun observe(onChange: (PlayerUi) -> Unit): Job = player.observe(onChange)

    /** The core's version, for an about screen and for spotting a mismatched framework. */
    fun coreVersion(): String = CoreCall.version()

    fun close() {
        player.shutdown()
        scope.cancel()
    }
}
