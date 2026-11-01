package com.varuna.rustify.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.varuna.rustify.MainActivity
import com.varuna.rustify.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A Quick Settings tile (4.9): play/pause from the notification shade, with the song as its subtitle.
 *
 * Only while the shade is open does it follow the player ([onStartListening] to [onStopListening]);
 * the rest of the time it costs nothing. With no player in the process yet — the app has not been
 * opened since the phone started — a tap opens the app instead, because there is nothing to resume.
 */
class PlaybackTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var watch: Job? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val player = AudioPlayerService.instance
        if (player == null) { render(playing = false, title = null); return }
        watch = s.launch {
            player.state
                .map { it.isPlaying to it.currentTrack?.name }
                .distinctUntilChanged()
                .collect { (playing, title) -> render(playing, title) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val player = AudioPlayerService.instance
        if (player == null || player.state.value.currentTrack == null) {
            openApp()
            return
        }
        if (player.state.value.isPlaying) player.pause() else player.play()
    }

    private fun render(playing: Boolean, title: String?) {
        val tile = qsTile ?: return
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (playing) R.string.tile_pause else R.string.tile_play)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = title ?: getString(R.string.app_name)
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to refresh the tile, if it is showing; called when playback starts or stops. */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, PlaybackTileService::class.java))
            }
        }
    }
}
