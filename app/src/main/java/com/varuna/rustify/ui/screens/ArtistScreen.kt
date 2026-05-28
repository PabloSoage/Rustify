package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.*
import com.varuna.rustify.ui.components.TrackRowItem
import com.varuna.rustify.ui.components.PlaylistItemCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    artistId: String,
    spotifyRepo: SpotifyRepository,
    onBackClick: () -> Unit,
    onTrackClick: (FullTrack) -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var artistDetails by remember { mutableStateOf<FullArtist?>(null) }
    var topTracks by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var albums by remember { mutableStateOf<List<SimpleAlbum>>(emptyList()) }
    var relatedArtists by remember { mutableStateOf<List<FullArtist>>(emptyList()) }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                // Fetch all data in parallel
                val artistDef = async { spotifyRepo.getArtist(artistId) }
                val tracksDef = async { spotifyRepo.getArtistTopTracks(artistId, limit = 10) }
                val albumsDef = async { spotifyRepo.getArtistAlbums(artistId, limit = 20) }
                val relatedDef = async { spotifyRepo.getRelatedArtists(artistId, limit = 10) }

                artistDetails = artistDef.await()
                topTracks = tracksDef.await().items
                albums = albumsDef.await().items
                relatedArtists = relatedDef.await().items
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load artist"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(artistId) {
        loadData()
    }

    val spotifyGreen = Color(0xFF1DB954)
    val darkBackground = Color(0xFF121212)

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
                    Button(onClick = { loadData() }) { Text("Retry") }
                }
            }
        } else {
            artistDetails?.let { artist ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    // HEADER (Image + Name + Follow button)
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = innerPadding.calculateTopPadding()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val imgUrl = artist.images.maxByOrNull { it.width ?: 0 }?.url
                            Surface(
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(CircleShape),
                                color = Color.DarkGray
                            ) {
                                if (!imgUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = imgUrl,
                                        contentDescription = "Artist Art",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = artist.name.take(1).uppercase(),
                                            style = MaterialTheme.typography.displayMedium,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${artist.followersTotal ?: 0} followers",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { /* Toggle follow */ },
                                colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                shape = RoundedCornerShape(32.dp)
                            ) {
                                Text("Follow", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }

                    // TOP TRACKS
                    if (topTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Top Tracks",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(topTracks.take(5).withIndex().toList()) { (index, track) ->
                            TrackRowItem(
                                index = index + 1,
                                track = track,
                                fallbackCoverUrl = artist.images.minByOrNull { it.width ?: 999 }?.url,
                                onClick = { onTrackClick(track) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }

                    // ALBUMS
                    if (albums.isNotEmpty()) {
                        item {
                            Text(
                                text = "Albums",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(albums) { album ->
                                    PlaylistItemCard(
                                        title = album.name,
                                        subtitle = album.releaseDate?.take(4),
                                        images = album.images,
                                        onClick = { onAlbumClick(album.id, album.name, album.images) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // FANS ALSO LIKE (Related Artists)
                    if (relatedArtists.isNotEmpty()) {
                        item {
                            Text(
                                text = "Fans Also Like",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(relatedArtists) { relArtist ->
                                    PlaylistItemCard(
                                        title = relArtist.name,
                                        subtitle = "Artist",
                                        images = relArtist.images,
                                        isCircle = true,
                                        onClick = { onArtistClick(relArtist.id) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
