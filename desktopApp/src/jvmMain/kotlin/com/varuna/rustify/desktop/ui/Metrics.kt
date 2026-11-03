// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/ui/Metrics.kt
package com.varuna.rustify.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.decodeCheckedWire
import com.varuna.rustify.metrics.PlayStats
import com.varuna.rustify.metrics.TopRow
import com.varuna.rustify.metrics.zoneOffsetsJson

/** The ranges the phone's Metrics screen offers, most of them. */
private enum class Range(val label: String, val days: Long?) {
    WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), YEAR("Last year", 365), ALL("All time", null),
}

/**
 * The listening history's numbers — the same ones the phone shows, computed by the same core
 * (`metrics::stats::compute`) over the plays this computer recorded (`QueuePlayer` records them).
 */
@Composable
fun MetricsSection() {
    var range by remember { mutableStateOf(Range.MONTH) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Range.entries.forEach { r ->
                FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
            }
        }
        Loaded(range, { listOf(load(range)) }) { loaded ->
            val stats = loaded.first()
            if (stats.totalPlays == 0) {
                Centered("Nothing played in this range yet.")
            } else {
                Body(stats)
            }
        }
    }
}

private suspend fun load(range: Range): PlayStats {
    val now = System.currentTimeMillis()
    val from = range.days?.let { now - it * 86_400_000L } ?: 0L
    return decodeCheckedWire(NativeEngine.playStats(from, now + 1, zoneOffsetsJson()))
}

@Composable
private fun Body(stats: PlayStats) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                Figure("${stats.totalPlays}", "plays")
                Figure("${stats.totalMs / 60_000}", "minutes")
                Figure("${stats.distinctArtists}", "artists")
                Figure("${stats.daysListened}", "days")
                Figure("${stats.currentStreakDays}", "day streak")
            }
        }
        topList("Top songs", stats.topTracks)
        topList("Top artists", stats.topArtists)
        topList("Top albums", stats.topAlbums)
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.topList(title: String, rows: List<TopRow>) {
    if (rows.isEmpty()) return
    item(key = "t-$title") {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    }
    items(rows.take(10), key = { "$title-${it.key}" }) { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.subtitle.isNotBlank()) {
                    Text(
                        row.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("${row.plays} plays", style = MaterialTheme.typography.bodySmall)
        }
    }
}
