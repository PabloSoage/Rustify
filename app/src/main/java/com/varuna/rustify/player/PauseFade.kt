package com.varuna.rustify.player

import android.content.Context

/**
 * A short fade when pausing and resuming (4.10), instead of the audio stopping mid-note.
 *
 * Not for every pause: the one made because the headphones or the car went away must cut at once
 * (see [DisconnectResume]) — a fade there is three hundred milliseconds out of the phone's speaker.
 */
object PauseFade {
    const val DURATION_MS = 300L
    const val STEPS = 12
    val STEP_MS = DURATION_MS / STEPS

    /** The volumes to step through from [from] to [to], ending exactly on [to]. */
    fun ramp(from: Float, to: Float, steps: Int = STEPS): List<Float> =
        (1..steps).map { i -> from + (to - from) * i / steps }

    private const val KEY = "pause_fade"

    private fun prefs(context: Context) = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY, on).apply()
}
