package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.MatchStore
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.UserAlternatives
import com.varuna.rustify.player.AudioPlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Editor de matches YouTube — sustituye al editor de texto crudo por una lista legible:
 *  - nombre de la canción + artistas + match actual (id + "usuario/auto"),
 *  - reproducir el match actual, editar (abre el buscador de alternativas), o borrar,
 *  - añadir un match manualmente (buscas la canción y luego su match).
 *
 * Reutiliza [YouTubeMappingDialog] (el buscador de alternativas de TrackScreen) para editar/añadir.
 * Todos los cambios pasan por [MatchStore] (que reescribe el JSON **y recarga el mapa en Rust**).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchEditorScreen(
    spotifyRepo: SpotifyRepository,
    audioPlayerService: AudioPlayerService,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val green = Color(0xFF1DB954)

    var mappings by remember { mutableStateOf(MatchStore.readAll(context).toList()) }
    val names = remember { mutableStateMapOf<String, FullTrack?>() }
    var editTrack by remember { mutableStateOf<FullTrack?>(null) }  // abre el diálogo de alternativas
    var showAdd by remember { mutableStateOf(false) }

    fun refresh() { mappings = MatchStore.readAll(context).toList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor de matches", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showAdd = true },
                    colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Añadir match manualmente", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text("${mappings.size} matches", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }

            items(mappings, key = { it.first }) { (tid, ytId) ->
                LaunchedEffect(tid) {
                    if (!names.containsKey(tid)) {
                        names[tid] = runCatching { spotifyRepo.getTrack(tid) }.getOrNull()
                    }
                }
                val ft = names[tid]
                val isUser = UserAlternatives.isUserSet(context, tid)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = ft?.album?.images?.firstOrNull()?.url,
                        contentDescription = null,
                        modifier = Modifier.size(46.dp).background(Color(0xFF222222), RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ft?.name ?: tid, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            ft?.artists?.joinToString(", ") { it.name } ?: "…",
                            color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "YT: $ytId · " + if (isUser) "elegido" else "auto",
                            color = if (isUser) green else Color(0xFF888888), fontSize = 11.sp, maxLines = 1
                        )
                    }
                    // Reproducir el match actual
                    IconButton(onClick = { runCatching { audioPlayerService.playPreview(tid, ytId) } }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = green)
                    }
                    // Editar (buscar/cambiar alternativa)
                    IconButton(onClick = {
                        editTrack = ft ?: FullTrack(
                            id = tid, name = tid, externalUri = "", explicit = false,
                            durationMs = 0, isrc = "", artists = emptyList(), album = null
                        )
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = Color.White)
                    }
                    // Borrar
                    IconButton(onClick = { MatchStore.remove(context, tid); refresh() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = Color(0xFFCC3333))
                    }
                }
            }

            if (mappings.isEmpty()) {
                item {
                    Text(
                        "No hay matches guardados. Usa “Añadir” o el botón de alternativas de una canción.",
                        color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
        }
    }

    // Diálogo de alternativas (editar el match de una canción concreta).
    editTrack?.let { t ->
        YouTubeMappingDialog(
            track = t,
            audioPlayerService = audioPlayerService,
            onDismiss = { editTrack = null },
            onMappingSelected = { yt ->
                t.id?.let { MatchStore.put(context, it, yt) }
                editTrack = null
                refresh()
            }
        )
    }

    // Añadir manualmente: primero busca la canción, luego se abre el buscador de match.
    if (showAdd) {
        SongPickerDialog(
            spotifyRepo = spotifyRepo,
            onDismiss = { showAdd = false },
            onPick = { track -> showAdd = false; editTrack = track }
        )
    }
}

/** Buscador de canciones de Spotify para el flujo "añadir match manualmente". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongPickerDialog(
    spotifyRepo: SpotifyRepository,
    onDismiss: () -> Unit,
    onPick: (FullTrack) -> Unit,
) {
    val green = Color(0xFF1DB954)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    fun search() {
        if (query.isBlank()) return
        searching = true
        scope.launch(Dispatchers.IO) {
            val r = runCatching { spotifyRepo.searchTracks(query, limit = 20).items }.getOrDefault(emptyList())
            results = r; searching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Buscar canción", color = Color.White) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nombre / artista") },
                    singleLine = true
                )
                Button(
                    onClick = { search() },
                    colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.Black),
                    modifier = Modifier.padding(top = 8.dp).align(Alignment.End)
                ) { Text("Buscar") }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(results) { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = t.album?.images?.firstOrNull()?.url,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp).background(Color(0xFF222222), RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.artists.joinToString(", ") { it.name }, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (searching) item { Text("Buscando…", color = Color.Gray, modifier = Modifier.padding(8.dp)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar", color = green) } }
    )
}
