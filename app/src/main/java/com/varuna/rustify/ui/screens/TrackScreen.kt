package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackScreen(
    trackId: String,
    spotifyRepo: SpotifyRepository,
    onBackClick: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var trackDetails by remember { mutableStateOf<FullTrack?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val coroutineScope = rememberCoroutineScope()

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

    val imgUrl = trackDetails?.album?.images?.maxByOrNull { it.width ?: 0 }?.url

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
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
                    containerColor = Color.Transparent
                )
            )
        },
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
            trackDetails?.let { track ->
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
                            .padding(innerPadding)
                            .padding(horizontal = 24.dp, vertical = 32.dp),
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

                        Spacer(modifier = Modifier.height(32.dp))

                        // Title
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Artist Link
                        Text(
                            text = track.artists.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.titleMedium,
                            color = spotifyGreen,
                            modifier = Modifier.clickable {
                                if (track.artists.isNotEmpty()) {
                                    onArtistClick(track.artists.first().id)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Album Link
                        Text(
                            text = track.album?.name ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.LightGray,
                            modifier = Modifier.clickable {
                                track.album?.let {
                                    onAlbumClick(it.id, it.name, it.images)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // Actions Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            IconButton(onClick = { /* Add to queue */ }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Add to Queue",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clickable { /* Play track */ },
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
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            // Favorite placeholder
                            IconButton(onClick = { /* Like */ }) {
                                Text("♡", color = Color.White, fontSize = 28.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
