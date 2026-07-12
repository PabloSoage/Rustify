package com.varuna.rustify.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.metrics.ListeningTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.*

private enum class MetricsRange { TODAY, WEEK, MONTH, ALL }

private data class MetricsRowData(val key: String, val name: String, val plays: Int, val ms: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedRange by remember { mutableStateOf(MetricsRange.WEEK) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var totalPlays by remember { mutableIntStateOf(0) }
    var totalMs by remember { mutableLongStateOf(0L) }
    var topTracks by remember { mutableStateOf<List<MetricsRowData>>(emptyList()) }
    var topArtists by remember { mutableStateOf<List<MetricsRowData>>(emptyList()) }
    var topAlbums by remember { mutableStateOf<List<MetricsRowData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun load() {
        scope.launch {
            isLoading = true
            val (from, to) = rangeToFrom(selectedRange)
            withContext(Dispatchers.IO) {
                val events = ListeningTracker.loadEvents(context).filter { e ->
                    val ts = e.optLong("startedAt")
                    e.optBoolean("counted") && ts >= from && ts < to
                }
                totalPlays = events.size
                totalMs = events.sumOf { it.optLong("listenedMs") }
                topTracks = aggregateBy(events, "trackId", "trackName")
                topArtists = aggregateByArtist(events)
                topAlbums = aggregateBy(events, "albumId", "albumName").filter { it.key.isNotEmpty() }
            }
            isLoading = false
        }
    }

    LaunchedEffect(selectedRange) { load() }

    val tabs = listOf(
        stringResource(R.string.metrics_top_tracks),
        stringResource(R.string.metrics_top_artists),
        stringResource(R.string.metrics_top_albums)
    )
    val ranges = listOf(
        MetricsRange.TODAY to stringResource(R.string.metrics_range_today),
        MetricsRange.WEEK to stringResource(R.string.metrics_range_week),
        MetricsRange.MONTH to stringResource(R.string.metrics_range_month),
        MetricsRange.ALL to stringResource(R.string.metrics_range_all)
    )

    val exportMsg = stringResource(R.string.metrics_export_done)
    val exportEmptyMsg = stringResource(R.string.metrics_export_empty)
    val importDoneTemplate = stringResource(R.string.metrics_import_done)
    val importErrorTemplate = stringResource(R.string.metrics_import_error)

    // E70 — Export metrics to a user-chosen file as Streaming History (Spotify/stats.fm compatible).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val events = ListeningTracker.loadEvents(context)
                    if (events.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, exportEmptyMsg, Toast.LENGTH_SHORT).show()
                        }
                        return@withContext
                    }
                    val bytes = ListeningTracker.exportSpotifyHistoryBytes(context)
                    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, exportMsg, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, importErrorTemplate.format(e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // E70 — Import listening history JSON (Spotify StreamingHistoryN.json, Rustify metrics.json
    // or Rustify container { events: [...] }).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val n = context.contentResolver.openInputStream(uri)?.use {
                        ListeningTracker.importHistory(context, it)
                    } ?: 0
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, importDoneTemplate.format(n), Toast.LENGTH_SHORT).show()
                    }
                    load()
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, importErrorTemplate.format(e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(totalPlays.toString(), color = Color(0xFF1DB954), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.metrics_total_plays), color = Color.Gray, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text((totalMs / 60000).toString(), color = Color(0xFF1DB954), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.metrics_total_minutes), color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ranges.forEach { (range, label) ->
                    FilterChip(selected = selectedRange == range, onClick = { selectedRange = range },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = Color(0xFF2A2A2A), selectedContainerColor = Color(0xFF1DB954), labelColor = Color.White, selectedLabelColor = Color.Black),
                        modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFF121212), contentColor = Color(0xFF1DB954)) {
                tabs.forEachIndexed { i, t -> Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(t, fontSize = 13.sp) }) }
            }
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF1DB954)) }
            } else {
                val data = when (selectedTab) { 0 -> topTracks; 1 -> topArtists; 2 -> topAlbums; else -> emptyList() }
                if (data.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.metrics_empty), color = Color.Gray) }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(data) { index, row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text((index + 1).toString().padStart(2, '0'), color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(row.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                                    Text("${row.plays} plays \u00B7 ${row.ms / 60000} min", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun rangeToFrom(range: MetricsRange): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when (range) {
        MetricsRange.TODAY -> cal.timeInMillis to now + 1
        MetricsRange.WEEK -> { val ts = cal.timeInMillis; cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek); cal.timeInMillis to ts + 86400000 }
        MetricsRange.MONTH -> { val ts = cal.timeInMillis; cal.set(Calendar.DAY_OF_MONTH, 1); cal.timeInMillis to ts + 86400000 }
        MetricsRange.ALL -> 0L to now + 1
    }
}

private fun aggregateBy(events: List<JSONObject>, keyField: String, nameField: String): List<MetricsRowData> {
    return events.groupBy { it.optString(keyField) to it.optString(nameField) }
        .map { (k, list) -> MetricsRowData(k.first, k.second, list.size, list.sumOf { it.optLong("listenedMs") }) }
        .sortedByDescending { it.plays }.take(50)
}

private fun aggregateByArtist(events: List<JSONObject>): List<MetricsRowData> {
    val map = mutableMapOf<Pair<String, String>, Pair<Int, Long>>()
    events.forEach { e ->
        val ids = e.optJSONArray("artistIds") ?: return@forEach
        val names = e.optJSONArray("artistNames") ?: return@forEach
        for (i in 0 until minOf(ids.length(), names.length())) {
            val key = ids.optString(i) to names.optString(i)
            val (plays, ms) = map.getOrDefault(key, 0 to 0L)
            map[key] = (plays + 1) to (ms + e.optLong("listenedMs"))
        }
    }
    return map.map { (k, v) -> MetricsRowData(k.first, k.second, v.first, v.second) }
        .sortedByDescending { it.plays }.take(50)
}
