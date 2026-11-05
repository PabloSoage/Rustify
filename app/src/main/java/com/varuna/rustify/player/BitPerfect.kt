package com.varuna.rustify.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Bit-perfect USB output (5.x §2, `22`), by Android's own route: since Android 14 an app can ask
 * for a USB output to be opened in exactly the track's format, with the system mixer out of the way
 * (`AudioManager.setPreferredMixerAttributes` with `MIXER_BEHAVIOR_BIT_PERFECT`). Symfonium uses the
 * same call; UAPP and Poweramp drive the DAC themselves over libusb or usbdevfs, which is step 2.
 *
 * What it promises: the samples the decoder hands over reach the DAC unchanged, at their own rate.
 * What it does not: media3 writes 16-bit or float PCM, so a 24-bit file arrives as its 16-bit
 * conversion. That is said in the status, not hidden.
 *
 * Off by default. While it is on and a USB output is connected, nothing that rescales the samples
 * runs on its own: loudness normalisation and ReplayGain are skipped. What the user turned on
 * themselves (the equaliser, AutoEq, the fade, the speed, skipping silence) stays on and is shown
 * as the reason it is not bit-perfect.
 */
object BitPerfect {
    const val KEY_ENABLED = "bit_perfect_usb"

    private fun prefs(context: Context) = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        // Either way it takes effect when the next song opens the output.
        status = Status(State.OFF)
        if (!on) clear(context)
    }

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** The decoded PCM as it leaves media3, or as a USB output offers to take it. */
    data class Pcm(val sampleRate: Int, val encoding: Int, val channelCount: Int)

    /** One format a USB output offers; only the bit-perfect ones are of use. */
    data class Offer(val pcm: Pcm, val bitPerfect: Boolean)

    /** Something the user turned on that changes the samples. */
    enum class Spoiler { EQUALIZER, AUTOEQ, FADE, SPEED, SKIP_SILENCE, STEREO }

    fun spoiler(equalizerOn: Boolean, autoEq: Boolean, fadeSeconds: Int, speedChanged: Boolean, skipSilence: Boolean, stereo: Boolean = false): Spoiler? = when {
        equalizerOn -> Spoiler.EQUALIZER
        autoEq -> Spoiler.AUTOEQ
        fadeSeconds > 0 -> Spoiler.FADE
        speedChanged -> Spoiler.SPEED
        skipSilence -> Spoiler.SKIP_SILENCE
        stereo -> Spoiler.STEREO
        else -> null
    }

    fun spoiler(context: Context): Spoiler? = spoiler(
        EqualizerController.isEnabled(context), AutoEqController.isActive(), TransitionFade.seconds(context),
        !PlaybackSpeed.isDefault(com.varuna.rustify.ui.AndroidAppPlatform(context).prefs()), SkipSilence.enabled(context), StereoTools.settings.active
    )

    /** The offer for exactly this PCM: same rate, same encoding, same channels. Anything else is a conversion. */
    fun choose(track: Pcm, offers: List<Offer>): Offer? = offers.firstOrNull { it.bitPerfect && it.pcm == track }

    /** What media3's `DefaultAudioSink` writes for a decoded [pcmEncoding]: float for high resolution when float output is on, 16-bit otherwise. */
    fun sinkEncoding(pcmEncoding: Int, floatOutput: Boolean): Int =
        if (floatOutput && pcmEncoding in HIGH_RES) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT

    private val HIGH_RES = setOf(
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT
    )

    enum class State { OFF, OLD_ANDROID, NO_USB, SPOILED, NO_MATCH, ACTIVE }

    data class Status(val state: State, val pcm: Pcm? = null, val device: String? = null, val spoiler: Spoiler? = null)

    @Volatile var status: Status = Status(State.OFF)
        private set

    @Volatile private var preferredOn: AudioDeviceInfo? = null

    fun usbOutput(context: Context): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    /** On, possible, and a USB output there: the gains that would rescale the samples are skipped. */
    fun active(context: Context): Boolean = isEnabled(context) && supported && usbOutput(context) != null

    /**
     * Called by [Sink] on the playback thread, before the output for [pcm] is opened, which is
     * when a preferred mixer takes effect.
     */
    fun onConfigure(context: Context, pcm: Pcm) {
        status = when {
            !isEnabled(context) -> Status(State.OFF)
            !supported -> Status(State.OLD_ANDROID)
            else -> runCatching { apply34(context, pcm) }.getOrElse {
                android.util.Log.w("BitPerfect", "could not set the mixer: ${it.message}")
                Status(State.NO_MATCH, pcm)
            }
        }
        android.util.Log.i("BitPerfect", "$status")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun apply34(context: Context, pcm: Pcm): Status {
        val am = context.getSystemService(AudioManager::class.java)
        val usb = usbOutput(context) ?: run { clear(context); return Status(State.NO_USB, pcm) }
        val name = usb.productName?.toString()
        spoiler(context)?.let { clear(context); return Status(State.SPOILED, pcm, name, it) }
        val mixers = am.getSupportedMixerAttributes(usb)
        val offers = mixers.map {
            Offer(
                Pcm(it.format.sampleRate, it.format.encoding, it.format.channelCount),
                it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            )
        }
        val chosen = choose(pcm, offers) ?: run { clear(context); return Status(State.NO_MATCH, pcm, name) }
        val ok = am.setPreferredMixerAttributes(mediaAttributes(), usb, mixers[offers.indexOf(chosen)])
        if (!ok) return Status(State.NO_MATCH, pcm, name)
        preferredOn = usb
        return Status(State.ACTIVE, pcm, name)
    }

    /** Hands the USB output back to the system mixer. */
    fun clear(context: Context) {
        val device = preferredOn ?: return
        preferredOn = null
        if (!supported) return
        runCatching {
            context.getSystemService(AudioManager::class.java).clearPreferredMixerAttributes(mediaAttributes(), device)
        }
    }

    private fun mediaAttributes(): android.media.AudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Learns each track's PCM format just before media3 opens the output for it. */
    class Sink(private val context: Context, sink: AudioSink, private val floatOutput: Boolean) : ForwardingAudioSink(sink) {
        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            val f = audioSinkConfig.format
            if (MimeTypes.AUDIO_RAW == f.sampleMimeType) {
                onConfigure(context, Pcm(f.sampleRate, sinkEncoding(f.pcmEncoding, floatOutput), f.channelCount))
            }
            super.configure(audioSinkConfig)
        }
    }
}
