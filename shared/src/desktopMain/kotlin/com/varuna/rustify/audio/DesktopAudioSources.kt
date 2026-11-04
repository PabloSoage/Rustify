// shared/src/desktopMain/kotlin/com/varuna/rustify/audio/DesktopAudioSources.kt
package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.engine.StreamResolver
import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.FileStore
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The phone's audio sources on the desktop (docs serie-4.x/27 §2.5): the same [AudioSourceRegistry]
 * and chain, in the order and with the switches saved in Settings, handed to [StreamResolver] so the
 * player and the downloads resolve through it.
 *
 * What differs from the phone is only the environment: the settings are the desktop's
 * `rustify_settings` store; the Invidious directory is cached in a file (it outgrows a preference);
 * there is no stream cache, so nothing is kept; the engine cannot decode Opus, so the YouTube sources
 * ask for AAC; and yt-dlp is the `yt-dlp` program, when there is one. Deezer plays through the core's
 * local server, which [com.varuna.rustify.bridge.LocalStreamServer] starts the first time a Deezer
 * track is registered, and add-ons are asked through the core, which judges and pins their hosts.
 */
object DesktopAudioSources {

    private const val TAG = "DesktopAudioSources"

    @Volatile private var installed: StreamResolver.Sources? = null

    /**
     * Initializes the registry over [settings] (the first call wins; later ones answer the same
     * chain) and answers it as a [StreamResolver.Sources].
     */
    fun install(settings: KeyValueStore): StreamResolver.Sources = synchronized(this) {
        installed ?: run {
            AudioSourceRegistry.initialize(
                AudioSourceEnvironment(
                    settings = settings,
                    cacheStore = FileStore(File(AppDirs.cache, "audio-sources")),
                    streamCache = StreamCacheAccess.None,
                    decodesOpus = false,
                ),
                DesktopYtDlpAudioSource(),
            )
            Chain().also { installed = it }
        }
    }

    private class Chain : StreamResolver.Sources {
        private val addons = Mutex()
        private var addonsLoaded = false

        override suspend fun resolve(track: FullTrack, hint: String?): StreamResolver.Stream? {
            // The installed add-ons join the chain before the first track, as the phone loads them at
            // start-up (MainActivity). Not at start-up here: the core's storage, where they are kept,
            // is opened by the window's first effect, and the first track comes after it.
            addons.withLock {
                if (!addonsLoaded) {
                    addonsLoaded = true
                    runCatching { AudioSourceRegistry.refreshAddons() }
                        .onFailure { Log.w(TAG, "could not load the add-ons", it) }
                }
            }
            return AudioSourceRegistry.streamChain().resolveStreamUrl(track, hint).fold(
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
}
