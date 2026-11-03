// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/ui/App.kt
package com.varuna.rustify.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.YtmTrack
import com.varuna.rustify.bridge.decodeWire
import com.varuna.rustify.desktop.DesktopPlayer
import kotlinx.coroutines.launch

/** The same green the Android app's dark theme is built on, so the two read as one product. */
private val Accent = Color(0xFF1DB954)

@Composable
fun RustifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, secondary = Accent),
        content = { Surface(modifier = Modifier.fillMaxSize()) { content() } },
    )
}

/**
 * The first desktop screen: search YouTube Music (no account needed, as on the phone), play the
 * results as a queue. Spotify's library, Home and the rest of the app's screens come as the
 * repositories move to the shared module (docs 25 §5).
 */
@Composable
fun App(player: DesktopPlayer) {
    val ui by player.ui.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YtmTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        searchError = null
        scope.launch {
            val found = runCatching { decodeWire<YtmSearchResults>(NativeEngine.searchYtMusic(q)) }
            searching = false
            found.onSuccess { results = it.tracks }
                .onFailure { searchError = it.message ?: "Search failed" }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search songs on YouTube Music") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = { search() }, enabled = !searching) { Text("Search") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                searchError != null -> Text(searchError.orEmpty(), Modifier.align(Alignment.Center))
                results.isEmpty() -> Text(
                    "Nothing here yet. Search for a song, an artist or an album.",
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(results, key = { i, t -> "$i-${t.videoId}" }) { index, track ->
                        TrackRow(
                            track = track,
                            current = ui.current?.videoId == track.videoId && ui.queue == results,
                            onClick = { player.playAll(results, index) },
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        NowPlayingBar(ui, player)
    }
}

@Composable
private fun TrackRow(track: YtmTrack, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) Accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatMs(track.durationSec * 1000L), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NowPlayingBar(ui: DesktopPlayer.Ui, player: DesktopPlayer) {
    // While dragging, the slider shows the finger, not the engine; the seek goes on release.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = ui.durationMs ?: ((ui.current?.durationSec ?: 0) * 1000L)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    ui.current?.title ?: "Not playing",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ui.error ?: ui.current?.artists?.joinToString(", ") { it.name }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = player::previous, enabled = ui.current != null) { Text("⏮") }
                Button(onClick = player::togglePause, enabled = ui.current != null && !ui.loading) {
                    Text(if (ui.playing) "⏸" else "▶")
                }
                TextButton(onClick = player::next, enabled = ui.current != null) { Text("⏭") }
            }
            Spacer(Modifier.width(24.dp))
            Text("Vol", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = ui.volume,
                onValueChange = player::setVolume,
                modifier = Modifier.width(140.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatMs(if (dragging) (dragValue * duration).toLong() else ui.positionMs), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = when {
                    dragging -> dragValue
                    duration > 0 -> (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                    else -> 0f
                },
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    if (duration > 0) player.seek((dragValue * duration).toLong())
                    dragging = false
                },
                enabled = duration > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatMs(duration), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
