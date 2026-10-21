package com.varuna.rustify.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.metrics.ListenerType
import com.varuna.rustify.metrics.ListeningTracker
import com.varuna.rustify.metrics.Recap
import com.varuna.rustify.metrics.RecapBuilder
import com.varuna.rustify.metrics.TopRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Your year" (4.7): the listening history of one year as cards you swipe through — minutes, top
 * artist, top song, when you listen — each shareable as an image. See [RecapBuilder].
 */
@Composable
fun RecapDialog(year: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var recap by remember(year) { mutableStateOf<Recap?>(null) }
    var loaded by remember(year) { mutableStateOf(false) }
    LaunchedEffect(year) {
        val zone = ZoneId.systemDefault()
        val from = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        recap = runCatching { RecapBuilder.build(year, ListeningTracker.stats(context, from, to)) }.getOrNull()
        loaded = true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
            val r = recap
            when {
                !loaded -> CircularProgressIndicator(color = Color(0xFF1DB954), modifier = Modifier.align(Alignment.Center))
                r == null -> Text(
                    stringResource(R.string.recap_empty, year), color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center
                )
                else -> RecapPager(r)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 8.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_close), tint = Color.White)
            }
        }
    }
}

private val gradients = listOf(
    listOf(Color(0xFF1DB954), Color(0xFF0B3D20)),
    listOf(Color(0xFF7B2FF7), Color(0xFF1B0A3C)),
    listOf(Color(0xFFFF6B35), Color(0xFF3D1205)),
    listOf(Color(0xFF00B4D8), Color(0xFF032B3A)),
    listOf(Color(0xFFE63946), Color(0xFF3A0A0E)),
)

@Composable
private fun RecapPager(r: Recap) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val numbers = NumberFormat.getIntegerInstance()
    val months = DateFormatSymbols.getInstance().months
    val weekdays = DateFormatSymbols.getInstance().weekdays // Sunday = 1
    val pages = buildList<@Composable () -> Unit> {
        add { BigNumberCard(stringResource(R.string.recap_minutes_title, r.year), numbers.format(r.minutes), pluralStringResource(R.plurals.recap_minutes_sub, r.plays, numbers.format(r.plays), r.distinctArtists)) }
        r.topArtist?.let { a -> add { TopCard(stringResource(R.string.recap_top_artist), a, pluralStringResource(R.plurals.recap_plays, a.plays, a.plays), r.topArtists) } }
        r.topTrack?.let { t -> add { TopCard(stringResource(R.string.recap_top_track), t, pluralStringResource(R.plurals.recap_plays, t.plays, t.plays), r.topTracks) } }
        add {
            val type = when (r.listener) {
                ListenerType.NIGHT_OWL -> R.string.recap_type_night
                ListenerType.EARLY_BIRD -> R.string.recap_type_early
                ListenerType.DAYTIME -> R.string.recap_type_day
                ListenerType.EVENING -> R.string.recap_type_evening
                ListenerType.NONE -> R.string.recap_type_day
            }
            val month = r.peakMonth?.let { months[it].replaceFirstChar { c -> c.uppercase() } } ?: "—"
            // DateFormatSymbols counts Sunday as 1; the core counts Monday as 0.
            val day = r.peakWeekday?.let { weekdays[(it + 1) % 7 + 1].replaceFirstChar { c -> c.uppercase() } } ?: "—"
            BigNumberCard(
                stringResource(R.string.recap_when_title), stringResource(type),
                pluralStringResource(R.plurals.recap_when_sub, r.longestStreakDays, month, day, r.longestStreakDays)
            )
        }
    }
    val pagerState = rememberPagerState { pages.size }
    // One layer per visible page is enough: what is shared is the page on screen.
    val layer = rememberGraphicsLayer()
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val captured = if (page == pagerState.currentPage) Modifier.drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else Modifier
            Box(
                captured.fillMaxSize().background(Brush.verticalGradient(gradients[page % gradients.size])).padding(28.dp),
                contentAlignment = Alignment.Center
            ) { pages[page]() }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size) { i ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == pagerState.currentPage) Color.White else Color.DarkGray))
                }
            }
            IconButton(onClick = {
                scope.launch {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap()
                    val uri = withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "share").apply { mkdirs() }
                        val file = File(dir, "rustify_recap_${r.year}.png")
                        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        FileProvider.getUriForFile(context, "com.varuna.rustify.fileprovider", file)
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            }) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.recap_share), tint = Color.White)
            }
        }
    }
}

@Composable
private fun BigNumberCard(title: String, big: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Text(big, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 60.sp)
        Spacer(Modifier.height(24.dp))
        Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Text("Rustify", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TopCard(title: String, top: TopRow, sub: String, rest: List<TopRow>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        if (top.imageUrl.isNotBlank()) {
            AsyncImage(
                model = top.imageUrl, contentDescription = null,
                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(top.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (top.subtitle.isNotBlank()) Text(top.subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
        Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))
        rest.drop(1).forEachIndexed { i, row ->
            Text("${i + 2}. ${row.name}", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(24.dp))
        Text("Rustify", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
