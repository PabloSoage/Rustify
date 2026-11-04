@file:OptIn(ExperimentalMaterial3Api::class)

package com.varuna.rustify.ui.screens

import com.varuna.rustify.ui.toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.ui.resources.*

/** Color for each log level. */
private fun levelColor(level: Char): Color = when (level) {
    'E', 'F' -> Color(0xFFFF5252)
    'W' -> Color(0xFFFFB74D)
    'I' -> Color(0xFF81C784)
    'D' -> Color(0xFF64B5F6)
    else -> Color.LightGray // Verbose and unknown levels
}

/**
 * In-app log viewer.
 * Observes [com.varuna.rustify.util.LogBook.entries]: monospaced, colored by level, with tag/level filters, autoscroll,
 * and actions to share, export (SAF), clear, and dump now.
 */
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platform = com.varuna.rustify.ui.LocalAppPlatform.current
    val book = com.varuna.rustify.ui.LocalAppServices.current.logs
    val logs by book.entries.collectAsState()
    val error by book.error.collectAsState()

    var tagFilter by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf<Char?>(null) } // null = all levels
    var autoScroll by remember { mutableStateOf(true) }
    var showLevelHelp by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Filter by tag (case-insensitive contains) and by level.
    val filtered = remember(logs, tagFilter, levelFilter) {
        logs.filter { e ->
            (tagFilter.isBlank() || e.tag.contains(tagFilter, ignoreCase = true)) &&
                (levelFilter == null || e.level == levelFilter)
        }
    }

    // Export to a .txt file the user picks.
    val exportedMsg = stringResource(Res.string.log_exported)
    val export = com.varuna.rustify.ui.rememberSaveTextFile("text/plain") { ok ->
        if (ok == true) platform.toast(exportedMsg) else if (ok == false) platform.toast("Error")
    }

    // Autoscroll to the end when new entries arrive.
    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(Res.string.log_viewer_title), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                actions = {
                    // Autoscroll toggle.
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            Icons.Default.VerticalAlignBottom,
                            contentDescription = stringResource(Res.string.log_autoscroll),
                            tint = if (autoScroll) Color(0xFF1DB954) else Color.Gray
                        )
                    }
                    // Dump now: shares a one-off logcat snapshot.
                    IconButton(onClick = {
                        platform.share(book.dumpNow())
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.log_dump_now), tint = Color.White)
                    }
                    // Share the current buffer.
                    IconButton(onClick = {
                        platform.share(book.exportText())
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(Res.string.log_share), tint = Color.White)
                    }
                    // Export to a .txt file.
                    IconButton(onClick = { export("rustify_log.txt") { book.exportText() } }) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(Res.string.log_export), tint = Color.White)
                    }
                    // Clear the buffer.
                    IconButton(onClick = { book.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.log_clear), tint = Color.White)
                    }
                    // Info: explain log levels
                    IconButton(onClick = { showLevelHelp = !showLevelHelp }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(Res.string.log_level_help),
                            tint = if (showLevelHelp) Color(0xFF1DB954) else Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding)) {

            // Warn if logcat capture failed to start on this ROM.
            if (error != null) {
                Text(
                    stringResource(Res.string.log_capture_error, error ?: ""),
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Tag filter.
            OutlinedTextField(
                value = tagFilter,
                onValueChange = { tagFilter = it },
                label = { Text(stringResource(Res.string.log_filter_tag)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E)
                )
            )

            // Level filter (chips).
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val levels = listOf(null, 'V', 'D', 'I', 'W', 'E')
                levels.forEach { lvl ->
                    val label = lvl?.toString() ?: stringResource(Res.string.log_level_all)
                    FilterChip(
                        selected = levelFilter == lvl,
                        onClick = { levelFilter = lvl },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF1DB954),
                            labelColor = Color.LightGray,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            // Level help legend (toggled by the info button in the top bar).
            if (showLevelHelp) {
                val legendColor = Color.LightGray
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(Res.string.logs_levels),
                        color = legendColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text(stringResource(Res.string.logs_levels_hint),
                        color = legendColor.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Entry list (monospaced, colored by level).
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
            ) {
                items(filtered) { e ->
                    Text(
                        text = e.raw,
                        color = levelColor(e.level),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
