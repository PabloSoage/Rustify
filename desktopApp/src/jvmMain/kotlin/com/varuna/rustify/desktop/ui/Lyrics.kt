// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/ui/Lyrics.kt
package com.varuna.rustify.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsChain
import com.varuna.rustify.bridge.LyricsResult
import com.varuna.rustify.bridge.indexAt
import com.varuna.rustify.platform.AppDirs
import com.varuna.rustify.platform.FileStore
import kotlinx.coroutines.launch
import java.io.File

/**
 * The phone's lyrics chain with the desktop's storage. A file store rather than Preferences: the
 * per-song choices run to 500 entries, past the 8 KiB Preferences holds in one value.
 */
internal object DesktopLyrics {
    val chain by lazy { LyricsChain(FileStore(File(AppDirs.data, "lyrics"))) }
}

/**
 * The lyrics under the controls, as the tablet has them: the line being sung in white and bold, the
 * rest grey, kept in the middle as the song goes. Above them one dot per provider; a click on a dot,
 * or a drag sideways, asks that one, and the one that has the song is remembered for it.
 * A click on a synced line jumps there.
 */
@Composable
internal fun LyricsPanel(track: FullTrack?, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val trackId = track?.id
    var result by remember(trackId) { mutableStateOf<LyricsResult?>(null) }
    var loading by remember(trackId) { mutableStateOf(true) }
    LaunchedEffect(trackId) {
        if (track == null || trackId == null) {
            loading = false
            return@LaunchedEffect
        }
        result = runCatching {
            DesktopLyrics.chain.getLyrics(trackId, track.artists.firstOrNull()?.name.orEmpty(), track.name, track.durationMs / 1000)
        }.getOrNull()
        loading = false
    }

    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(vertical = 12.dp)) {
        if (track != null && trackId != null) {
            ProviderDots(track, trackId, result) { result = it }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val r = result
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.Accent)
                r == null -> Text(S.lyricsNotFound, Modifier.align(Alignment.Center), color = Palette.Muted)
                r.synced.isNotEmpty() -> SyncedLines(r, positionMs, onSeek)
                else -> Text(
                    r.plain.orEmpty(),
                    color = Color.LightGray,
                    fontSize = 16.sp,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncedLines(r: LyricsResult, positionMs: Long, onSeek: (Long) -> Unit) {
    val lines = r.synced
    val current = lines.indexAt(positionMs)
    val state = rememberLazyListState()
    // Two lines of what was sung stay above the one being sung.
    LaunchedEffect(current) {
        if (current >= 0) state.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
        itemsIndexed(lines) { i, line ->
            val now = i == current
            Text(
                line.text.ifBlank { "♪" },
                color = when {
                    now -> Color.White
                    i < current -> Color.Gray
                    else -> Color.Gray.copy(alpha = 0.75f)
                },
                fontSize = if (now) 20.sp else 17.sp,
                fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { onSeek(line.timeMs) }.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ProviderDots(track: FullTrack, trackId: String, current: LyricsResult?, onChange: (LyricsResult?) -> Unit) {
    val providers = remember(trackId) { DesktopLyrics.chain.providersFor(trackId) }
    if (providers.size < 2) return
    val scope = rememberCoroutineScope()
    var asking by remember(trackId) { mutableStateOf<String?>(null) }
    var nothingFrom by remember(trackId) { mutableStateOf<String?>(null) }
    val shown = current?.providerId
    val index = providers.indexOf(shown).coerceAtLeast(0)
    fun open(target: Int) {
        val id = providers.getOrNull(Math.floorMod(target, providers.size)) ?: return
        if (id == shown || asking != null) return
        asking = id
        nothingFrom = null
        scope.launch {
            val r = DesktopLyrics.chain.fetchFrom(
                trackId, id, track.artists.firstOrNull()?.name.orEmpty(), track.name, track.durationMs / 1000,
            )
            asking = null
            if (r != null) {
                DesktopLyrics.chain.choose(trackId, id)
                onChange(r)
            } else {
                nothingFrom = id
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).pointerInput(trackId, shown) {
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onHorizontalDrag = { _, dx -> dragged += dx },
                onDragEnd = { if (dragged < -60f) open(index + 1) else if (dragged > 60f) open(index - 1) },
            )
        },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        providers.forEachIndexed { i, id ->
            Box(
                Modifier.padding(horizontal = 4.dp)
                    .size(if (i == index) 9.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (i == index) Color.White else Color.Gray.copy(alpha = 0.6f))
                    .clickable { open(i) },
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                asking != null -> S.lyricsAsking(S.lyricsProvider(asking!!))
                nothingFrom != null -> S.lyricsNothing(S.lyricsProvider(nothingFrom!!))
                else -> S.lyricsProvider(providers[index])
            },
            color = Color.Gray,
            fontSize = 12.sp,
        )
    }
}
