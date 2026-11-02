package com.varuna.rustify.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * The tempo of a local file (`19`, 5.x §3): decoded here with MediaCodec, measured by the core
 * (`tempo.rs`), kept per track in `filesDir/bpm.json` so a song is analysed once.
 *
 * Only a window of the song is decoded: [WINDOW_S] seconds from a third of the way in, past the
 * intro, where the beat has settled. Decoding is what costs; the measurement itself is about 150 ms
 * for 30 s of audio.
 */
object BpmAnalyzer {
    private const val FILE = "bpm.json"
    const val WINDOW_S = 60

    @Serializable
    data class Estimate(val bpm: Float, val confidence: Float)

    private val values = ConcurrentHashMap<String, Float>()
    @Volatile private var loaded = false

    fun cached(context: Context, trackId: String): Float? {
        ensureLoaded(context)
        return values[trackId]
    }

    /** Measures [trackId] (a `local:` track) and remembers it; null when it has no clear beat. */
    suspend fun analyze(context: Context, trackId: String, durationMs: Long): Float? {
        cached(context, trackId)?.let { return it }
        val uri = Uri.parse(trackId.removePrefix("local:"))
        val startUs = if (durationMs > WINDOW_S * 1000L * 2) durationMs * 1000 / 3 else 0L
        val (pcm, rate) = withContext(Dispatchers.IO) { decodeMono(context, uri, startUs, WINDOW_S) } ?: return null
        val json = NativeEngine.tempoOf(pcm, rate)
        val estimate = runCatching { RustifyJson.decodeFromString(Estimate.serializer().nullable, json) }.getOrNull()
            ?: return null
        android.util.Log.i("BpmAnalyzer", "$trackId: ${estimate.bpm} BPM (confidence ${estimate.confidence})")
        // Below this there is no beat worth naming (noise scored ~1.1 in the core's tests).
        if (estimate.confidence < 2f) return null
        put(context, trackId, estimate.bpm)
        return estimate.bpm
    }

    /** Up to [seconds] of mono float PCM from [startUs], and its sample rate. */
    private fun decodeMono(context: Context, uri: Uri, startUs: Long, seconds: Int): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val out = FloatArray(rate * seconds)
            var written = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (written < out.size) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            while (fb.remaining() >= channels && written < out.size) {
                                var sum = 0f
                                repeat(channels) { sum += fb.get() }
                                out[written++] = sum / channels
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.remaining() >= channels && written < out.size) {
                                var sum = 0f
                                repeat(channels) { sum += sb.get() / 32_768f }
                                out[written++] = sum / channels
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return out.copyOf(written) to rate
        } catch (e: Exception) {
            android.util.Log.w("BpmAnalyzer", "could not decode $uri: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun put(context: Context, trackId: String, bpm: Float) {
        values[trackId] = bpm
        val snapshot = JSONObject(values as Map<*, *>).toString()
        runCatching {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(snapshot)
            if (!tmp.renameTo(File(context.filesDir, FILE))) { File(context.filesDir, FILE).writeText(snapshot); tmp.delete() }
        }
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            for (key in json.keys()) values[key] = json.getDouble(key).toFloat()
        }
    }
}
