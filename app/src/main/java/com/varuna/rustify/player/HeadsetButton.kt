package com.varuna.rustify.player

import android.content.Context

/**
 * The headphones' single button (4.9): one press plays or pauses, two and three do what the person
 * chose.
 *
 * Media3 already turns a double press into "next", and nothing else. This takes the button over for
 * presses that come from outside the app (a Bluetooth or wired headset) and leaves the notification's
 * own buttons alone — the same line Media3 draws, for the same reason: only a physical button has
 * no other way to say "previous".
 *
 * Holding the button is not ours to use: Android keeps a long press of play/pause for the voice
 * assistant and never passes the repeats on (tried on the emulator: the Assistant opened, the app
 * saw nothing). So there is no "hold to fast-forward", which Musicolet's list made look possible.
 *
 * The counting is here, free of Android; [RustifyForegroundService] feeds it key-downs and runs what
 * [resolve] returns once the presses stop coming.
 */
class HeadsetButton {
    enum class Action { TOGGLE, NEXT, PREVIOUS, NOTHING }

    private var presses = 0

    /** A press. Returns how long to wait for another before calling [resolve]. */
    fun press(): Long {
        presses++
        return WINDOW_MS
    }

    /** The presses stopped: what they add up to. */
    fun resolve(double: Action, triple: Action): Action {
        val n = presses
        presses = 0
        return when {
            n <= 0 -> Action.NOTHING
            n == 1 -> Action.TOGGLE
            n == 2 -> double
            else -> triple
        }
    }

    companion object {
        const val WINDOW_MS = 400L

        private const val KEY_DOUBLE = "headset_double_press"
        private const val KEY_TRIPLE = "headset_triple_press"

        /** What two and three presses may be set to; one press is always play/pause. */
        val CHOICES = listOf(Action.NEXT, Action.PREVIOUS, Action.NOTHING)

        private fun prefs(context: Context) = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)

        private fun read(context: Context, key: String, default: Action): Action =
            prefs(context).getString(key, null)?.let { s -> CHOICES.firstOrNull { it.name == s } } ?: default

        fun double(context: Context): Action = read(context, KEY_DOUBLE, Action.NEXT)
        fun triple(context: Context): Action = read(context, KEY_TRIPLE, Action.PREVIOUS)

        fun setDouble(context: Context, a: Action) = prefs(context).edit().putString(KEY_DOUBLE, a.name).apply()
        fun setTriple(context: Context, a: Action) = prefs(context).edit().putString(KEY_TRIPLE, a.name).apply()
    }
}
