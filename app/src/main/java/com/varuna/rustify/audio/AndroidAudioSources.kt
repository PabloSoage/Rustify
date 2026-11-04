package com.varuna.rustify.audio

import android.content.Context
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.SharedPreferencesStore

/**
 * The audio sources (in :shared since docs serie-4.x/27 §2.5) over what the phone has: the
 * `rustify_settings` preferences file, unchanged, so every key and value stays where it was; the
 * stream cache ([StreamRouting]); and youtubedl-android's yt-dlp.
 */
object AndroidAudioSources {

    /** The preferences file the sources always kept their settings in. */
    fun settings(context: Context): KeyValueStore =
        SharedPreferencesStore(context.applicationContext.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE))

    fun environment(context: Context): AudioSourceEnvironment {
        val app = context.applicationContext
        return AudioSourceEnvironment(settings = settings(app), streamCache = AndroidStreamCache(app))
    }

    /** [AudioSourceRegistry.initialize] with the phone's providers. Idempotent, as that is. */
    fun initialize(context: Context) {
        val app = context.applicationContext
        AudioSourceRegistry.initialize(environment(app), YtDlpAudioSource(app))
    }

    /** [StreamRouting] as the sources see it. */
    private class AndroidStreamCache(private val context: Context) : StreamCacheAccess {
        override fun isEnabled(): Boolean = StreamRouting.isEnabled(context)
        override fun keyFor(trackId: String): String = StreamRouting.keyFor(context, trackId)
        override fun cacheRoot(): String = StreamRouting.cacheRoot(context)
        override fun bumpGeneration() = StreamRouting.bumpGeneration(context)
    }
}
