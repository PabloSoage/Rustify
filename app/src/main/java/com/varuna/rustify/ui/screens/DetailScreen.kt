// app/src/main/java/com/varuna/rustify/ui/screens/DetailScreen.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.varuna.rustify.bridge.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    itemId: String,
    itemName: String,
    itemImages: List<SpotifyImage>,
    isPlaylist: Boolean,
    spotifyRepo: SpotifyRepository,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var playlistDetails by remember { mutableStateOf<FullPlaylist?>(null) }
    var albumDetails by remember { mutableStateOf<FullAlbum?>(null) }
    var tracks by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    // Load full details and tracklist on item change
    LaunchedEffect(itemId) {
        isLoading = true
        errorMessage = null
        try {
            if (isPlaylist) {
                playlistDetails = spotifyRepo.getPlaylist(itemId)
                // Fetch first 50 tracks inside the playlist
                val response = spotifyRepo.getPlaylistTracks(itemId, limit = 50, offset = 0)
                tracks = response.items
            } else {
                albumDetails = spotifyRepo.getAlbum(itemId)
                // Fetch tracks in the album
                val response = spotifyRepo.getAlbumTracks(itemId, limit = 50, offset = 0)
                tracks = response.items
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load details"
        } finally {
            isLoading = false
        }
    }

    val primaryImageUrl = itemImages.maxByOrNull { it.width ?: 0 }?.url
        ?: playlistDetails?.images?.maxByOrNull { it.width ?: 0 }?.url
        ?: albumDetails?.images?.maxByOrNull { it.width ?: 0 }?.url

    // Custom Spotify-like harmonious green color palette
    val spotifyGreen = Color(0xFF1DB954)
    val darkBackground = Color(0xFF121212)
    val gradientColor = Color(0xFF2E2E2E) // Deep grey/charcoal gradient header

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = itemName, 
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                ),
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
            )
        },
        containerColor = darkBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // Background ambient glow gradient
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

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(), 
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = spotifyGreen)
                }
            } else if (errorMessage != null) {
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
                                    if (isPlaylist) {
                                        playlistDetails = spotifyRepo.getPlaylist(itemId)
                                        tracks = spotifyRepo.getPlaylistTracks(itemId, limit = 50).items
                                    } else {
                                        albumDetails = spotifyRepo.getAlbum(itemId)
                                        tracks = spotifyRepo.getAlbumTracks(itemId, limit = 50).items
                                    }
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
                    contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = 32.dp)
                ) {
                    // 1. HEADER SECTION
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Cover Image with Drop Shadow & Corner rounding
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
                                            text = itemName.take(1).uppercase(),
                                            style = MaterialTheme.typography.headlineLarge,
                                            color = Color.White
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Metadata info
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = if (isPlaylist) "PLAYLIST" else "ALBUM",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp
                                    ),
                                    color = spotifyGreen
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = itemName,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                val descriptionText = if (isPlaylist) {
                                    playlistDetails?.description ?: "Spotify Playlist"
                                } else {
                                    "Album by " + (albumDetails?.artists?.joinToString(", ") { it.name } ?: "Unknown Artist")
                                }
                                Text(
                                    text = descriptionText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.LightGray,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val subtitleText = if (isPlaylist) {
                                        playlistDetails?.owner?.name ?: "Spotify"
                                    } else {
                                        albumDetails?.releaseDate?.take(4) ?: ""
                                    }
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
                                        text = "${tracks.size} tracks",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    // 2. ACTION ROW
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Big circular play button
                            Surface(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clickable { /* Handle Playlist / Album playback */ },
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

                    // 3. SONGS LIST
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
                            TrackRowItem(
                                index = index + 1,
                                track = track,
                                fallbackCoverUrl = primaryImageUrl,
                                onClick = {
                                    // Handle song playback / select track
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackRowItem(
    index: Int,
    track: FullTrack,
    fallbackCoverUrl: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track Index Number
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.Gray,
            modifier = Modifier.width(32.dp)
        )

        // Track Cover art thumbnail
        val trackImageUrl = track.album?.images?.minByOrNull { it.width ?: 999 }?.url ?: fallbackCoverUrl
        Surface(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Color.DarkGray
        ) {
            if (!trackImageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = trackImageUrl,
                    contentDescription = "Track Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = track.name.take(1).uppercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artists
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Explicit Badge
                if (track.explicit) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .background(Color.Gray.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "E",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.Black,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.8f
                        )
                    }
                }
                Text(
                    text = track.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Track Duration
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
    }
}

private fun formatDuration(ms: Int): String {
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format(java.util.Locale.getDefault(), "%d:%02d", mins, secs)
}
