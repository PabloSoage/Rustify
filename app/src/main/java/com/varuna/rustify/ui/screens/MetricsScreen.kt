package com.varuna.rustify.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.metrics.ListeningTracker
import com.varuna.rustify.metrics.PlayEvent
import com.varuna.rustify.metrics.PlayImportReport
import com.varuna.rustify.metrics.PlayStats
import com.varuna.rustify.metrics.TopRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

private enum class MetricsRange { TODAY, WEEK, MONTH, YEAR, ALL }

/** Computed by the core (`metrics::stats`) since 4.0; see [ListeningTracker.stats]. */
private typealias Stats = PlayStats

private val GREEN = Color(0xFF1DB954)
private val CARD = Color(0xFF1E1E1E)
private val BG = Color(0xFF121212)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedRange by remember { mutableStateOf(MetricsRange.WEEK) }
    var selectedTopTab by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf(Stats()) }
    var isLoading by remember { mutableStateOf(true) }

    fun load() {
        scope.launch {
            isLoading = true
            val s = withContext(Dispatchers.IO) { computeStats(context, selectedRange) }
            stats = s
            isLoading = false
        }
    }
    LaunchedEffect(selectedRange) { load() }

    val ranges = listOf(
        MetricsRange.TODAY to stringResource(R.string.metrics_range_today),
        MetricsRange.WEEK to stringResource(R.string.metrics_range_week),
        MetricsRange.MONTH to stringResource(R.string.metrics_range_month),
        MetricsRange.YEAR to stringResource(R.string.metrics_range_year),
        MetricsRange.ALL to stringResource(R.string.metrics_range_all)
    )

    val exportMsg = stringResource(R.string.metrics_export_done)
    val exportEmptyMsg = stringResource(R.string.metrics_export_empty)
    val importDoneTemplate = stringResource(R.string.metrics_import_done)
    val importErrorTemplate = stringResource(R.string.metrics_import_error)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val events = ListeningTracker.loadEvents(context)
                    if (events.isEmpty()) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, exportEmptyMsg, Toast.LENGTH_SHORT).show() }
                        return@withContext
                    }
                    val bytes = ListeningTracker.exportSpotifyHistoryBytes(context)
                    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, exportMsg, Toast.LENGTH_SHORT).show() }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) { Toast.makeText(context, importErrorTemplate.format(e.message ?: ""), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val n = context.contentResolver.openInputStream(uri)?.use {
                        ListeningTracker.importHistory(context, it)
                    } ?: PlayImportReport()
                    withContext(Dispatchers.Main) { Toast.makeText(context, importDoneTemplate.format(n.added), Toast.LENGTH_SHORT).show() }
                    load()
                }.onFailure { e ->
                    withContext(Dispatchers.Main) { Toast.makeText(context, importErrorTemplate.format(e.message ?: ""), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.metrics_title), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                actions = {
                    IconButton(onClick = { exportLauncher.launch("rustify_metrics_export.json") }) {
                        Icon(Icons.Default.GetApp, contentDescription = stringResource(R.string.metrics_export), tint = Color.White)
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.metrics_import), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BG)
            )
        },
        containerColor = BG
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Range chips
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ranges.forEach { (range, label) ->
                        FilterChip(
                            selected = selectedRange == range,
                            onClick = { selectedRange = range },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFF2A2A2A), selectedContainerColor = GREEN,
                                labelColor = Color.White, selectedLabelColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (isLoading) {
                item { Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GREEN) } }
                return@LazyColumn
            }

            if (stats.totalPlays == 0 && stats.totalMs == 0L) {
                item { Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.metrics_empty), color = Color.Gray) } }
                return@LazyColumn
            }

            // Summary stat grid
            item {
                val hours = stats.totalMs / 3_600_000L
                val minutes = stats.totalMs / 60_000L
                StatGrid(
                    listOf(
                        Triple(stats.totalPlays.toString(), stringResource(R.string.metrics_total_plays), GREEN),
                        Triple(minutes.toString(), stringResource(R.string.metrics_total_minutes), GREEN),
                        Triple(hours.toString(), stringResource(R.string.metrics_stat_hours), Color(0xFF4FC3F7)),
                        Triple(stats.distinctTracks.toString(), stringResource(R.string.metrics_stat_tracks), Color(0xFFBA68C8)),
                        Triple(stats.distinctArtists.toString(), stringResource(R.string.metrics_stat_artists), Color(0xFFFFB74D)),
                        Triple(stats.distinctAlbums.toString(), stringResource(R.string.metrics_stat_albums), Color(0xFF4DB6AC)),
                        Triple(stats.daysListened.toString(), stringResource(R.string.metrics_stat_days), Color(0xFFE57373)),
                        Triple("${stats.avgPerDayMin}m", stringResource(R.string.metrics_stat_avg_day), Color(0xFF90A4AE)),
                        Triple("${stats.completionRate}%", stringResource(R.string.metrics_stat_completion), Color(0xFFAED581))
                    )
                )
                Spacer(Modifier.height(20.dp))
            }

            // Listening clocks (radial 24h)
            item {
                SectionTitle(stringResource(R.string.metrics_clocks))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadialHourClock(
                        values = stats.playsByHour.map { it.toFloat() },
                        label = stringResource(R.string.metrics_clock_plays),
                        color = GREEN,
                        modifier = Modifier.weight(1f)
                    )
                    RadialHourClock(
                        values = stats.msByHour.map { (it / 60000f) },
                        label = stringResource(R.string.metrics_clock_minutes),
                        color = Color(0xFF4FC3F7),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // Weekday
            item {
                SectionTitle(stringResource(R.string.metrics_by_weekday))
                val labels = listOf(
                    stringResource(R.string.dow_mon), stringResource(R.string.dow_tue), stringResource(R.string.dow_wed),
                    stringResource(R.string.dow_thu), stringResource(R.string.dow_fri), stringResource(R.string.dow_sat),
                    stringResource(R.string.dow_sun)
                )
                MiniBarChart(labels.mapIndexed { i, l -> l to stats.playsByWeekday[i].toFloat() }, GREEN)
                Spacer(Modifier.height(20.dp))
            }

            // Month
            item {
                SectionTitle(stringResource(R.string.metrics_by_month))
                val m = listOf("1","2","3","4","5","6","7","8","9","10","11","12")
                MiniBarChart(m.mapIndexed { i, l -> l to stats.playsByMonth[i].toFloat() }, Color(0xFFFFB74D))
                Spacer(Modifier.height(20.dp))
            }

            // Year
            if (stats.playsByYear.size > 1) {
                item {
                    SectionTitle(stringResource(R.string.metrics_by_year))
                    MiniBarChart(stats.playsByYear.map { it.year.toString() to it.plays.toFloat() }, Color(0xFFBA68C8))
                    Spacer(Modifier.height(20.dp))
                }
            }

            // All-time lines
            if (stats.cumulativeMin.size > 1) {
                item {
                    SectionTitle(stringResource(R.string.metrics_all_time))
                    LineChartView(stats.cumulativeMin.map { it.toFloat() }, GREEN, stringResource(R.string.metrics_cumulative_minutes))
                    Spacer(Modifier.height(12.dp))
                    LineChartView(stats.cumulativePlays.map { it.toFloat() }, Color(0xFF4FC3F7), stringResource(R.string.metrics_cumulative_plays))
                    Spacer(Modifier.height(12.dp))
                    LineChartView(stats.minutesPerDay.map { it.toFloat() }, Color(0xFFFFB74D), stringResource(R.string.metrics_minutes_per_day))
                    Spacer(Modifier.height(20.dp))
                }
            }

            // Top tabs
            item {
                val tabs = listOf(
                    stringResource(R.string.metrics_top_tracks),
                    stringResource(R.string.metrics_top_artists),
                    stringResource(R.string.metrics_top_albums)
                )
                PrimaryTabRow(selectedTabIndex = selectedTopTab, containerColor = BG, contentColor = GREEN) {
                    tabs.forEachIndexed { i, t -> Tab(selected = selectedTopTab == i, onClick = { selectedTopTab = i }, text = { Text(t, fontSize = 13.sp) }) }
                }
                Spacer(Modifier.height(8.dp))
            }
            val topData = when (selectedTopTab) { 0 -> stats.topTracks; 1 -> stats.topArtists; else -> stats.topAlbums }
            if (topData.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.metrics_empty), color = Color.Gray) } }
            } else {
                val maxPlays = topData.maxOf { it.plays }.coerceAtLeast(1)
                itemsIndexedTop(topData) { index, row -> TopRowItem(index, row, maxPlays) }
            }

            // Recent history
            if (stats.recent.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionTitle(stringResource(R.string.metrics_history))
                }
                itemsRecent(stats.recent) { e -> RecentRow(e) }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ---- small helpers so we can call itemsIndexed inside the LazyColumn scope cleanly ----
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedTop(
    data: List<TopRow>, content: @Composable (Int, TopRow) -> Unit
) {
    items(data.size) { i -> content(i, data[i]) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsRecent(
    data: List<PlayEvent>, content: @Composable (PlayEvent) -> Unit
) {
    items(data.size) { i -> content(data[i]) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun StatGrid(cells: List<Triple<String, String, Color>>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(3).forEach { rowCells ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowCells.forEach { (value, label, color) ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CARD),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                            Text(label, color = Color.Gray, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
                repeat(3 - rowCells.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Radial 24h clock: one bar per hour, length proportional to the value; clock face in the center. */
@Composable
private fun RadialHourClock(values: List<Float>, label: String, color: Color, modifier: Modifier = Modifier) {
    val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val inner = size.minDimension * 0.20f
                    val outer = size.minDimension * 0.46f
                    // guide ring
                    drawCircle(color = Color(0xFF333333), radius = outer, center = Offset(cx, cy), style = Stroke(width = 1f))
                    for (h in 0 until 24) {
                        val frac = (values.getOrElse(h) { 0f } / maxV).coerceIn(0f, 1f)
                        val len = inner + (outer - inner) * frac
                        val ang = Math.toRadians((h / 24.0) * 360.0 - 90.0)
                        val sx = cx + inner * cos(ang).toFloat(); val sy = cy + inner * sin(ang).toFloat()
                        val ex = cx + len * cos(ang).toFloat(); val ey = cy + len * sin(ang).toFloat()
                        drawLine(
                            color = color.copy(alpha = 0.35f + 0.65f * frac),
                            start = Offset(sx, sy), end = Offset(ex, ey),
                            strokeWidth = 5f, cap = StrokeCap.Round
                        )
                    }
                }
                Text("🕛", fontSize = 20.sp)
                Text("0", color = Color.Gray, fontSize = 8.sp, modifier = Modifier.align(Alignment.TopCenter))
                Text("6", color = Color.Gray, fontSize = 8.sp, modifier = Modifier.align(Alignment.CenterEnd))
                Text("12", color = Color.Gray, fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomCenter))
                Text("18", color = Color.Gray, fontSize = 8.sp, modifier = Modifier.align(Alignment.CenterStart))
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.Gray, fontSize = 11.sp)
        }
    }
}

/**
 * Simple vertical bars (no Canvas): one per entry, height proportional to the maximum.
 * The bar lives in a weight(1f) area and grows via fillMaxHeight(frac); the number (top) and the
 * label (bottom) are fixed, so a tall column never pushes the label out of the card or off the bottom.
 */
@Composable
private fun MiniBarChart(values: List<Pair<String, Float>>, color: Color) {
    val maxV = (values.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(0.0001f)
    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(160.dp).padding(12.dp), verticalAlignment = Alignment.Bottom) {
            values.forEach { (lbl, v) ->
                val frac = (v / maxV).coerceIn(0f, 1f)
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Number on top (fixed). Empty string when 0 to keep the baseline aligned.
                    Text(if (v > 0f) v.toInt().toString() else "", color = Color.Gray, fontSize = 8.sp, maxLines = 1)
                    // Flexible area: the bar scales to the available height, so it never overflows.
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.55f)
                                .fillMaxHeight(frac.coerceAtLeast(0.015f))
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(color.copy(alpha = 0.35f + 0.65f * frac))
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    // Label at the bottom (fixed) — always visible.
                    Text(lbl, color = Color.Gray, fontSize = 8.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Line for a time series (full history), normalized to the Canvas height. */
@Composable
private fun LineChartView(points: List<Float>, color: Color, label: String) {
    val maxV = (points.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                if (points.size < 2) return@Canvas
                val w = size.width; val h = size.height
                val dx = w / (points.size - 1)
                val line = Path()
                val fill = Path()
                points.forEachIndexed { i, v ->
                    val x = i * dx
                    val y = h - (v / maxV).coerceIn(0f, 1f) * h
                    if (i == 0) { line.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y) }
                    else { line.lineTo(x, y); fill.lineTo(x, y) }
                }
                fill.lineTo(w, h); fill.close()
                drawPath(fill, color = color.copy(alpha = 0.15f))
                drawPath(line, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun TopRowItem(index: Int, row: TopRow, maxPlays: Int) {
    val rankColor = when (index) { 0 -> Color(0xFFFFD700); 1 -> Color(0xFFC0C0C0); 2 -> Color(0xFFCD7F32); else -> Color.Gray }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((index + 1).toString().padStart(2, '0'), color = rankColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
        Cover(row.imageUrl, row.name, circle = false)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(row.name.ifBlank { "—" }, color = Color.White, fontSize = 14.sp, maxLines = 1, fontWeight = FontWeight.Medium)
            if (row.subtitle.isNotBlank()) Text(row.subtitle, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { row.plays.toFloat() / maxPlays },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = GREEN, trackColor = Color(0xFF2A2A2A)
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.plays.toString(), color = GREEN, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("${row.ms / 60000}m", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Cover(url: String, name: String, circle: Boolean) {
    val shape = if (circle) CircleShape else RoundedCornerShape(4.dp)
    Box(Modifier.size(40.dp).clip(shape).background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.trim().take(1).uppercase().ifBlank { "?" }, color = Color.Gray, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RecentRow(e: PlayEvent) {
    val name = e.trackName
    val artist = e.artistNames.firstOrNull().orEmpty()
    val img = e.imageUrl
    val ts = e.startedAt
    val rel = if (ts > 0) android.text.format.DateUtils.getRelativeTimeSpanString(ts).toString() else ""
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(img, name, circle = false)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(name.ifBlank { "—" }, color = Color.White, fontSize = 13.sp, maxLines = 1)
            Text(artist, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
        }
        Text(rel, color = Color.Gray, fontSize = 10.sp)
    }
}

// ---------------------------------------------------------------------------
// Aggregation
// ---------------------------------------------------------------------------

private fun rangeToFrom(range: MetricsRange): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val to = now + 1
    val from = when (range) {
        MetricsRange.TODAY -> startOfToday.timeInMillis
        MetricsRange.WEEK -> (startOfToday.clone() as Calendar).apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.timeInMillis
        MetricsRange.MONTH -> (startOfToday.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        MetricsRange.YEAR -> (startOfToday.clone() as Calendar).apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        MetricsRange.ALL -> 0L
    }
    return from to to
}

/**
 * The core computes everything (`core_engine/src/metrics/stats.rs`): with no ceiling on the history
 * any more, aggregating a few hundred thousand events here would put them all on the UI's heap.
 */
private suspend fun computeStats(context: Context, range: MetricsRange): Stats {
    val (from, to) = rangeToFrom(range)
    return runCatching { ListeningTracker.stats(context, from, to) }
        .onFailure { android.util.Log.w("MetricsScreen", "stats failed: ${it.message}") }
        .getOrDefault(Stats())
}
