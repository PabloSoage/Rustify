package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullAlbum
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.TrackRowItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    albumId: String,
    albumName: String,
    albumImages: List<SpotifyImage>,
    spotifyRepo: SpotifyRepository,
    onBackClick: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAddToQueue: (FullTrack) -> Unit,
    onGoToQueue: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    currentTrackId: String? = null,
    modifier: Modifier = Modifier
) {
    var albumDetails by remember { mutableStateOf<FullAlbum?>(null) }
    var tracks by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Pagination state
    var offset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(albumId) {
        isLoading = true
        errorMessage = null
        try {
            albumDetails = spotifyRepo.getAlbum(albumId)
            val response = spotifyRepo.getAlbumTracks(albumId, limit = 50, offset = 0)
            tracks = response.items
            hasMore = response.hasMore
            offset = tracks.size

        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load details"
        } finally {
            isLoading = false
        }
    }

    val primaryImageUrl = albumImages.maxByOrNull { it.width ?: 0 }?.url
        ?: albumDetails?.images?.maxByOrNull { it.width ?: 0 }?.url

    val spotifyGreen = Color(0xFF1DB954)
    val darkBackground = Color(0xFF121212)
    val gradientColor = Color(0xFF2E2E2E)

    Scaffold(
        containerColor = darkBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(gradientColor, darkBackground)
                        )
                    )
            )

            if (isLoading && tracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(), 
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = spotifyGreen)
                }
            } else if (errorMessage != null && tracks.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Something went wrong", 
                        style = MaterialTheme.typography.titleLarge, 
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "Unknown error", 
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    albumDetails = spotifyRepo.getAlbum(albumId)
                                    val response = spotifyRepo.getAlbumTracks(albumId, limit = 50, offset = 0)
                                    tracks = response.items
                                    hasMore = response.hasMore
                                    offset = tracks.size
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen)
                    ) {
                        Text("Retry", color = Color.White)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 32.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(200.dp)
                                    .shadow(16.dp, RoundedCornerShape(12.dp))
                                    .clip(RoundedCornerShape(12.dp)),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                if (!primaryImageUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = primaryImageUrl,
                                        contentDescription = "Cover Art",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.DarkGray),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = albumName.take(1).uppercase(),
                                            style = MaterialTheme.typography.headlineLarge,
                                            color = Color.White
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "ALBUM",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp
                                    ),
                                    color = spotifyGreen
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = albumName,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                val descriptionText = "Album by " + (albumDetails?.artists?.joinToString(", ") { it.name } ?: "Unknown Artist")
                                Text(
                                    text = descriptionText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.LightGray,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val subtitleText = albumDetails?.releaseDate?.take(4) ?: ""
                                    if (subtitleText.isNotEmpty()) {
                                        Text(
                                            text = subtitleText,
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White
                                        )
                                        Text(
                                            text = " • ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                    Text(
                                        text = "${albumDetails?.totalTracks ?: tracks.size} tracks",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clickable { 
                                        if (tracks.isNotEmpty()) {
                                            onTrackClick(tracks, 0)
                                        }
                                    },
                                shape = CircleShape,
                                color = spotifyGreen
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.Black,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (tracks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No tracks found", color = Color.Gray)
                            }
                        }
                    } else {
                        itemsIndexed(tracks) { index, track ->
                            // Request more data when we reach near the end of the list
                            if (index >= tracks.size - 5 && !isLoadingMore && hasMore) {
                                LaunchedEffect(index) {
                                    isLoadingMore = true
                                    try {
                                        val response = spotifyRepo.getAlbumTracks(albumId, limit = 50, offset = offset)
                                        if (response.items.isNotEmpty()) {
                                            tracks = tracks + response.items
                                            offset += response.items.size
                                            hasMore = response.hasMore

                                        } else {
                                            hasMore = false
                                        }
                                    } catch (_: Exception) {
                                        // Ignore pagination errors or show a toast
                                    } finally {
                                        isLoadingMore = false
                                    }
                                }
                            }
                            
                            val trackId = track.id ?: ""
                            val isLiked = spotifyRepo.isTrackLiked(trackId)
                            TrackRowItem(
                                index = index + 1,
                                track = track,
                                fallbackCoverUrl = primaryImageUrl,
                                onClick = { onTrackClick(tracks, index) },
                                isLiked = isLiked,
                                isCurrentTrack = track.id == currentTrackId,
                                onLikeToggle = {
                                    coroutineScope.launch {
                                        spotifyRepo.toggleLikeTrack(track)
                                    }
                                },
                                onMoreClick = { selectedTrackForMenu = track }
                            )
                        }
                        
                        if (isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = spotifyGreen, modifier = Modifier.size(24.dp))
                                }
                            }
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
            }
        }
    }

    if (selectedTrackForMenu != null) {
        TrackOptionsMenuBottomSheet(
            track = selectedTrackForMenu!!,
            spotifyRepo = spotifyRepo,
            onDismiss = { selectedTrackForMenu = null },
            onAddToQueue = {
                onAddToQueue(selectedTrackForMenu!!)
                selectedTrackForMenu = null
            },
            onGoToQueue = {
                onGoToQueue()
                selectedTrackForMenu = null
            },
            onGoToAlbum = { id, name, images ->
                onAlbumClick(id, name, images)
                selectedTrackForMenu = null
            },
            onGoToArtist = { id ->
                onArtistClick(id)
                selectedTrackForMenu = null
            },
            onRemoveFromPlaylist = null
        )
    }
}
