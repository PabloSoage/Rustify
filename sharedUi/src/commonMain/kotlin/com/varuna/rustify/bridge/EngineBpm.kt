// sharedUi/src/commonMain/kotlin/com/varuna/rustify/bridge/EngineBpm.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.fileExists
import com.varuna.rustify.ui.readAllText
import com.varuna.rustify.ui.writeAllText
import com.varuna.rustify.util.LockedMap
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * The tempo of a local file where the engine plays (desktop and iOS), the twin of Android's `BpmAnalyzer`:
 * the core decodes and measures a window of the song (`engine::bpm`), and the answer is kept per track in
 * `filesDir/bpm.json`, so a song is analysed once. The same file and the same rules as the phone's.
 *
 * Only `file:` tracks: a catalogue track is not on disk, and a phone's `content://` means nothing here.
 */
class EngineBpm(
    filesDir: AppFile,
    private val measure: suspend (uri: String) -> String = NativeEngine::bpmAnalyze,
) {
    @Serializable
    private data class Estimate(val bpm: Float, val confidence: Float)

    private val file = filesDir.child(FILE)
    private val store = MapSerializer(String.serializer(), Float.serializer())
    private val values: MutableMap<String, Float> by lazy {
        LockedMap<String, Float>().also { map ->
            runCatching { if (file.fileExists()) map.putAll(RustifyJson.decodeFromString(store, file.readAllText())) }
                .onFailure { Log.w(TAG, "could not read $FILE", it) }
        }
    }

    fun cached(trackId: String): Float? = values[trackId]

    /** Measures [trackId] and remembers it; null when it has no clear beat, or is not a file here. */
    suspend fun analyze(trackId: String): Float? {
        cached(trackId)?.let { return it }
        val local = TrackRef.parse(trackId) as? TrackRef.Local ?: return null
        if (!local.uri.startsWith("file:")) return null
        val answer = measure(local.uri)
        val estimate = runCatching { RustifyJson.decodeFromString(Estimate.serializer().nullable, answer) }.getOrNull()
        if (estimate == null) {
            Log.i(TAG, "$trackId: no tempo: ${answer.take(200)}")
            return null
        }
        Log.i(TAG, "$trackId: ${estimate.bpm} BPM (confidence ${estimate.confidence})")
        // Below this there is no beat worth naming (noise scored ~1.1 in the core's tests).
        if (estimate.confidence < MIN_CONFIDENCE) return null
        put(trackId, estimate.bpm)
        return estimate.bpm
    }

    /** The user's correction of an octave error: doubles or halves the stored tempo. */
    fun scale(trackId: String, factor: Float): Float? {
        val bpm = cached(trackId) ?: return null
        val scaled = ((bpm * factor) * 10f).roundToInt() / 10f
        put(trackId, scaled)
        return scaled
    }

    private fun put(trackId: String, bpm: Float) {
        values[trackId] = bpm
        runCatching { file.writeAllText(RustifyJson.encodeToString(store, values.toMap())) }
            .onFailure { Log.w(TAG, "could not write $FILE", it) }
    }

    private companion object {
        const val TAG = "EngineBpm"
        const val FILE = "bpm.json"
        const val MIN_CONFIDENCE = 2f
    }
}
