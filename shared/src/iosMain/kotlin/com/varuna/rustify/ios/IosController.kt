// shared/src/iosMain/kotlin/com/varuna/rustify/ios/IosController.kt
package com.varuna.rustify.ios

import com.varuna.rustify.bridge.CoreCall
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SpotifyUser
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.decodeWire
import com.varuna.rustify.engine.IosPlayerBackend
import com.varuna.rustify.engine.PlayerUi
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.platform.KeychainKeyValueStore
import com.varuna.rustify.platform.SecretStorage
import com.varuna.rustify.platform.UserDefaultsStore
import com.varuna.rustify.platform.defaultLanguage
import com.varuna.rustify.spotify.IosSpotifyCore
import com.varuna.rustify.spotify.SpotifyLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Swift app talks to: one object, callbacks instead of coroutines, plain types.
 *
 * Swift cannot collect a Flow or call a suspend function without a wrapper, and the app does not need
 * either — it needs "search this, tell me", "play these", and "tell me when the player changes". All
 * of it runs on the main queue ([MainScope]); the core calls themselves hop to an IO thread inside
 * [NativeEngine] and [CoreCall].
 *
 * [secrets] is the Keychain, which the app implements in Swift: the Spotify cookie and token go
 * there, the rest of the session stays in `NSUserDefaults` (see [KeychainKeyValueStore]).
 */
class IosController(val secrets: SecretStorage) {
    /** What everything on the main queue runs in; the interface's player and Jam run in it too. */
    val scope = MainScope()

    /** The player, which the shared interface drives and Swift's lock screen and session watch. */
    val player = QueuePlayer(scope, IosPlayerBackend, UserDefaultsStore()).also { it.restore() }
    private val spotify = SpotifyLibrary(
        IosSpotifyCore,
        KeychainKeyValueStore(KeychainKeyValueStore.SPOTIFY_SECRETS, secrets, UserDefaultsStore()),
    )

    private val _ready = MutableStateFlow(false)

    /** True once the core knows its folders: restoring a session before that would forget what it learned. */
    val ready: StateFlow<Boolean> = _ready

    /** Call once at launch with the app's Application Support and Caches folders. */
    fun start(dataDir: String, cacheDir: String) {
        scope.launch {
            NativeEngine.initCacheDir(dataDir)
            NativeEngine.initSpotifyCacheDir(cacheDir)
            // Memory only, but off the main queue as it always was here.
            val language = defaultLanguage()
            withContext(Dispatchers.IO) { NativeEngine.setLanguageNative(language) }
            player.start()
            _ready.value = true
        }
    }

    /** YouTube Music, no account needed — as on the phone. */
    fun search(query: String, onResult: (List<FullTrack>) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            val answer = NativeEngine.searchYtMusic(query)
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

    /** Plays if paused; for the lock screen's play button and the end of an interruption. */
    fun play() = player.play()

    /** Pauses if playing; for the lock screen's pause button, an interruption or unplugged headphones. */
    fun pause() = player.pause()

    /** Whether the player is playing now, for callers that ask rather than [observe]. */
    val isPlaying: Boolean get() = player.ui.value.playing

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
