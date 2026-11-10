package com.varuna.rustify.player

import android.content.Context
import android.media.AudioDeviceInfo

/**
 * Pause when the headphones or the Bluetooth go away, and pick up again if they come back soon (4.9).
 *
 * Before this, nothing listened for `ACTION_AUDIO_BECOMING_NOISY`, so pulling out the headphones or
 * losing the Bluetooth carried on through the phone's speaker. Media3's own answer,
 * `setHandleAudioBecomingNoisy(true)`, was tried and left off for a reason worth keeping: it only
 * pauses. Starting a car drops its head unit for a few seconds, the link comes back, and the music
 * stays paused — so every trip began with a tap on the phone. What a person means by "it
 * disconnected for a moment" is "carry on", and that is what this does: pause at once, and resume
 * if an external output reappears within [windowSeconds].
 *
 * Android Auto never went through this. It holds the session itself and pauses on its own when it
 * goes, which is why the car with Android Auto was fine and the car without it was not.
 *
 * The decisions live here, free of Android, so they are tested on the JVM; [AudioPlayerService]
 * only feeds the events in and carries out what comes back.
 */
class DisconnectResume(
    private val windowMs: () -> Long,
    private val ignoreConnectPlay: () -> Boolean,
) {
    sealed interface Action {
        data object None : Action
        data object Pause : Action
        /** Resume after [delayMs]: Android takes a moment to move audio onto the new device. */
        data class ResumeAfter(val delayMs: Long) : Action
    }

    /** When the output went away while playing; null when nothing is waiting to resume. */
    private var pausedAt: Long? = null

    /** When an external output last appeared, for [shouldIgnoreControllerPlay]. */
    private var connectedAt: Long? = null

    val isPending: Boolean get() = pausedAt != null

    /** `ACTION_AUDIO_BECOMING_NOISY`. Only a song that was playing is paused and remembered. */
    fun onNoisy(now: Long, isPlaying: Boolean): Action {
        if (!isPlaying) return Action.None
        pausedAt = now
        return Action.Pause
    }

    /** Output devices appeared (`AudioDeviceCallback.onAudioDevicesAdded`, sinks only). */
    fun onOutputsAdded(now: Long, types: List<Int>): Action {
        if (types.none { it in EXTERNAL_OUTPUTS }) return Action.None
        connectedAt = now
        val at = pausedAt ?: return Action.None
        val window = windowMs()
        if (window <= 0 || now - at > window) {
            pausedAt = null
            return Action.None
        }
        return Action.ResumeAfter(SETTLE_MS)
    }

    /**
     * The moment the delayed resume falls due. Anything that cancelled it in between (a tap, a call,
     * the window running out) has cleared [pausedAt] or makes this refuse.
     */
    fun confirmResume(now: Long, inCall: Boolean): Boolean {
        val at = pausedAt ?: return false
        pausedAt = null
        if (inCall) return false
        return now - at <= windowMs() + SETTLE_MS
    }

    /**
     * The person chose: play, pause, skip, a new queue. Their choice wins over a pending resume.
     *
     * Except for the first [OWN_PAUSE_ECHO_MS]: the disconnection's own pause goes through
     * `Player.pause()` too and comes straight back here as a user request, after [onNoisy] has
     * already remembered it. Taken as a choice, it cancelled every resume (seen on WSA with a faked
     * headset). Nobody decides anything that fast, and a real tap that fast changes nothing anyway:
     * a pause leaves it paused, and a play makes the resume a no-op.
     */
    fun onUserCommand(now: Long) {
        val at = pausedAt ?: return
        if (now - at < OWN_PAUSE_ECHO_MS) return
        pausedAt = null
    }

    /**
     * Some cars and headsets send PLAY the moment they connect, even when the music had been paused
     * on purpose. With the option on, a PLAY from a controller that close to a connection is ignored
     * — unless the music had been paused by the disconnection, when resuming is wanted anyway.
     *
     * Off by default because the ordinary case, getting in and pressing play on the steering wheel,
     * falls in the same few seconds.
     */
    fun shouldIgnoreControllerPlay(now: Long): Boolean {
        if (!ignoreConnectPlay() || pausedAt != null) return false
        val at = connectedAt ?: return false
        return now - at <= CONNECT_PLAY_WINDOW_MS
    }

    companion object {
        const val SETTLE_MS = 1_500L
        const val CONNECT_PLAY_WINDOW_MS = 5_000L
        const val OWN_PAUSE_ECHO_MS = 1_000L

        /**
         * How long the playback service stays in the foreground once paused
         * (`MediaSessionService.setForegroundServiceTimeoutMs`, set in [RustifyForegroundService]).
         *
         * The resume after a reconnection depends on it. It starts with the app in the background,
         * from an `AudioDeviceCallback` and with no tap, and Android 17 mutes, without an error,
         * audio started from the background unless a foreground service is already running. While
         * the service is still in the foreground the resume plays. The longest window offered has
         * to end before the service leaves the foreground ([WINDOW_CHOICES], pinned by a test).
         * This is media3's default (10 min), written down so the dependency is visible.
         */
        const val FOREGROUND_AFTER_PAUSE_MS = 600_000L

        /** Outputs a person wears or plugs in. The speaker, the earpiece and HDMI are not. */
        val EXTERNAL_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
        )

        private const val KEY_WINDOW = "disconnect_resume_seconds"
        private const val KEY_IGNORE_PLAY = "ignore_connect_play"

        /** The choices offered in settings; 0 is "never resume". */
        val WINDOW_CHOICES = intArrayOf(15, 60, 300, 0)
        const val DEFAULT_WINDOW_SECONDS = 60

        private fun prefs(context: Context) = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)

        fun windowSeconds(context: Context): Int = prefs(context).getInt(KEY_WINDOW, DEFAULT_WINDOW_SECONDS)
        fun setWindowSeconds(context: Context, seconds: Int) = prefs(context).edit().putInt(KEY_WINDOW, seconds).apply()

        fun ignoreConnectPlay(context: Context): Boolean = prefs(context).getBoolean(KEY_IGNORE_PLAY, false)
        fun setIgnoreConnectPlay(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_IGNORE_PLAY, on).apply()
    }
}
