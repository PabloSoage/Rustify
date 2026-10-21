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

    /** Why offload is not used right now, or null when it is wanted. */
    enum class Blocker { SWITCHED_OFF, EQUALIZER, FADE }

    fun blocker(enabled: Boolean, equalizerOn: Boolean, fadeSeconds: Int): Blocker? = when {
        !enabled -> Blocker.SWITCHED_OFF
        equalizerOn -> Blocker.EQUALIZER
        fadeSeconds > 0 -> Blocker.FADE
        else -> null
    }

    fun blocker(context: Context): Blocker? =
        blocker(isEnabled(context), EqualizerController.isEnabled(context), TransitionFade.seconds(context))
}
