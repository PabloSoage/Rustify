package com.varuna.rustify.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.varuna.rustify.dj.DjAutoController
import com.varuna.rustify.dj.DjSpeech
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.dj.DjEngine
import com.varuna.rustify.dj.DjMode
import com.varuna.rustify.dj.DjSettings
import kotlinx.coroutines.launch

/**
 * E90 — Pantalla del DJ IA. Campo de texto para la petición en lenguaje natural, botón "Iniciar DJ"
 * (automix), muestra la frase de intro del DJ y encola/reproduce el resultado.
 *
 * No modifica AudioPlayerService: usa las lambdas [onPlayTracks] (loadPlaylist) / [onEnqueueTracks]
 * (enqueueAll) que el llamador conecta a los métodos públicos del servicio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjScreen(
    spotifyRepo: SpotifyRepository,
    nowPlaying: FullTrack?,
    queue: List<FullTrack>,
    onPlayTracks: (List<FullTrack>) -> Unit,
    onEnqueueTracks: (List<FullTrack>) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val green = Color(0xFF1DB954)

    var request by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var intro by remember { mutableStateOf<String?>(null) }
    var resultTracks by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val mode = remember { DjSettings.mode(context) }
    val modeLabel = when (mode) {
        DjMode.HEURISTIC -> stringResource(R.string.dj_mode_heuristic)
        DjMode.API -> stringResource(R.string.dj_mode_api)
        DjMode.LOCAL -> stringResource(R.string.dj_mode_local)
    }
    val emptyResultMsg = stringResource(R.string.dj_no_results)

    fun startDj() {
        if (isLoading) return
        isLoading = true
        intro = null
        errorMsg = null
        resultTracks = emptyList()
        scope.launch {
            runCatching {
                val engine = DjEngine(context, spotifyRepo)
                engine.run(request = request, nowPlaying = nowPlaying, queue = queue)
            }.onSuccess { res ->
                intro = res.intro
                resultTracks = res.tracks
                if (res.tracks.isNotEmpty()) {
                    onPlayTracks(res.tracks)
                    com.varuna.rustify.dj.DjVoice.speak(context, res.intro)  // habla la intro (respeta el toggle de voz)
                } else {
                    errorMsg = emptyResultMsg
                }
            }.onFailure { e ->
                errorMsg = e.message ?: emptyResultMsg
            }
            isLoading = false
        }
    }

    // ── DJ autónomo (Livi) + voz ──────────────────────────────────────────────────────
    val autoState by DjAutoController.state.collectAsState()
    val speech = remember { DjSpeech(context) }
    val micUnavailableMsg = stringResource(R.string.dj_mic_unavailable)
    val voiceLang = DjSettings.voiceLanguage(context)

    // Favoritas del usuario (liked songs); si aún no están cacheadas, se piden a la API.
    val favoritesProvider: suspend () -> List<FullTrack> = {
        spotifyRepo.likedTracks.toList().ifEmpty {
            runCatching { spotifyRepo.getSavedTracks(limit = 50).items }.getOrDefault(emptyList())
        }
    }

    fun listen() {
        speech.start(
            languageTag = voiceLang,
            onResult = { text ->
                request = text
                val lower = text.lowercase()
                val wantsNext = listOf("siguiente", "cambia", "next", "change", "otro").any { lower.contains(it) }
                if (DjAutoController.isActive && wantsNext) DjAutoController.next(context) else startDj()
            },
            onError = {
                android.widget.Toast.makeText(context, micUnavailableMsg, android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) listen() }

    fun onMicClick() {
        if (!speech.isAvailable()) {
            android.widget.Toast.makeText(context, micUnavailableMsg, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) listen() else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) { onDispose { speech.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dj_title), color = Color.White, fontWeight = FontWeight.Bold) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.dj_subtitle), color = Color.Gray, fontSize = 14.sp)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dj_current_mode, modeLabel), color = green, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.dj_change_in_settings),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onOpenSettings() }
                )
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = request,
                onValueChange = { request = it },
                label = { Text(stringResource(R.string.dj_request_hint)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { onMicClick() }) {
                        Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.dj_mic), tint = green)
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E),
                    focusedLabelColor = green,
                    focusedIndicatorColor = green
                )
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { startDj() },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = green),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dj_start), color = Color.Black, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        if (resultTracks.isNotEmpty()) onEnqueueTracks(resultTracks)
                    },
                    enabled = !isLoading && resultTracks.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dj_enqueue), color = Color.White)
                }
            }

            Spacer(Modifier.height(12.dp))
            // Autonomous "DJ Livi"-style: press once, it announces a mood (voice) and queues a block.
            if (autoState == null) {
                OutlinedButton(
                    onClick = { DjAutoController.start(context, spotifyRepo, favoritesProvider) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = green)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dj_auto_start), color = Color.White)
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF102010)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (autoState!!.preparing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = green,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.dj_auto_preparing),
                                    color = green, fontWeight = FontWeight.Bold, fontSize = 15.sp
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.dj_auto_now, autoState!!.moodLabel),
                                color = green, fontWeight = FontWeight.Bold, fontSize = 15.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { DjAutoController.next(context) },
                                enabled = !autoState!!.preparing,
                                colors = ButtonDefaults.buttonColors(containerColor = green),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.Black)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.dj_auto_next), color = Color.Black)
                            }
                            OutlinedButton(
                                onClick = { DjAutoController.stop() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.dj_auto_stop), color = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = green)
                }
            }

            intro?.let { line ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.dj_intro_label), color = green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(line, color = Color.White, fontSize = 15.sp)
                    }
                }
            }

            errorMsg?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(msg, color = Color(0xFFE57373), fontSize = 14.sp)
            }

            if (resultTracks.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.dj_queue_built, resultTracks.size),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                resultTracks.take(50).forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (i + 1).toString().padStart(2, '0'),
                            color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(28.dp)
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(t.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                            Text(t.artists.joinToString(", ") { it.name }, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
