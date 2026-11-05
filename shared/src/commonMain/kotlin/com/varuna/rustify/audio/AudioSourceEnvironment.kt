// shared/src/commonMain/kotlin/com/varuna/rustify/audio/AudioSourceEnvironment.kt
package com.varuna.rustify.audio

import com.varuna.rustify.platform.KeyValueStore

/**
 * What the audio sources need from the platform they run on, given to [AudioSourceRegistry] once.
 *
 * The sources are HTTP, the core and their own settings; the only things that differ between the
 * phone and the desktop are where those settings live, whether there is a stream cache behind the
 * local server, and what the player can decode.
 *
 * @param settings the store the sources keep their settings in. On Android the `rustify_settings`
 *   preferences file, through `SharedPreferencesStore`, so every key and value is where it was.
 * @param cacheStore where a source keeps what it fetched to save asking again (the Invidious
 *   directory). The same store on Android; on the desktop a file store, because the directory
 *   outgrows what `java.util.prefs` holds in one value.
 * @param streamCache the stream cache, where the platform has one (Android's `StreamRouting`).
 * @param decodesOpus false where the player cannot decode Opus (the core's engine, which has no
 *   Opus decoder): the YouTube sources then ask for AAC.
 */
class AudioSourceEnvironment(
    val settings: KeyValueStore,
    val cacheStore: KeyValueStore = settings,
    val streamCache: StreamCacheAccess = StreamCacheAccess.None,
    val decodesOpus: Boolean = true,
)

/**
 * The stream cache as the audio sources see it: whether to keep a copy of what plays, under which
 * key, and the generation that puts every stored copy out of reach when a setting changes what a
 * source would produce. Android's is `StreamRouting`; a platform without one passes [None].
 */
interface StreamCacheAccess {
    fun isEnabled(): Boolean

    /** The cache key for [trackId] under the current generation. */
    fun keyFor(trackId: String): String

    /** Where the cached tracks live. */
    fun cacheRoot(): String

    /**
     * What a cached track contains may no longer be what the user wants (the source order, a source
     * switched on or off, the Deezer quality).
     */
    fun bumpGeneration()

    /** No stream cache: nothing is kept, and there is no generation to move. */
    object None : StreamCacheAccess {
        override fun isEnabled(): Boolean = false
        override fun keyFor(trackId: String): String = ""
        override fun cacheRoot(): String = ""
        override fun bumpGeneration() = Unit
    }
}
