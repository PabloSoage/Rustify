@file:Suppress("SpellCheckingInspection")

package com.varuna.rustify.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.varuna.rustify.R
import com.varuna.rustify.util.bouncingMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsRepository
import com.varuna.rustify.bridge.LyricsResult
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.effectiveCoverUrl
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.player.AudioPlayerState
import com.varuna.rustify.ui.components.SpotifyLikeButton
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class YouTubeTrack(
    val id: String,
    val title: String,
    val author: String,
    val durationSec: Int,
    val thumbnailUrl: String
) {
    companion object {
        fun fromJson(json: JSONObject): YouTubeTrack = YouTubeTrack(
            id = json.optString("id", ""),
            title = json.optString("title", ""),
            author = json.optString("author", ""),
            durationSec = json.optInt("duration_sec", 0),
            thumbnailUrl = json.optString("thumbnail_url", "")
        )

        fun listFromJsonArray(jsonStr: String): List<YouTubeTrack> {
            val list = mutableListOf<YouTubeTrack>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return list
        }
    }
}

@Composable
fun YouTubeMappingDialog(
    track: FullTrack,
    audioPlayerService: AudioPlayerService,
    onDismiss: () -> Unit,
    onMappingSelected: (String) -> Unit
) {
    var query by remember { mutableStateOf("${track.name} ${track.artists.joinToString(" ") { it.name }}") }
    var results by remember { mutableStateOf<List<YouTubeTrack>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var playingPreviewId by remember { mutableStateOf<String?>(null) }
    
    val coroutineScope = rememberCoroutineScope()
    val playerState by audioPlayerService.state.collectAsState()

    LaunchedEffect(Unit) {
        isSearching = true
        coroutineScope.launch(Dispatchers.IO) {
            val json = NativeEngine.searchYouTubeNative(query)
            results = YouTubeTrack.listFromJsonArray(json)
            isSearching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Asignar video de YouTube") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Buscar en YouTube") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1DB954),
                        focusedLabelColor = Color(0xFF1DB954)
                    )
                )
                Button(
                    onClick = {
                        isSearching = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val json = NativeEngine.searchYouTubeNative(query)
                            results = YouTubeTrack.listFromJsonArray(json)
                            isSearching = false
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp).align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                ) {
                    Text("Buscar")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally), color = Color(0xFF1DB954))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(results) { yt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTrackId = yt.id }
                                    .background(if (selectedTrackId == yt.id) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = yt.thumbnailUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(60.dp, 45.dp).clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(yt.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(yt.author, fontSize = 12.sp, color = Color.Gray)
                                }
                                
                                // Preview play button
                                IconButton(onClick = {
                                    // Previewing an alternative also SELECTS it, so "Confirmar" becomes
                                    // enabled. Otherwise a user who only taps preview (the natural way to
                                    // try an alternative) leaves selectedTrackId null → Confirmar disabled
                                    // → "the confirm button does nothing".
                                    selectedTrackId = yt.id
                                    if (playingPreviewId == yt.id) {
                                        audioPlayerService.pause()
                                        playingPreviewId = null
                                    } else {
                                        playingPreviewId = yt.id
                                        audioPlayerService.playPreview(track.id ?: "", yt.id)
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (playingPreviewId == yt.id && playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Preview",
                                        tint = Color(0xFF1DB954)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedTrackId?.let { onMappingSelected(it) } },
                enabled = selectedTrackId != null
            ) {
                Text("Confirmar", color = Color(0xFF1DB954))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun TrackScreen(
    trackId: String,
    spotifyRepo: SpotifyRepository,
    audioPlayerService: AudioPlayerService,
    onBackClick: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    onGoToRadio: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var trackDetails by remember { mutableStateOf<FullTrack?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showMappingDialog by remember { mutableStateOf(false) }
    var showQueueBottomSheet by remember { mutableStateOf(false) }

    val playerState by audioPlayerService.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val isCurrentTrack = playerState.currentTrack?.id == trackId
    val trackToShow = if (isCurrentTrack) playerState.currentTrack else trackDetails

    val isPlaying = playerState.isPlaying
    val isBuffering = playerState.isBuffering
    val isError = playerState.isError
    val bufferPercent = playerState.bufferPercent

    val currentPosition = if (isCurrentTrack) playerState.positionMs else 0L
    val totalDuration = if (isCurrentTrack) playerState.durationMs else (trackToShow?.durationMs?.toLong() ?: 0L)

    val currentQueue = playerState.queue
    val currentTrackIdx = currentQueue.indexOfFirst { it.id == (trackToShow?.id ?: trackId) }
    val hasPrevious = currentTrackIdx > 0
    val hasNext = currentTrackIdx != -1 && currentTrackIdx < currentQueue.size - 1

    LaunchedEffect(trackId) {
        if (!isCurrentTrack) {
            isLoading = true
            errorMessage = null
            try {
                trackDetails = spotifyRepo.getTrack(trackId)
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load track"
            } finally {
                isLoading = false
            }
        } else {
            isLoading = false
        }
    }

    // Observe preloaded lyrics from AudioPlayerService (BUG-20 fix)
    val preloadedLyrics by audioPlayerService.preloadedLyrics.collectAsState()
    var lyricsResult by remember(trackToShow?.id) { mutableStateOf<LyricsResult?>(null) }
    var lyricsLoading by remember(trackToShow?.id) { mutableStateOf(false) }

    LaunchedEffect(trackToShow?.id) {
        val track = trackToShow ?: return@LaunchedEffect
        if (track.id == null) return@LaunchedEffect

        // First check if preloaded lyrics are already available for this track
        val cached = preloadedLyrics
        if (cached != null && audioPlayerService.preloadedLyricsTrackId == track.id) {
            lyricsResult = cached
            lyricsLoading = false
            return@LaunchedEffect
        }

        lyricsLoading = true
        lyricsResult = null
        val artist = track.artists.firstOrNull()?.name ?: ""
        val durationSec = track.durationMs / 1000
        try {
            lyricsResult = LyricsRepository.getLyrics(
                trackId = track.id!!,
                artist = artist,
                title = track.name,
                durationSec = durationSec
            )
        } catch (_: Exception) {}
        lyricsLoading = false
    }

    // React to preloaded lyrics arriving asynchronously (skip-to-next scenario)
    LaunchedEffect(preloadedLyrics) {
        val track = trackToShow ?: return@LaunchedEffect
        val trackId = track.id ?: return@LaunchedEffect
        if (preloadedLyrics != null && audioPlayerService.preloadedLyricsTrackId == trackId) {
            lyricsResult = preloadedLyrics
            lyricsLoading = false
        }
    }

    val spotifyGreen = Color(0xFF1DB954)
    val darkBackground = Color(0xFF121212)

    val imgUrl = trackToShow?.effectiveCoverUrl()
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        containerColor = darkBackground,
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = spotifyGreen)
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    trackDetails = spotifyRepo.getTrack(trackId)
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Failed to load track"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }) { Text("Retry") }
                    }
                }
            } else {
                trackToShow?.let { track ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Blurred Background
                        if (!imgUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = imgUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(80.dp),
                                contentScale = ContentScale.Crop,
                                alpha = 0.5f
                            )
                        }

                        // Content overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, darkBackground.copy(alpha = 0.8f), darkBackground),
                                        startY = 0f,
                                        endY = 1500f
                                    )
                                )
                        ) {
                            if (isLandscape) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = 48.dp)
                                        .padding(horizontal = 24.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Cover Art
                                    Surface(
                                        modifier = Modifier
                                            .size(180.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        color = Color.DarkGray
                                    ) {
                                        if (!imgUrl.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = imgUrl,
                                                contentDescription = track.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(24.dp))
                                    
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .verticalScroll(rememberScrollState()),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        TrackScreenControls(
                                            track = track,
                                            lyricsResult = lyricsResult,
                                            lyricsLoading = lyricsLoading,
                                            isBuffering = isBuffering,
                                            isPlaying = isPlaying,
                                            bufferPercent = bufferPercent,
                                            hasPrevious = hasPrevious,
                                            hasNext = hasNext,
                                            isCurrentTrack = isCurrentTrack,
                                            currentPosition = currentPosition,
                                            totalDuration = totalDuration,
                                            playerState = playerState,
                                            audioPlayerService = audioPlayerService,
                                            spotifyRepo = spotifyRepo,
                                            onShowMappingDialog = { showMappingDialog = true },
                                            onShowQueue = { showQueueBottomSheet = true },
                                            onAlbumClick = onAlbumClick,
                                            onArtistClick = onArtistClick,
                                            coroutineScope = coroutineScope
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = 64.dp)
                                        .padding(horizontal = 24.dp, vertical = 16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    // Big Cover Art
                                    Surface(
                                        modifier = Modifier
                                            .size(280.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        color = Color.DarkGray
                                    ) {
                                        if (!imgUrl.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = imgUrl,
                                                contentDescription = track.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(24.dp))
                                    
                                    TrackScreenControls(
                                        track = track,
                                        lyricsResult = lyricsResult,
                                        lyricsLoading = lyricsLoading,
                                        isBuffering = isBuffering,
                                        isPlaying = isPlaying,
                                        bufferPercent = bufferPercent,
                                        hasPrevious = hasPrevious,
                                        hasNext = hasNext,
                                        isCurrentTrack = isCurrentTrack,
                                        currentPosition = currentPosition,
                                        totalDuration = totalDuration,
                                        playerState = playerState,
                                        audioPlayerService = audioPlayerService,
                                        spotifyRepo = spotifyRepo,
                                        onShowMappingDialog = { showMappingDialog = true },
                                        onShowQueue = { showQueueBottomSheet = true },
                                        onAlbumClick = onAlbumClick,
                                        onArtistClick = onArtistClick,
                                        coroutineScope = coroutineScope
                                    )
                                }
                            }
                        }
                    }

                    // Error banner — shown when stream resolution fails
                    if (isError) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFCC2200).copy(alpha = 0.85f))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = playerState.errorMessage.ifBlank { "No se encontró en YouTube Music" },
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                val inQueue = playerState.queue.any { it.id == trackToShow?.id }
                                if (inQueue && trackToShow?.id != null) {
                                    audioPlayerService.playSpecificTrackInQueue(trackToShow.id!!)
                                } else {
                                    audioPlayerService.retryCurrentTrack(fallbackTrackId = trackToShow?.id)
                                }
                            }) {
                                Text("Reintentar", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Custom floating top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (isLandscape) 8.dp else 40.dp, start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    trackToShow?.let {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (showMappingDialog) {
        trackToShow?.let { track ->
            val alternativeChangedMsg = stringResource(com.varuna.rustify.R.string.track_alternative_changed)
            YouTubeMappingDialog(
                track = track,
                audioPlayerService = audioPlayerService,
                onDismiss = { showMappingDialog = false },
                onMappingSelected = { ytId ->
                    showMappingDialog = false
                    track.id?.let { tid ->
                        NativeEngine.setAlternativeTrackNative(tid, ytId)
                        LyricsRepository.invalidateLyrics(tid)
                        
                        android.widget.Toast.makeText(
                            context.applicationContext,
                            alternativeChangedMsg,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        
                        val inQueue = playerState.queue.any { it.id == tid }
                        if (isCurrentTrack) {
                            audioPlayerService.retryCurrentTrack(ytId, fallbackTrackId = tid)
                        } else if (inQueue) {
                            audioPlayerService.playSpecificTrackInQueue(tid, ytId)
                        } else {
                            audioPlayerService.loadAndPlay(track)
                            audioPlayerService.retryCurrentTrack(ytId)
                        }
                    }
                }
            )
        }
    }

    if (showQueueBottomSheet) {
        QueueBottomSheet(
            playerState = playerState,
            audioPlayerService = audioPlayerService,
            onDismiss = { showQueueBottomSheet = false }
        )
    }

    if (showOptionsMenu && trackToShow != null) {
        TrackOptionsMenuBottomSheet(
            track = trackToShow,
            spotifyRepo = spotifyRepo,
            onDismiss = { showOptionsMenu = false },
            onAddToQueue = {
                audioPlayerService.enqueue(trackToShow)
                showOptionsMenu = false
            },
            onGoToQueue = {
                showOptionsMenu = false
                showQueueBottomSheet = true
            },
            onGoToAlbum = { id, name, images ->
                showOptionsMenu = false
                onAlbumClick(id, name, images)
            },
            onGoToArtist = { id ->
                showOptionsMenu = false
                onArtistClick(id)
            },
            onGoToRadio = onGoToRadio?.let { cb -> {
                showOptionsMenu = false
                cb(trackToShow.id ?: "", trackToShow.name)
            } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    playerState: AudioPlayerState,
    audioPlayerService: AudioPlayerService,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Cola de reproducción",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Current Track Section
            Text(
                text = "Sonando ahora",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            playerState.currentTrack?.let { current ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val imgUrl = current.album?.images?.minByOrNull { it.width ?: 999 }?.url
                    Surface(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
                        color = Color.DarkGray
                    ) {
                        if (!imgUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = imgUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(current.name, fontWeight = FontWeight.SemiBold, color = Color(0xFF1DB954), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current.artists.joinToString(", ") { it.name }, color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Next Up Section
            Text(
                text = stringResource(R.string.queue_next_up),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val queue = playerState.queue
            val currentIdx = queue.indexOfFirst { it.id == playerState.currentTrack?.id }
            val nextUpTracks = if (currentIdx != -1) queue.drop(currentIdx + 1) else queue
            val nextUpStartIndex = if (currentIdx != -1) currentIdx + 1 else 0

            if (nextUpTracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.queue_empty), color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    itemsIndexed(nextUpTracks, key = { index, track -> "${track.id ?: "unknown"}_$index" }) { indexInNextUp, track ->
                        val originalIndex = nextUpStartIndex + indexInNextUp
                        
                        val dismissState = rememberSwipeToDismissBoxState(
                            positionalThreshold = { it * 0.4f }
                        )

                        LaunchedEffect(dismissState.currentValue) {
                            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart || dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
                                if (originalIndex in queue.indices) {
                                    audioPlayerService.removeFromQueue(originalIndex)
                                }
                                dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                            }
                        }

                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = true,
                            backgroundContent = {
                                val direction = dismissState.dismissDirection
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFFCC2200))
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color.White
                                    )
                                }
                            },
                            content = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1E1E1E))
                                        .clickable {
                                            track.id?.let { id ->
                                                audioPlayerService.playSpecificTrackInQueue(id)
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    var accumulatedDragY = 0f
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .pointerInput(originalIndex) {
                                                detectDragGestures(
                                                    onDragStart = { accumulatedDragY = 0f },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        accumulatedDragY += dragAmount.y
                                                        val threshold = 90f
                                                        if (accumulatedDragY > threshold) {
                                                            val target = originalIndex + 1
                                                            if (target < queue.size) {
                                                                audioPlayerService.moveQueueItem(originalIndex, target)
                                                            }
                                                            accumulatedDragY = 0f
                                                        } else if (accumulatedDragY < -threshold) {
                                                            val target = originalIndex - 1
                                                            if (target > currentIdx) {
                                                                audioPlayerService.moveQueueItem(originalIndex, target)
                                                            }
                                                            accumulatedDragY = 0f
                                                        }
                                                    }
                                                )
                                            }
                                    )

                                    val imgUrl = track.album?.images?.minByOrNull { it.width ?: 999 }?.url
                                    Surface(
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                                        color = Color.DarkGray
                                    ) {
                                        if (!imgUrl.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = imgUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(track.name, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artists.joinToString(", ") { it.name }, color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TrackScreenControls(
    track: FullTrack,
    lyricsResult: LyricsResult?,
    lyricsLoading: Boolean,
    isBuffering: Boolean,
    isPlaying: Boolean,
    bufferPercent: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    isCurrentTrack: Boolean,
    currentPosition: Long,
    totalDuration: Long,
    playerState: AudioPlayerState,
    audioPlayerService: AudioPlayerService,
    spotifyRepo: SpotifyRepository,
    onShowMappingDialog: () -> Unit,
    onShowQueue: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val lyricsListState = rememberLazyListState()

    // Auto-scroll to current lyric line
    LaunchedEffect(currentPosition, showLyrics) {
        if (!showLyrics) return@LaunchedEffect
        val synced = lyricsResult?.synced ?: return@LaunchedEffect
        if (synced.isEmpty()) return@LaunchedEffect
        val idx = synced.indexOfLast { it.timeMs <= currentPosition }.coerceAtLeast(0)
        try {
            lyricsListState.animateScrollToItem(idx)
        } catch (_: Exception) {}
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (isBuffering) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .height(2.dp),
                color = Color(0xFF1DB954),
                trackColor = Color.Transparent
            )
        } else {
            Spacer(modifier = Modifier.height(18.dp))
        }

        // Title and Like button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().bouncingMarquee()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = track.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF1DB954),
                    modifier = Modifier.fillMaxWidth().bouncingMarquee().clickable {
                        if (track.artists.isNotEmpty()) {
                            onArtistClick(track.artists.first().id)
                        }
                    }
                )
            }

            // Like Button
            val isLiked = track.id?.let { spotifyRepo.isTrackLiked(it) } ?: false
            SpotifyLikeButton(
                isLiked = isLiked,
                onClick = {
                    coroutineScope.launch {
                        spotifyRepo.toggleLikeTrack(track)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Album Link
        Text(
            text = track.album?.name ?: "",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.LightGray,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().bouncingMarquee().clickable {
                track.album?.let {
                    onAlbumClick(it.id, it.name, it.images)
                }
            }.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(16.dp))


        // Premium Seek Slider
        var sliderPosition by remember { mutableStateOf<Float?>(null) }
        val displayPosition = sliderPosition ?: currentPosition.toFloat()

        Slider(
            value = displayPosition.coerceIn(0f, totalDuration.toFloat().coerceAtLeast(1f)),
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = {
                sliderPosition?.let {
                    if (isCurrentTrack) {
                        audioPlayerService.seekTo(it.toLong())
                    }
                }
                sliderPosition = null
            },
            valueRange = 0f..totalDuration.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF1DB954),
                inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val formatTime = { millis: Long ->
                val totalSecs = millis / 1000
                val minutes = totalSecs / 60
                val seconds = totalSecs % 60
                String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
            }
            Text(formatTime(displayPosition.toLong()), color = Color.LightGray, fontSize = 12.sp)
            Text(formatTime(totalDuration), color = Color.LightGray, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Playback Controls Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            // Left Group
            Row(
                modifier = Modifier.weight(1f), 
                horizontalArrangement = Arrangement.SpaceEvenly, 
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Bifurcation (YouTube mapping)
            val isLocal = track.id?.startsWith("local:") == true
            if (!isLocal) {
                IconButton(onClick = onShowMappingDialog) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = stringResource(R.string.track_menu_view_youtube),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }

            // Shuffle / Repeat Mode Button
            IconButton(onClick = { audioPlayerService.cyclePlaybackMode() }) {
                val icon = if (playerState.isRepeat) Icons.Default.Repeat else Icons.Default.Shuffle
                val tint = if (playerState.isShuffle || playerState.isRepeat) Color(0xFF1DB954) else Color.White
                Icon(
                    imageVector = icon,
                    contentDescription = "Playback Mode",
                    tint = tint,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Previous button
            IconButton(
                onClick = { audioPlayerService.skipToPrevious() },
                enabled = hasPrevious
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (hasPrevious) Color.White else Color.Gray,
                    modifier = Modifier.size(36.dp)
                )
            }
            } // End Left Group

            // Play/Pause button with buffering indicator
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                // Background circle
                Surface(
                    modifier = Modifier
                        .size(72.dp)
                        .clickable {
                            if (isCurrentTrack) {
                                audioPlayerService.togglePlayPause()
                            } else {
                                val inQueue = playerState.queue.any { it.id == track.id }
                                if (inQueue && track.id != null) {
                                    audioPlayerService.playSpecificTrackInQueue(track.id!!)
                                } else {
                                    audioPlayerService.loadAndPlay(track)
                                }
                            }
                        },
                    shape = CircleShape,
                    color = if (isBuffering) Color(0xFF1DB954).copy(alpha = 0.4f) else Color(0xFF1DB954)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                // Buffer progress arc drawn OVER the circle
                if (isBuffering && bufferPercent in 1..99) {
                    Canvas(modifier = Modifier.size(80.dp)) {
                        val strokeWidth = 4.dp.toPx()
                        val sweepAngle = (bufferPercent / 100f) * 360f
                        drawArc(
                            color = Color.White.copy(alpha = 0.9f),
                            startAngle = -90f,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            style = Stroke(
                                width = strokeWidth,
                                cap = StrokeCap.Round
                            )
                        )
                    }
                }
            }

            // Right Group
            Row(
                modifier = Modifier.weight(1f), 
                horizontalArrangement = Arrangement.SpaceEvenly, 
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Next button
            IconButton(
                onClick = { audioPlayerService.skipToNext() },
                enabled = hasNext
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = if (hasNext) Color.White else Color.Gray,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Queue Button
            IconButton(onClick = onShowQueue) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = "View Queue",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Lyrics Button (Far Right)
            IconButton(onClick = { showLyrics = !showLyrics }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Subject,
                    contentDescription = "Lyrics",
                    tint = if (showLyrics) Color(0xFF1DB954) else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            } // End Right Group
        }
        
        // Lyrics Panel
        if (showLyrics) {
            Spacer(modifier = Modifier.height(16.dp))
            LyricsView(
                lyricsResult = lyricsResult,
                isLoading = lyricsLoading,
                currentPositionMs = currentPosition,
                listState = lyricsListState
            )
        }
    }
}

@Composable
fun LyricsView(
    lyricsResult: LyricsResult?,
    isLoading: Boolean,
    currentPositionMs: Long,
    listState: LazyListState
) {
    val spotifyGreen = Color(0xFF1DB954)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 350.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = spotifyGreen, modifier = Modifier.size(32.dp))
                }
            }
            lyricsResult == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No se encontraron letras",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            lyricsResult.synced.isNotEmpty() -> {
                val synced = lyricsResult.synced
                val currentIdx = synced.indexOfLast { it.timeMs <= currentPositionMs }.coerceAtLeast(0)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(synced) { index, line ->
                        val isActive = index == currentIdx
                        Text(
                            text = line.text.ifBlank { "♪" },
                            color = if (isActive) Color.White else Color.Gray.copy(alpha = 0.5f),
                            fontSize = if (isActive) 18.sp else 15.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (isActive) 8.dp else 4.dp)
                        )
                    }
                }
            }
            lyricsResult.plain != null -> {
                val scrollState = rememberScrollState()
                Text(
                    text = lyricsResult.plain,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                )
            }
            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_not_found),
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
