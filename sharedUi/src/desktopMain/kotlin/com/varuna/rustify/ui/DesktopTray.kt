// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopTray.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import com.varuna.rustify.player.PlayerController
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.tile_pause
import com.varuna.rustify.ui.resources.tile_play
import com.varuna.rustify.ui.resources.tray_quit
import com.varuna.rustify.ui.resources.tray_show
import com.varuna.rustify.ui.resources.widget_next
import com.varuna.rustify.ui.resources.widget_previous
import org.jetbrains.compose.resources.stringResource

/**
 * The phone's widget, on the desktop (docs serie-4.x/27 §2.11): an icon in the system tray with the
 * song playing as its tooltip, and play/pause, next, previous, the window and quit in its menu. Read
 * from the same [PlayerController] the window has, through its public face only.
 *
 * Clicking the icon (double click on Windows) shows the window, which is how it comes back after it
 * was closed with "keep playing" on. The labels are the widget's and the quick tile's.
 */
@Composable
fun ApplicationScope.DesktopTray(icon: Painter, player: PlayerController, onShow: () -> Unit, onQuit: () -> Unit) {
    val state by player.state.collectAsState()
    val track = state.currentTrack
    val tooltip = if (track == null) "Rustify" else buildString {
        append(track.name)
        val artists = track.artists.joinToString(", ") { it.name }
        if (artists.isNotBlank()) append(" — ").append(artists)
    }
    val play = stringResource(Res.string.tile_play)
    val pause = stringResource(Res.string.tile_pause)
    val previous = stringResource(Res.string.widget_previous)
    val next = stringResource(Res.string.widget_next)
    val show = stringResource(Res.string.tray_show)
    val quit = stringResource(Res.string.tray_quit)
    Tray(
        icon = icon,
        tooltip = tooltip,
        onAction = onShow,
    ) {
        Item(if (state.isPlaying) pause else play, enabled = track != null, onClick = { player.togglePlayPause() })
        Item(previous, enabled = track != null, onClick = { player.skipToPrevious() })
        Item(next, enabled = track != null, onClick = { player.skipToNext() })
        Separator()
        Item(show, onClick = onShow)
        Item(quit, onClick = onQuit)
    }
}
