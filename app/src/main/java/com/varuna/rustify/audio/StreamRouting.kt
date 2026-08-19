package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import com.varuna.rustify.bridge.LocalStreamServer
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.TrackRef
import java.io.File

/**
 * The stream cache: what decides whether a track plays from disk through the local server or the
 * way it always has.
 *
 * Two calls, in this order, and the order is the whole design:
 *
 *  1. [cachedUrlFor] — asked **before** anything is resolved. A track played before is already on
 *     disk, so there is no backend to pick, no yt-dlp to run and no network to wait for. This is
 *     where the second play of a song stops costing anything.
 *  2. [rememberAfterResolving] — called **after** a backend produced a URL. It starts a background
 *     download so that the *next* play takes path 1. It never delays this one.
 *
 * Nothing here ever makes the caller wait for bytes. If the cache is cold the answer is "play what
 * you already had", which is what the app did before any of this existed.
 *
 * ## What is not routed
 *
 * **Local music.** A `local:` id already plays through Media3 from `content://`; putting it behind
 * HTTP would add a hop, a port and a token to something that has no problem. The Rust side refuses
 * a non-remote upstream as well, so this is a decision in two places rather than a habit in one.
 *
 * ## The key
 *
 * The track id, and only the track id. Not the backend and not the format, because [cachedUrlFor]
 * runs before either of those is known — and knowing them is exactly the work being avoided. The
 * consequence, stated rather than hidden: after switching backend or quality, a track already on
 * disk keeps playing from disk until the entry is swept out or the cache is cleared. [forget]
 * covers the one case where that would be wrong on the spot — the user picking a different YouTube
 * match, which is a deliberate "no, not that audio".
 */
object StreamRouting {

    private const val TAG = "StreamRouting"
    private const val PREFS = "rustify_settings"

    /**
     * The user's switch. On by default: the benefit is instant repeat plays and less data used.
     *
     * It is also the **master switch for everything 3.1 added**. With it off, nothing is routed,
     * nothing is downloaded, and the loopback server is never started at all — the app behaves
     * exactly as 2.12.14 did. That is deliberate: it is the one lever that answers "is the new
     * machinery what broke this?" without reinstalling an older build.
     */
    const val ENABLED_KEY = "settings_stream_cache_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    /**
     * Where cached tracks live.
     *
     * `cacheDir` and not `filesDir`: this is regenerable data, and telling Android so is what lets
     * it reclaim the space instead of the user reinstalling the app to get it back.
     */
    fun cacheRoot(context: Context): String =
        File(context.cacheDir, "stream-cache").absolutePath

    /** Bytes currently held. Crosses JNI and walks a directory, hence `suspend`. */
    suspend fun size(context: Context): Long =
        runCatching { NativeEngine.streamCacheSize(cacheRoot(context)) }.getOrDefault(0L)

    /** Empties the cache and returns the bytes freed. */
    suspend fun clear(context: Context): Long =
        runCatching { NativeEngine.clearStreamCache(cacheRoot(context)) }.getOrDefault(0L)

    /**
     * The loopback URL for [trackId] if its audio is already on disk, or null.
     *
     * Starts nothing and fetches nothing: null means "carry on as normal".
     */
    suspend fun cachedUrlFor(context: Context, trackId: String): String? {
        if (!routable(context, trackId)) return null
        if (!LocalStreamServer.ensureStarted()) return null
        return LocalStreamServer.registerReady(
            cacheKey = trackId,
            cacheRoot = cacheRoot(context)
        )
    }

    /**
     * Remembers the audio a backend just resolved, so the next play comes off disk.
     *
     * Returns a loopback URL on the rare occasion the bytes were already there — a race with a
     * fill started moments earlier — and null otherwise, which is the normal case and means "play
     * the URL you resolved".
     */
    suspend fun rememberAfterResolving(
        context: Context,
        trackId: String,
        info: StreamInfo
    ): String? {
        if (!routable(context, trackId)) return null

        val hint = info.cache
        // A provider that says nothing about caching is not cached. Deezer's `uri` is a
        // `deezer://` URI and yt-dlp's expires in six hours: what to *store* is not something to
        // guess from a playback URL, so a provider has to say it on purpose.
        if (hint == null || hint.upstreamUrl.isBlank()) return null
        if (!LocalStreamServer.ensureStarted()) return null

        return runCatching {
            LocalStreamServer.registerReady(
                cacheKey = trackId,
                cacheRoot = cacheRoot(context),
                upstreamUrl = hint.upstreamUrl,
                mime = info.mimeType,
                deezerSngId = hint.deezerSngId
            )
        }.onFailure { Log.w(TAG, "could not register $trackId for caching", it) }.getOrNull()
    }

    /**
     * Drops whatever is cached for [trackId].
     *
     * Called when the user picks a different YouTube match: they have said the audio is wrong, and
     * a cache that keeps serving it would make the choice look like it did nothing.
     */
    fun forget(context: Context, trackId: String) {
        if (trackId.isBlank()) return
        runCatching {
            val file = File(cacheRoot(context), sanitise(trackId))
            if (file.isFile) file.delete()
        }.onFailure { Log.w(TAG, "could not drop the cached copy of $trackId", it) }
    }

    private fun routable(context: Context, trackId: String): Boolean =
        trackId.isNotBlank() && !TrackRef.isLocal(trackId) && isEnabled(context)

    /**
     * The same mapping `env::storage::sanitise_key` applies on the Rust side.
     *
     * Duplicated on purpose and duplicated *exactly*: [forget] deletes a file by name without
     * crossing JNI, and a name that disagreed with the one Rust wrote would delete nothing while
     * looking like it worked. `StreamRoutingTest` pins the two together.
     */
    fun sanitise(key: String): String {
        val out = StringBuilder(key.length.coerceAtLeast(1))
        for (c in key) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') {
                out.append(c)
            } else {
                out.append('_')
            }
        }
        // A leading dot would make a hidden file, and "." or ".." would name a directory.
        if (out.isEmpty() || out[0] == '.') out.insert(0, 'k')
        return out.toString()
    }
}
