package com.varuna.rustify.player

import android.content.Context

/**
 * The headphones' single button (4.9): one press plays or pauses, two and three do what the person
 * chose, and holding it fast-forwards.
 *
 * Media3 already turns a double press into "next", and nothing else. This takes the button over for
 * presses that come from outside the app (a Bluetooth or wired headset) and leaves the notification's
 * own buttons alone — the same line Media3 draws, for the same reason: only a physical button has
 * no other way to say "previous".
 *
 * The counting is here, free of Android; [RustifyForegroundService] feeds it key-downs and runs what
 * [resolve] returns once the presses stop coming.
 */
class HeadsetButton {
    enum class Action { TOGGLE, NEXT, PREVIOUS, NOTHING }

    private var presses = 0
    private var lastSeekAt: Long? = null

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

    /**
     * The button is being held: the key repeats every few tens of milliseconds, far too often to
     * seek on each. True when this repeat should jump forward [SEEK_MS].
     */
    fun holdRepeat(now: Long): Boolean {
        presses = 0
        lastSeekAt?.let { if (now - it < SEEK_EVERY_MS) return false }
        lastSeekAt = now
        return true
    }

    companion object {
        const val WINDOW_MS = 400L
        const val SEEK_MS = 10_000L
        const val SEEK_EVERY_MS = 400L

        private const val KEY_DOUBLE = "headset_double_press"
        private const val KEY_TRIPLE = "headset_triple_press"
        private const val KEY_HOLD = "headset_hold_seeks"

        /** What two and three presses may be set to; one press is always play/pause. */
        val CHOICES = listOf(Action.NEXT, Action.PREVIOUS, Action.NOTHING)

        private fun prefs(context: Context) = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)

        private fun read(context: Context, key: String, default: Action): Action =
            prefs(context).getString(key, null)?.let { s -> CHOICES.firstOrNull { it.name == s } } ?: default

        fun double(context: Context): Action = read(context, KEY_DOUBLE, Action.NEXT)
        fun triple(context: Context): Action = read(context, KEY_TRIPLE, Action.PREVIOUS)
        fun holdSeeks(context: Context): Boolean = prefs(context).getBoolean(KEY_HOLD, true)

        fun setDouble(context: Context, a: Action) = prefs(context).edit().putString(KEY_DOUBLE, a.name).apply()
        fun setTriple(context: Context, a: Action) = prefs(context).edit().putString(KEY_TRIPLE, a.name).apply()
        fun setHoldSeeks(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_HOLD, on).apply()
    }
}
