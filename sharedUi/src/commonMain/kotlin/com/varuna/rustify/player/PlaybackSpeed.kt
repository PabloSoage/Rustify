package com.varuna.rustify.player

import com.varuna.rustify.platform.KeyValueStore
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Playback speed and pitch (4.9): podcasts and audiobooks faster, a song slower to learn it, or the
 * same song a tone lower to sing along. Media3 does both with Sonic in the PCM chain, so anything
 * but 1× and 0 semitones takes offload off ([AudioOffload.Blocker.SPEED]): the audio DSP plays the
 * file as it is.
 *
 * Global and remembered, like the equaliser, in the settings store ([com.varuna.rustify.ui.AppPlatform.prefs]'s
 * `rustify_settings`). The reset is one tap in the same sheet.
 */
object PlaybackSpeed {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2f
    const val SPEED_STEP = 0.05f
    const val MAX_SEMITONES = 6

    private const val KEY_SPEED = "playback_speed"
    private const val KEY_SEMITONES = "playback_pitch_semitones"

    fun speed(settings: KeyValueStore): Float = snap(settings.getFloat(KEY_SPEED, 1f))
    fun semitones(settings: KeyValueStore): Int = settings.getInt(KEY_SEMITONES, 0).coerceIn(-MAX_SEMITONES, MAX_SEMITONES)

    fun set(settings: KeyValueStore, speed: Float, semitones: Int) {
        settings.putFloat(KEY_SPEED, snap(speed))
        settings.putInt(KEY_SEMITONES, semitones.coerceIn(-MAX_SEMITONES, MAX_SEMITONES))
    }

    fun isDefault(settings: KeyValueStore): Boolean = isDefault(speed(settings), semitones(settings))
    fun isDefault(speed: Float, semitones: Int): Boolean = snap(speed) == 1f && semitones == 0

    /** A semitone is a twelfth of an octave, and an octave doubles the frequency. */
    fun pitchFactor(semitones: Int): Float = 2.0.pow(semitones / 12.0).toFloat()

    /** To the slider's step, so 1.0000001 from a float slider still reads as normal speed. */
    fun snap(speed: Float): Float =
        ((speed.coerceIn(MIN_SPEED, MAX_SPEED) / SPEED_STEP).roundToInt() * SPEED_STEP * 100).roundToInt() / 100f
}
