@file:OptIn(ExperimentalMaterial3Api::class)

package com.varuna.rustify.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.util.LogCapture

/** Color por nivel de log. */
private fun levelColor(level: Char): Color = when (level) {
    'E', 'F' -> Color(0xFFFF5252)
    'W' -> Color(0xFFFFB74D)
    'I' -> Color(0xFF81C784)
    'D' -> Color(0xFF64B5F6)
    else -> Color.LightGray // V y desconocidos
}

/**
 * F1.B — Visor de logs in-app (§3.B.2 / §4.B.2 del doc 40).
 * Observa [LogCapture.flow], monoespaciado, color por nivel, filtro por tag/nivel, autoscroll,
 * y acciones: Compartir, Exportar (SAF), Limpiar, Volcar ahora.
 */
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logs by LogCapture.flow.collectAsState()
    val error by LogCapture.error.collectAsState()

    var tagFilter by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf<Char?>(null) } // null = todos
    var autoScroll by remember { mutableStateOf(true) }
    var showLevelHelp by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Vista filtrada por tag (contains, case-insensitive) y por nivel.
    val filtered = remember(logs, tagFilter, levelFilter) {
        logs.filter { e ->
            (tagFilter.isBlank() || e.tag.contains(tagFilter, ignoreCase = true)) &&
                (levelFilter == null || e.level == levelFilter)
        }
    }

    // Exportar a .txt vía SAF (mismo patrón que SettingsScreen CreateDocument).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(LogCapture.exportText().toByteArray())
                }
                Toast.makeText(context, R.string.log_exported, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Autoscroll al final cuando llegan entradas nuevas.
    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_viewer_title), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                actions = {
                    // Autoscroll toggle
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            Icons.Default.VerticalAlignBottom,
                            contentDescription = stringResource(R.string.log_autoscroll),
                            tint = if (autoScroll) Color(0xFF1DB954) else Color.Gray
                        )
                    }
                    // Volcar ahora (snapshot -d): reemplaza el buffer con el dump puntual.
                    IconButton(onClick = {
                        val dump = LogCapture.dumpNow()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, dump)
                        }
                        val chooser = Intent.createChooser(send, null)
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(chooser)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.log_dump_now), tint = Color.White)
                    }
                    // Compartir buffer actual
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, LogCapture.exportText())
                        }
                        val chooser = Intent.createChooser(send, null)
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(chooser)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.log_share), tint = Color.White)
                    }
                    // Exportar a fichero .txt
                    IconButton(onClick = { exportLauncher.launch("rustify_log.txt") }) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.log_export), tint = Color.White)
                    }
                    // Limpiar buffer
                    IconButton(onClick = { LogCapture.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.log_clear), tint = Color.White)
                    }
                    // Info: explain log levels
                    IconButton(onClick = { showLevelHelp = !showLevelHelp }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.log_level_help),
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

            // Aviso si logcat no arrancó en esta ROM.
            if (error != null) {
                Text(
                    stringResource(R.string.log_capture_error, error ?: ""),
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Filtro por tag.
            OutlinedTextField(
                value = tagFilter,
                onValueChange = { tagFilter = it },
                label = { Text(stringResource(R.string.log_filter_tag)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E)
                )
            )

            // Filtro por nivel (chips).
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val levels = listOf<Char?>(null, 'V', 'D', 'I', 'W', 'E')
                levels.forEach { lvl ->
                    val label = lvl?.toString() ?: stringResource(R.string.log_level_all)
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
                    Text("V = Verbose  ·  D = Debug  ·  I = Info  ·  W = Warn  ·  E = Error",
                        color = legendColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("Verbose → mayor detalle, menor severidad. Error → crítico, menor detalle.",
                        color = legendColor.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Lista de entradas (monoespaciada, color por nivel).
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
