// shared/src/iosMain/kotlin/com/varuna/rustify/ios/IosController.kt
package com.varuna.rustify.ios

import com.varuna.rustify.bridge.CoreCall
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.YtmTrack
import com.varuna.rustify.bridge.decodeWire
import com.varuna.rustify.engine.IosPlayerBackend
import com.varuna.rustify.engine.PlayerUi
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.platform.defaultLanguage
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
    private val player = QueuePlayer(scope, IosPlayerBackend)

    /** Call once at launch with the app's Application Support and Caches folders. */
    fun start(dataDir: String, cacheDir: String) {
        scope.launch {
            CoreCall.call("initCacheDir") { put("dir", dataDir) }
            CoreCall.call("initSpotifyCacheDir") { put("dir", cacheDir) }
            CoreCall.call("setLanguage") { put("lang", defaultLanguage()) }
            player.start()
        }
    }

    fun search(query: String, onResult: (List<YtmTrack>) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            val answer = CoreCall.call("searchYtMusic") { put("query", query) }
            runCatching { decodeWire<YtmSearchResults>(answer).tracks }
                .onSuccess(onResult)
                .onFailure { onError(it.message ?: "Search failed") }
        }
    }

    fun playAll(tracks: List<YtmTrack>, start: Int) = player.playAll(tracks, start)

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
