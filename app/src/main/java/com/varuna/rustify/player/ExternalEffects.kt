package com.varuna.rustify.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Tells equaliser apps which audio session is Rustify's (`22` §4): Wavelet, the phone's own sound
 * settings and the like attach their effects to a session when a player announces it with
 * `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`, and let go on `ACTION_CLOSE_…`. Without it they can
 * only reach Rustify through workarounds (Wavelet's "legacy mode" reads `dumpsys` with the DUMP
 * permission granted over adb). Poweramp and Symfonium announce theirs the same way.
 */
object ExternalEffects {
    @Volatile private var announced = AudioEffect.ERROR_BAD_VALUE

    /** The intents for [session], in order: the previous session closed, this one opened. */
    fun intentsFor(previous: Int, session: Int): List<Pair<String, Int>> = buildList {
        if (previous == session) return@buildList
        if (previous > 0) add(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION to previous)
        if (session > 0) add(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION to session)
    }

    fun announce(context: Context, session: Int) = send(context, session)

    fun close(context: Context) = send(context, AudioEffect.ERROR_BAD_VALUE)

    @Synchronized
    private fun send(context: Context, session: Int) {
        intentsFor(announced, session).forEach { (action, id) ->
            runCatching {
                context.sendBroadcast(
                    Intent(action)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                )
            }
        }
        announced = session
    }
}
