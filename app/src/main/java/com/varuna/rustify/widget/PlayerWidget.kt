package com.varuna.rustify.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.varuna.rustify.MainActivity
import com.varuna.rustify.R
import com.varuna.rustify.player.AudioPlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The home-screen widget (4.9): cover, song, artist, and previous / play-pause / next.
 *
 * It is redrawn when the song or the play state changes ([refresh], from the player), never on a
 * timer: a widget that ticks is a widget that drains the battery to show a progress bar nobody is
 * looking at. Tapping anywhere but the buttons opens the app.
 */
class PlayerWidget : GlanceAppWidget() {

    /**
     * The state is read *inside* the content. Glance keeps a session open for a while after drawing,
     * and an update during it only recomposes — values read before `provideContent` stay as they
     * were. Read outside, the widget kept showing the previous song with a play button while the
     * next one played (seen on the emulator).
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = AudioPlayerService.instance?.state
            ?: kotlinx.coroutines.flow.MutableStateFlow(com.varuna.rustify.player.AudioPlayerState())

        provideContent {
            val state by flow.collectAsState()
            val track = state.currentTrack
            val title = track?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
            val artist = track?.artists?.joinToString(", ") { it.name }.orEmpty()
            val playing = state.isPlaying
            val coverUrl = track?.album?.images?.firstOrNull()?.url
            var cover by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(coverUrl) { cover = coverUrl?.let { loadCover(context, it) } }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = GlanceModifier.fillMaxSize()
                    .background(Color(0xFF1E1E1E))
                    .cornerRadius(16.dp)
                    .padding(8.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                val c = cover
                if (c != null) {
                    Image(
                        provider = ImageProvider(c),
                        contentDescription = null,
                        modifier = GlanceModifier.size(48.dp).cornerRadius(8.dp)
                    )
                } else {
                    Box(GlanceModifier.size(48.dp).background(Color(0xFF2A2A2A)).cornerRadius(8.dp)) {}
                }
                Spacer(GlanceModifier.width(10.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        title,
                        maxLines = 1,
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )
                    if (artist.isNotEmpty()) {
                        Text(artist, maxLines = 1, style = TextStyle(color = ColorProvider(Color(0xFFB3B3B3)), fontSize = 12.sp))
                    }
                }
                button(android.R.drawable.ic_media_previous, R.string.widget_previous, actionRunCallback<PreviousAction>())
                button(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) R.string.tile_pause else R.string.tile_play,
                    actionRunCallback<ToggleAction>()
                )
                button(android.R.drawable.ic_media_next, R.string.widget_next, actionRunCallback<NextAction>())
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun button(icon: Int, label: Int, action: androidx.glance.action.Action) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = androidx.glance.LocalContext.current.getString(label),
            modifier = GlanceModifier.size(40.dp).padding(6.dp).clickable(action)
        )
    }

    /** Small, software and once per change: a widget's bitmap travels through a Binder. */
    private suspend fun loadCover(context: Context, url: String): Bitmap? = runCatching {
        val request = coil.request.ImageRequest.Builder(context)
            .data(url)
            .size(192)
            .allowHardware(false)
            .build()
        coil.Coil.imageLoader(context).execute(request).drawable?.toBitmap(192, 192)
    }.getOrNull()

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { PlayerWidget().updateAll(context) }
        }
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

/**
 * A widget can outlive the player: with none in the process, there is nothing to control.
 * Glance runs callbacks on a background dispatcher and ExoPlayer only answers on the main thread
 * (it throws otherwise — seen on the emulator), so each one hops there first.
 */
private fun player(): AudioPlayerService? = AudioPlayerService.instance?.takeIf { it.state.value.currentTrack != null }

class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) {
            val p = player() ?: return@withContext
            if (p.state.value.isPlaying) p.pause() else p.play()
        }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { player()?.skipToNext() }
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { player()?.skipToPrevious() }
    }
}
