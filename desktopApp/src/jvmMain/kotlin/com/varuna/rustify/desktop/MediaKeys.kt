// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/MediaKeys.kt
package com.varuna.rustify.desktop

import com.varuna.rustify.engine.MediaRequest
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.engine.SystemMediaControls
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps the system's media controls and the player in step, for as long as the caller's coroutine
 * lives: what plays goes to the overlay / MPRIS / Now Playing, and the keyboard's media keys, a
 * headset button or `playerctl next` come back as player actions.
 *
 * Runs on the window's coroutine (the Swing main thread), which is the thread [QueuePlayer] wants.
 */
object MediaKeys {
    /** The system wants a position every so often, not every 250 ms the player reports one. */
    private const val POSITION_EVERY_MS = 2_000L

    suspend fun run(player: QueuePlayer, windowHandle: Long, onRaise: () -> Unit, onQuit: () -> Unit) = coroutineScope {
        if (!SystemMediaControls.start("Rustify", windowHandle)) return@coroutineScope

        launch {
            var lastTrack: String? = null
            var lastDuration: Long? = null
            var lastPlaying: Boolean? = null
            var lastPosition = Long.MIN_VALUE
            player.ui.collect { ui ->
                val track = ui.current
                if (track == null) {
                    if (lastTrack != null) SystemMediaControls.stopped()
                    lastTrack = null
                    return@collect
                }
                val duration = ui.durationMs ?: (track.durationSec * 1000L).takeIf { it > 0 }
                if (track.videoId != lastTrack || duration != lastDuration) {
                    SystemMediaControls.metadata(
                        title = track.title,
                        artist = track.artists.joinToString(", ") { it.name },
                        album = "",
                        coverUrl = track.thumbnailUrl.ifBlank { null },
                        durationMs = duration,
                    )
                    lastTrack = track.videoId
                    lastDuration = duration
                    lastPlaying = null // re-send the state with the new track
                }
                if (ui.playing != lastPlaying || abs(ui.positionMs - lastPosition) >= POSITION_EVERY_MS) {
                    SystemMediaControls.playback(ui.playing, ui.positionMs)
                    lastPlaying = ui.playing
                    lastPosition = ui.positionMs
                }
            }
        }

        launch {
            SystemMediaControls.requests().collect { request ->
                val playing = player.ui.value.playing
                when (request) {
                    MediaRequest.Play -> if (!playing) player.togglePause()
                    MediaRequest.Pause, MediaRequest.Stop -> if (playing) player.togglePause()
                    MediaRequest.Toggle -> player.togglePause()
                    MediaRequest.Next -> player.next()
                    MediaRequest.Previous -> player.previous()
                    is MediaRequest.SeekBy ->
                        player.seek((player.ui.value.positionMs + request.offsetMs).coerceAtLeast(0))
                    is MediaRequest.SetPosition -> player.seek(request.positionMs)
                    MediaRequest.Raise -> onRaise()
                    MediaRequest.Quit -> onQuit()
                }
            }
        }
    }
}
