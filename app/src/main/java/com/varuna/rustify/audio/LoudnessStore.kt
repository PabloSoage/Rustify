package com.varuna.rustify.audio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * How loud each track's recording is, so consecutive songs play at the same perceived level (4.2).
 *
 * YouTube measures every upload and says by how much it is louder than its reference
 * (`loudnessDb` in the InnerTube answer); YouTube's own player turns it down by exactly that. A song
 * from one channel and the next from another can otherwise differ by several dB.
 *
 * Kept per track id because the second play of a song comes from the stream cache, where nothing
 * says how loud it is. Persisted in `filesDir/loudness.json`; small (a number per track).
 */
object LoudnessStore {
    private const val FILE = "loudness.json"
    const val PREF_ENABLED = "normalize_volume"

    private val values = ConcurrentHashMap<String, Double>()
    @Volatile private var loaded = false

    /** Off the main thread: reads the file the first time. */
    fun get(context: Context, trackId: String): Double? {
        ensureLoaded(context)
        return values[trackId]
    }

    fun put(context: Context, trackId: String, loudnessDb: Double) {
        ensureLoaded(context)
        if (values.put(trackId, loudnessDb) == loudnessDb) return
        // The map answers at once; the file is written behind, off whatever thread called.
        val snapshot = JSONObject(values as Map<*, *>).toString()
        kotlin.concurrent.thread(name = "loudness-store") {
            runCatching {
                val file = File(context.filesDir, FILE)
                val tmp = File(context.filesDir, "$FILE.tmp")
                tmp.writeText(snapshot)
                if (!tmp.renameTo(file)) { file.writeText(snapshot); tmp.delete() }
            }
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

    /**
     * Player volume for a recording [loudnessDb] above the reference: turned down by that much, never
     * up — `ExoPlayer.volume` cannot exceed 1, and boosting a quiet master would clip anyway. Floored
     * so a mis-measured upload cannot become inaudible.
     */
    fun gainFor(loudnessDb: Double?): Float {
        if (loudnessDb == null || loudnessDb <= 0.0) return 1f
        return 10.0.pow(-loudnessDb / 20.0).toFloat().coerceIn(0.25f, 1f)
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            for (key in json.keys()) values[key] = json.getDouble(key)
        }
    }
}
