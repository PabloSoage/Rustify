package com.varuna.rustify.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

/**
 * Stereo tools (`22` §7), the ones UAPP, Symfonium, Wavelet and Poweramp all have and Rustify did not:
 *
 * - **Crossfeed:** on headphones each ear hears only its own channel, which a hard-panned 60s record
 *   makes tiring. A little of the other channel, low-passed (the head shadows the highs) is fed in,
 *   as a loudspeaker in a room would; the Bauer/bs2b idea, at its usual 700 Hz and 4.5 dB.
 * - **Balance:** one side quieter, for an uneven ear or uneven earbuds.
 * - **Mono:** both channels mixed, for one earbud.
 * - **Voice reduction:** the centre (usually the voice) cancelled, the bass kept, for karaoke.
 *
 * A media3 processor in the PCM chain: 16-bit and float stereo, anything else passes untouched.
 * When none is on it reports itself inactive and media3 skips it entirely. Any of them on turns
 * offload off ([AudioOffload]) and counts as changing the samples for bit-perfect ([BitPerfect]).
 */
object StereoTools {
    private const val PREFS = "rustify_settings"

    data class Settings(
        val crossfeed: Boolean = false,
        /** -100 (left only) … 0 … 100 (right only). */
        val balance: Int = 0,
        val mono: Boolean = false,
        val voiceReduction: Boolean = false,
    ) {
        val active: Boolean get() = crossfeed || balance != 0 || mono || voiceReduction
    }

    @Volatile var settings = Settings()
        private set

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings = Settings(
            crossfeed = p.getBoolean("stereo_crossfeed", false),
            balance = p.getInt("stereo_balance", 0),
            mono = p.getBoolean("stereo_mono", false),
            voiceReduction = p.getBoolean("stereo_voice_reduction", false),
        )
        return settings
    }

    fun save(context: Context, s: Settings) {
        settings = s
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("stereo_crossfeed", s.crossfeed)
            .putInt("stereo_balance", s.balance.coerceIn(-100, 100))
            .putBoolean("stereo_mono", s.mono)
            .putBoolean("stereo_voice_reduction", s.voiceReduction)
            .apply()
    }

    fun isActive(context: Context): Boolean = load(context).active

    /** One-pole low-pass coefficient for [cutoffHz] at [sampleRate]. */
    fun onePole(cutoffHz: Double, sampleRate: Int): Float = exp(-2.0 * PI * cutoffHz / sampleRate).toFloat()

    /** Gains of (left, right) for a balance in -100…100: the side being turned away from goes down. */
    fun balanceGains(balance: Int): Pair<Float, Float> {
        val b = balance.coerceIn(-100, 100) / 100f
        return (if (b > 0) 1f - b else 1f) to (if (b < 0) 1f + b else 1f)
    }

    /** Filter memory, one per stream. */
    class State {
        var crossL = 0f
        var crossR = 0f
        var bass = 0f
    }

    private val CROSSFEED_GAIN = 10.0.pow(-4.5 / 20).toFloat()

    /**
     * Processes interleaved stereo [frames] in place. The arithmetic of the processor, kept apart
     * from buffers so it can be tested on its own.
     */
    fun process(samples: FloatArray, sampleRate: Int, s: Settings, st: State, count: Int = samples.size) {
        val aCross = onePole(700.0, sampleRate)
        val aBass = onePole(200.0, sampleRate)
        val (gl, gr) = balanceGains(s.balance)
        var i = 0
        while (i + 1 < count) {
            var l = samples[i]
            var r = samples[i + 1]
            if (s.voiceReduction) {
                val mid = (l + r) * 0.5f
                st.bass = aBass * st.bass + (1 - aBass) * mid
                val side = (l - r) * 0.5f
                l = side + st.bass
                r = -side + st.bass
            }
            if (s.mono) {
                val m = (l + r) * 0.5f
                l = m; r = m
            }
            if (s.crossfeed) {
                st.crossL = aCross * st.crossL + (1 - aCross) * l
                st.crossR = aCross * st.crossR + (1 - aCross) * r
                // Normalised so a centred (mono) signal keeps its level at low frequencies.
                val nl = (l + CROSSFEED_GAIN * st.crossR) / (1 + CROSSFEED_GAIN)
                val nr = (r + CROSSFEED_GAIN * st.crossL) / (1 + CROSSFEED_GAIN)
                l = nl; r = nr
            }
            samples[i] = l * gl
            samples[i + 1] = r * gr
            i += 2
        }
    }

    /** The media3 side: lives in the player's chain, reads [settings] at each configuration. */
    class Processor : BaseAudioProcessor() {
        private var state = State()
        private var applied = Settings()
        private var scratch = FloatArray(0)

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            applied = settings
            val stereo = inputAudioFormat.channelCount == 2 &&
                (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
            return if (stereo && applied.active) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val format = inputAudioFormat
            val bytes = inputBuffer.remaining()
            val float = format.encoding == C.ENCODING_PCM_FLOAT
            val n = bytes / if (float) 4 else 2
            if (scratch.size < n) scratch = FloatArray(n)
            val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
            if (float) for (k in 0 until n) scratch[k] = inBuf.float
            else for (k in 0 until n) scratch[k] = inBuf.short / 32768f
            // Read live: while the processor is in the chain, a change applies at once.
            applied = settings
            process(scratch, format.sampleRate, applied, state, n)
            val out = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
            if (float) for (k in 0 until n) out.putFloat(scratch[k])
            else for (k in 0 until n) out.putShort((scratch[k] * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
            out.flip()
        }

        override fun onFlush() {
            state = State()
        }
    }

    val processor = Processor()
}
