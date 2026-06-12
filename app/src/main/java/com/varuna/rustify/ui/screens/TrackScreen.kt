@file:Suppress("SpellCheckingInspection")

package com.varuna.rustify.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
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
            try {
                val res = NativeEngine.searchYouTubeNative(query)
                results = YouTubeTrack.listFromJsonArray(res)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSearching = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            audioPlayerService.pause()
            onDismiss()
        },
        title = { Text("Vincular con YouTube Music", color = Color.White) },
        containerColor = Color(0xFF1E1E1E),
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar en YouTube Music", color = Color.LightGray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF1DB954),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        isSearching = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val res = NativeEngine.searchYouTubeNative(query)
                                results = YouTubeTrack.listFromJsonArray(res)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                isSearching = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Buscar", color = Color.Black)
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF1DB954))
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(results) { ytTrack ->
                            val isSelected = ytTrack.id == selectedTrackId
                            val isCurrentPreview = ytTrack.id == playingPreviewId
                            val isCurrentPreviewPlaying = isCurrentPreview && playerState.isPlaying

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF1DB954).copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        selectedTrackId = ytTrack.id
                                        if (isCurrentPreview) {
                                            audioPlayerService.togglePlayPause()
                                        } else {
                                            playingPreviewId = ytTrack.id
                                            audioPlayerService.playPreview(track.id ?: "", ytTrack.id)
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isCurrentPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isCurrentPreviewPlaying) "Pause" else "Play",
                                    tint = if (isSelected) Color(0xFF1DB954) else Color.LightGray,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        ytTrack.title,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        ytTrack.author,
                                        color = Color.LightGray,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (ytTrack.durationSec > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val mins = ytTrack.durationSec / 60
                                    val secs = ytTrack.durationSec % 60
                                    Text(
                                        String.format(java.util.Locale.US, "%d:%02d", mins, secs),
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            HorizontalDivider(color = Color.DarkGray)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    audioPlayerService.pause()
                    selectedTrackId?.let { onMappingSelected(it) }
                },
                enabled = selectedTrackId != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1DB954),
                    disabledContainerColor = Color.DarkGray
                )
            ) {
                Text("Vincular", color = if (selectedTrackId != null) Color.Black else Color.Gray)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    audioPlayerService.pause()
                    onDismiss()
                }
            ) {
                Text("Cancelar", color = Color.LightGray)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackScreen(
    trackId: String,
    spotifyRepo: SpotifyRepository,
    audioPlayerService: AudioPlayerService,
    onBackClick: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var trackDetails by remember { mutableStateOf<FullTrack?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showMappingDialog by remember { mutableStateOf(false) }
    var showQueueBottomSheet by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()
    val playerState by audioPlayerService.state.collectAsState()

    val trackToShow = playerState.currentTrack ?: trackDetails
    val isCurrentTrack = playerState.currentTrack != null || trackDetails?.id == trackId
    val isPlaying = isCurrentTrack && playerState.isPlaying
    val isBuffering = isCurrentTrack && playerState.isBuffering
    val isError = isCurrentTrack && playerState.isError
    val bufferPercent = if (isCurrentTrack) playerState.bufferPercent else 0
    val currentPosition = if (isCurrentTrack) playerState.positionMs else 0L
    val totalDuration = if (isCurrentTrack) playerState.durationMs else (trackToShow?.durationMs?.toLong() ?: 0L)

    LaunchedEffect(trackId) {
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

    val spotifyGreen = Color(0xFF1DB954)
    val darkBackground = Color(0xFF121212)

    val imgUrl = trackToShow?.album?.images?.maxByOrNull { it.width ?: 0 }?.url

    fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }

    Scaffold(
        containerColor = darkBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, darkBackground.copy(alpha = 0.8f), darkBackground),
                                    startY = 0f,
                                    endY = 1500f
                                )
                            )
                            .padding(bottom = innerPadding.calculateBottomPadding(), top = 80.dp)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isBuffering) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                                    .height(2.dp),
                                color = spotifyGreen,
                                trackColor = Color.Transparent
                            )
                        } else {
                            Spacer(modifier = Modifier.height(18.dp))
                        }

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
                                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = track.artists.joinToString(", ") { it.name },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = spotifyGreen,
                                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE).clickable {
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
                            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE).clickable {
                                track.album?.let {
                                    onAlbumClick(it.id, it.name, it.images)
                                }
                            }.align(Alignment.Start)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // YouTube Mapping Override Trigger
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { showMappingDialog = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit YouTube Mapping",
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Versión alternativa de YouTube", color = Color.LightGray, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(24.dp))

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
                                activeTrackColor = spotifyGreen,
                                inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatTime(displayPosition.toLong()), color = Color.LightGray, fontSize = 12.sp)
                            Text(formatTime(totalDuration), color = Color.LightGray, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Playback Controls Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        ) {
                            // Shuffle Button
                            IconButton(onClick = { audioPlayerService.toggleShuffle() }) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (playerState.isShuffle) spotifyGreen else Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            val hasPrevious = isCurrentTrack && playerState.queue.indexOfFirst { it.id == playerState.currentTrack?.id } > 0
                            val hasNext = isCurrentTrack && playerState.queue.indexOfFirst { it.id == playerState.currentTrack?.id } != -1 && playerState.queue.indexOfFirst { it.id == playerState.currentTrack?.id } < playerState.queue.lastIndex

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
                                                audioPlayerService.loadAndPlay(track)
                                            }
                                        },
                                    shape = CircleShape,
                                    color = if (isBuffering) spotifyGreen.copy(alpha = 0.4f) else spotifyGreen
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isBuffering) {
                                            // Show hourglass icon while buffering
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
                            IconButton(onClick = { showQueueBottomSheet = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = "View Queue",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
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
                            trackToShow.let { audioPlayerService.loadAndPlay(it) }
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
                    .padding(top = 40.dp, start = 8.dp, end = 8.dp),
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

    if (showMappingDialog) {
        trackToShow?.let { track ->
            YouTubeMappingDialog(
                track = track,
                audioPlayerService = audioPlayerService,
                onDismiss = { showMappingDialog = false },
                onMappingSelected = { ytId ->
                    showMappingDialog = false
                    track.id?.let { tid ->
                        NativeEngine.setAlternativeTrackNative(tid, ytId)
                        if (isCurrentTrack) {
                            audioPlayerService.loadAndPlay(track)
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
            }
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
                text = "Siguiente en la cola",
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
                    Text("La cola está vacía", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    itemsIndexed(nextUpTracks, key = { indexInNextUp, track -> "${track.id ?: ""}_$indexInNextUp" }) { indexInNextUp, track ->
                        val originalIndex = nextUpStartIndex + indexInNextUp
                        
                        @Suppress("DEPRECATION")
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    audioPlayerService.removeFromQueue(originalIndex)
                                    true
                                } else {
                                    false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFFCC2200))
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
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
