package com.varuna.rustify.player

import android.content.Context

/**
 * Audio offload (4.7.1): the phone's audio DSP decodes the song and the CPU sleeps between buffers
 * of several minutes, instead of waking every few milliseconds to decode in software. The biggest
 * battery saving there is for listening with the screen off.
 *
 * On by default; media3 only offloads when the device says it can, for an audio-only track, and
 * releases the wake lock while it sleeps. Two things in the app need the CPU awake and so turn it
 * off while they are on: the system equaliser (an effect on the session) and the fade between songs
 * (a loop that sets the volume every 50 ms near each end).
 */
object AudioOffload {
    const val KEY_ENABLED = "audio_offload_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** The audio lab is open: it reads the PCM, and with offload there is none in the app. */
    @Volatile var labOpen = false

    /** The Video tab is playing: audio has to stay in step with the picture, which offload would not. */
    @Volatile var videoMode = false

    /**
     * The player's clock came loose from the audio while offloaded (PositionSanity), so offload is
     * off until the app restarts. Reported on 5.0 as a progress bar looping and songs ending early;
     * a session without offload costs battery, a song cut in half costs the song.
     */
    @Volatile var distrusted = false

    /** Why offload is not used right now, or null when it is wanted. */
    enum class Blocker { SWITCHED_OFF, POSITION_DRIFT, EQUALIZER, FADE, AUDIO_LAB, VIDEO, SPEED, SKIP_SILENCE, AUTOEQ, BIT_PERFECT, STEREO }

    fun blocker(enabled: Boolean, equalizerOn: Boolean, fadeSeconds: Int, labOpen: Boolean = false, video: Boolean = false, speedChanged: Boolean = false, skipSilence: Boolean = false, autoEq: Boolean = false, bitPerfect: Boolean = false, stereo: Boolean = false, drifted: Boolean = false): Blocker? = when {
        !enabled -> Blocker.SWITCHED_OFF
        drifted -> Blocker.POSITION_DRIFT
        labOpen -> Blocker.AUDIO_LAB
        video -> Blocker.VIDEO
        // Bit-perfect needs the PCM in the app: offload hands the encoded file to the DSP instead.
        bitPerfect -> Blocker.BIT_PERFECT
        equalizerOn -> Blocker.EQUALIZER
        fadeSeconds > 0 -> Blocker.FADE
        speedChanged -> Blocker.SPEED
        skipSilence -> Blocker.SKIP_SILENCE
        autoEq -> Blocker.AUTOEQ
        stereo -> Blocker.STEREO
        else -> null
    }

    fun blocker(context: Context): Blocker? =
        blocker(isEnabled(context), EqualizerController.isEnabled(context), TransitionFade.seconds(context), labOpen, videoMode, !PlaybackSpeed.isDefault(com.varuna.rustify.ui.AndroidAppPlatform(context).prefs()), SkipSilence.enabled(context), AutoEqController.isActive(), BitPerfect.active(context), StereoTools.settings.active, distrusted)
}
