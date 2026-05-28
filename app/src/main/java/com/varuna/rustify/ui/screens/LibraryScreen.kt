package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.varuna.rustify.bridge.*
import kotlinx.coroutines.launch

enum class LibraryTab(val title: String) {
    PLAYLISTS("Playlists"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    TRACKS("Liked Tracks")
}

@Composable
fun LibraryScreen(
    spotifyRepo: SpotifyRepository,
    onPlaylistClick: (String, String, List<SpotifyImage>) -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onTrackClick: (FullTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkBackground = Color(0xFF121212)
    val spotifyGreen = Color(0xFF1DB954)
    var selectedTab by remember { mutableStateOf(LibraryTab.PLAYLISTS) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(darkBackground)
    ) {
        // Header
        Text(
            text = "Your Library",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White
            ),
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
        )

        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = darkBackground,
            contentColor = Color.White,
            edgePadding = 16.dp,
            indicator = {
                TabRowDefaults.PrimaryIndicator(
                    color = spotifyGreen
                )
            }
        ) {
            LibraryTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Tab(
                    selected = selected,
                    onClick = { selectedTab = tab },
                    text = {
                        Text(
                            text = tab.title,
                            color = if (selected) spotifyGreen else Color.LightGray,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                LibraryTab.PLAYLISTS -> LibraryPlaylists(spotifyRepo, onPlaylistClick)
                LibraryTab.ALBUMS -> LibraryAlbums(spotifyRepo, onAlbumClick)
                LibraryTab.ARTISTS -> LibraryArtists(spotifyRepo)
                LibraryTab.TRACKS -> LibraryTracks(spotifyRepo, onTrackClick)
            }
        }
    }
}

@Composable
fun LibraryPlaylists(
    spotifyRepo: SpotifyRepository,
    onPlaylistClick: (String, String, List<SpotifyImage>) -> Unit
) {
    var playlists by remember { mutableStateOf<List<SimplePlaylist>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (playlists == null) {
            isLoading = true
            try {
                playlists = spotifyRepo.getSavedPlaylists(limit = 50).items
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    LibraryContentList(
        isLoading = isLoading,
        errorMessage = errorMessage,
        items = playlists,
        onRetry = {
            coroutineScope.launch {
                isLoading = true
                errorMessage = null
                try {
                    playlists = spotifyRepo.getSavedPlaylists(limit = 50).items
                } catch (e: Exception) {
                    errorMessage = e.message
                } finally {
                    isLoading = false
                }
            }
        },
        itemContent = { playlist ->
            SearchResultRow(
                title = playlist.name,
                subtitle = "Playlist • ${playlist.owner?.name ?: "Spotify"}",
                imageUrl = playlist.images.maxByOrNull { it.width ?: 0 }?.url,
                onClick = { onPlaylistClick(playlist.id, playlist.name, playlist.images) }
            )
        },
        emptyMessage = "No playlists found."
    )
}

@Composable
fun LibraryAlbums(
    spotifyRepo: SpotifyRepository,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit
) {
    var albums by remember { mutableStateOf<List<FullAlbum>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (albums == null) {
            isLoading = true
            try {
                albums = spotifyRepo.getSavedAlbums(limit = 50).items
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    LibraryContentList(
        isLoading = isLoading,
        errorMessage = errorMessage,
        items = albums,
        onRetry = {
            coroutineScope.launch {
                isLoading = true
                errorMessage = null
                try {
                    albums = spotifyRepo.getSavedAlbums(limit = 50).items
                } catch (e: Exception) {
                    errorMessage = e.message
                } finally {
                    isLoading = false
                }
            }
        },
        itemContent = { album ->
            SearchResultRow(
                title = album.name,
                subtitle = "Album • ${album.artists.joinToString(", ") { it.name }}",
                imageUrl = album.images.maxByOrNull { it.width ?: 0 }?.url,
                onClick = { onAlbumClick(album.id, album.name, album.images) }
            )
        },
        emptyMessage = "No saved albums found."
    )
}

@Composable
fun LibraryArtists(
    spotifyRepo: SpotifyRepository
) {
    var artists by remember { mutableStateOf<List<FullArtist>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (artists == null) {
            isLoading = true
            try {
                artists = spotifyRepo.getFollowedArtists(limit = 50).items
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    LibraryContentList(
        isLoading = isLoading,
        errorMessage = errorMessage,
        items = artists,
        onRetry = {
            coroutineScope.launch {
                isLoading = true
                errorMessage = null
                try {
                    artists = spotifyRepo.getFollowedArtists(limit = 50).items
                } catch (e: Exception) {
                    errorMessage = e.message
                } finally {
                    isLoading = false
                }
            }
        },
        itemContent = { artist ->
            SearchResultRow(
                title = artist.name,
                subtitle = "Artist",
                imageUrl = artist.images.maxByOrNull { it.width ?: 0 }?.url,
                isCircle = true,
                onClick = { /* Handle artist click */ }
            )
        },
        emptyMessage = "No followed artists found."
    )
}

@Composable
fun LibraryTracks(
    spotifyRepo: SpotifyRepository,
    onTrackClick: (FullTrack) -> Unit
) {
    var tracks by remember { mutableStateOf<List<FullTrack>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (tracks == null) {
            isLoading = true
            try {
                tracks = spotifyRepo.getSavedTracks(limit = 50).items
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    LibraryContentList(
        isLoading = isLoading,
        errorMessage = errorMessage,
        items = tracks,
        onRetry = {
            coroutineScope.launch {
                isLoading = true
                errorMessage = null
                try {
                    tracks = spotifyRepo.getSavedTracks(limit = 50).items
                } catch (e: Exception) {
                    errorMessage = e.message
                } finally {
                    isLoading = false
                }
            }
        },
        itemContent = { track ->
            SearchResultRow(
                title = track.name,
                subtitle = "Track • ${track.artists.joinToString(", ") { it.name }}",
                imageUrl = track.album?.images?.maxByOrNull { it.width ?: 0 }?.url,
                onClick = { onTrackClick(track) }
            )
        },
        emptyMessage = "No liked tracks found."
    )
}

@Composable
fun <T> LibraryContentList(
    isLoading: Boolean,
    errorMessage: String?,
    items: List<T>?,
    onRetry: () -> Unit,
    itemContent: @Composable (T) -> Unit,
    emptyMessage: String
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading && items == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF1DB954)
            )
        } else if (errorMessage != null && items == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                ) {
                    Text("Retry", color = Color.White)
                }
            }
        } else if (items != null) {
            if (items.isEmpty()) {
                Text(
                    text = emptyMessage,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp) // Bottom nav padding
                ) {
                    items(items) { item ->
                        itemContent(item)
                    }
                }
            }
        }
    }
}
