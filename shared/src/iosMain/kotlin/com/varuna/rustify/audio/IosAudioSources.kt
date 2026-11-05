// shared/src/iosMain/kotlin/com/varuna/rustify/audio/IosAudioSources.kt
package com.varuna.rustify.audio

import com.varuna.rustify.engine.StreamResolver
import com.varuna.rustify.platform.KeyValueStore

/**
 * The phone's audio sources on iOS: the same [AudioSourceRegistry] and chain, over the app's settings.
 * What differs is the environment: no stream cache, an engine that cannot decode Opus, so the YouTube
 * sources ask for AAC, and no yt-dlp, which is a program iOS cannot run.
 */
object IosAudioSources {
    private var installed: StreamResolver.Sources? = null

    /** Main queue only. The first call initializes the registry; later ones answer the same chain. */
    fun install(settings: KeyValueStore): StreamResolver.Sources = installed ?: run {
        AudioSourceRegistry.initialize(
            AudioSourceEnvironment(settings = settings, streamCache = StreamCacheAccess.None, decodesOpus = false),
            ytDlp = null,
        )
        AudioSourceRegistry.asStreamSources().also { installed = it }
    }
}
