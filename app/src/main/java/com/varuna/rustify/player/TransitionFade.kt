package com.varuna.rustify.player

import android.content.Context

/**
 * Fading between songs (4.7): the end of one fades out and the start of the next fades in.
 *
 * Not a true crossfade — Rustify plays one song at a time through one player, so the two never
 * overlap; overlapping them would take a second player and every queue decision doubled. What it
 * does give is what most people mean by turning crossfade on: no abrupt cut and no blast at the
 * start. Off by default.
 */
object TransitionFade {
    const val KEY_SECONDS = "fade_between_songs_seconds"
    const val MAX_SECONDS = 12

    fun seconds(context: Context): Int =
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE).getInt(KEY_SECONDS, 0)

    fun setSeconds(context: Context, s: Int) {
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE).edit()
            .putInt(KEY_SECONDS, s.coerceIn(0, MAX_SECONDS)).apply()
        // The fade needs the CPU awake at the end of this song too: re-decide offload now.
        AudioPlayerService.instance?.applyAudioOffload()
    }

    /**
     * The volume multiplier at [positionMs] of a song [durationMs] long, fading over [fadeMs] at each
     * end. A song shorter than two fades gets fades of a third of its length each, so it is never
     * silent in the middle. Unknown duration: fade in only.
     */
    fun gain(positionMs: Long, durationMs: Long, fadeMs: Long): Float {
        if (fadeMs <= 0) return 1f
        val fade = if (durationMs > 0) minOf(fadeMs, durationMs / 3) else fadeMs
        if (fade <= 0) return 1f
        val fadeIn = (positionMs.toFloat() / fade).coerceIn(0f, 1f)
        val fadeOut = if (durationMs > 0) ((durationMs - positionMs).toFloat() / fade).coerceIn(0f, 1f) else 1f
        return minOf(fadeIn, fadeOut)
    }
}
