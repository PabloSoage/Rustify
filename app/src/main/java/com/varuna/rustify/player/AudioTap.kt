package com.varuna.rustify.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A copy of the PCM on its way to the speaker, for the audio lab (4.7.1).
 *
 * A `TeeAudioProcessor` sits in the player's audio chain and passes every buffer through untouched;
 * while [listening] is on it also mixes the buffer to mono into a ring the lab reads from. Off, it
 * costs one boolean check per buffer. With audio offload there is no PCM in the app at all — the DSP
 * decodes — so the lab turns offload off while it is open ([AudioOffload.labOpen]).
 */
object AudioTap : TeeAudioProcessor.AudioBufferSink {
    @Volatile var listening = false

    @Volatile var sampleRate = 0
        private set
    @Volatile private var channels = 0
    @Volatile private var encoding = C.ENCODING_INVALID

    private const val RING = 16_384
    private val ring = FloatArray(RING)
    private var write = 0
    private val lock = Any()

    val processor = TeeAudioProcessor(this)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!listening) return
        val ch = channels.coerceAtLeast(1)
        val b = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        synchronized(lock) {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2 * ch) {
                    var s = 0f
                    repeat(ch) { s += b.short / 32768f }
                    ring[write] = s / ch; write = (write + 1) % RING
                }
                C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4 * ch) {
                    var s = 0f
                    repeat(ch) { s += b.float }
                    ring[write] = s / ch; write = (write + 1) % RING
                }
                else -> {}
            }
        }
    }

    /** The newest [n] samples, oldest first (zeros before anything has played). */
    fun latest(n: Int): FloatArray = synchronized(lock) {
        val out = FloatArray(n)
        var r = (write - n + RING * 2) % RING
        for (i in 0 until n) { out[i] = ring[r]; r = (r + 1) % RING }
        out
    }
}
