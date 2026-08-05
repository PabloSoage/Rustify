package com.varuna.rustify.player

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.varuna.rustify.webplayer.WebPlayerController

/**
 * Session player facade for the experimental web-player mode.
 *
 * The MediaSession derives the notification (and the lockscreen / Android Auto transport) from the
 * player it was given — not from [AudioPlayerService]'s own state. In web mode the audio comes from a
 * WebView while ExoPlayer sits idle, so the session used to advertise "paused" no matter what the page
 * was doing: the buttons worked, but the play/pause icon and the progress were wrong.
 *
 * [ForwardingSimpleBasePlayer] is media3's supported way to fix that: it decorates the real player and
 * lets a subclass rewrite the reported [State] and intercept commands, while [invalidateState] pushes
 * the change out so the session refreshes itself.
 *
 * Outside web mode every call falls straight through to the wrapped player, so normal playback is
 * completely untouched.
 */
@UnstableApi
class WebSessionPlayer(
    delegate: Player,
    private val isWebMode: () -> Boolean
) : ForwardingSimpleBasePlayer(delegate) {

    override fun getState(): State {
        val base = super.getState()
        if (!isWebMode()) return base
        // SimpleBasePlayer asserts that a non-IDLE state has a non-empty playlist. AudioPlayerService
        // publishes a metadata-only MediaItem when web playback starts, but until it does the timeline
        // can be empty — report the delegate's state unchanged rather than trip the assertion.
        if (base.timeline.isEmpty()) return base

        val web = WebPlayerController.state.value
        if (!web.available) return base

        return base.buildUpon()
            .setPlayWhenReady(web.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(web.positionMs)
            .setIsLoading(false)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!isWebMode()) return super.handleSetPlayWhenReady(playWhenReady)
        if (playWhenReady) WebPlayerController.play() else WebPlayerController.pause()
        // No invalidateState() here: SimpleBasePlayer already invalidates once the returned future
        // completes, and calling it mid-command would re-enter the state machine. The next poll
        // (≤1s) confirms what the page actually did.
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        if (!isWebMode()) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> WebPlayerController.next()

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> WebPlayerController.previous()

            else -> WebPlayerController.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * Re-reads the state and notifies the session. Must run on the application thread; the web state
     * poll already delivers on the main thread.
     */
    fun refresh() = invalidateState()
}
