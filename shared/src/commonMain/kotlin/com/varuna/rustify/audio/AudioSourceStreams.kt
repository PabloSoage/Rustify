// shared/src/commonMain/kotlin/com/varuna/rustify/audio/AudioSourceStreams.kt
package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.engine.StreamResolver
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The registry's stream chain as what the engine's player asks, where it plays (the desktop and iOS). */
fun AudioSourceRegistry.asStreamSources(): StreamResolver.Sources = object : StreamResolver.Sources {
    private val addons = Mutex()
    private var addonsLoaded = false

    override suspend fun resolve(track: FullTrack, hint: String?): StreamResolver.Stream? {
        // The installed add-ons join the chain before the first track, as the phone loads them at
        // start-up (MainActivity). Not at start-up here: the core's storage, where they are kept,
        // is opened by the window's first effect, and the first track comes after it.
        addons.withLock {
            if (!addonsLoaded) {
                addonsLoaded = true
                runCatching { refreshAddons() }
                    .onFailure { Log.w(TAG, "could not load the add-ons", it) }
            }
        }
        return streamChain().resolveStreamUrl(track, hint).fold(
            onSuccess = { (provider, info) ->
                Log.d(TAG, "${track.id} via $provider${if (hint.isNullOrBlank()) "" else " (video $hint)"}")
                StreamResolver.Stream(url = info.uri, mimeType = info.mimeType.orEmpty(), loudnessDb = info.loudnessDb)
            },
            onFailure = { e ->
                Log.w(TAG, "no source for ${track.id}: ${e.message}")
                null
            },
        )
    }
}

private const val TAG = "AudioSources"
