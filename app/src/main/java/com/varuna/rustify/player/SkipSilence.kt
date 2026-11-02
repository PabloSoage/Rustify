package com.varuna.rustify.player

import android.content.Context

/**
 * Skipping silence (`18`, after Metrolist): media3's `skipSilenceEnabled` drops long silent stretches
 * — the minute of nothing before a hidden track, a live album's gaps. Off by default: it changes the
 * music, and like the equaliser it needs the CPU, so it turns audio offload off while it is on
 * ([AudioOffload.Blocker.SKIP_SILENCE]).
 */
object SkipSilence {
    const val KEY = "skip_silence"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
