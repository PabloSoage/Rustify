package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SearchFilter {
    ALL, TRACKS, ALBUMS, ARTISTS, PLAYLISTS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    spotifyRepo: SpotifyRepository,
    onTrackClick: (FullTrack) -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onPlaylistClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkBackground = Color(0xFF121212)
    val spotifyGreen = Color(0xFF1DB954)
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(SearchFilter.ALL) }

    var isLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<NormalizedSearchResults?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun performSearch(query: String) {
        if (query.isBlank()) {
            searchResults = null
            return
        }
        
        searchJob?.cancel()
        searchJob = coroutineScope.launch {
            isLoading = true
            errorMessage = null
            // Debounce
            delay(500)
            try {
                // To keep it simple, we use searchAll for ALL filter and specific ones for others.
                // Or we can just use searchAll and filter locally for demonstration.
                val results = spotifyRepo.searchAll(query, limit = 20)
                searchResults = results
                val trackIds = results.tracks.mapNotNull { it.id }
                spotifyRepo.checkAndCacheLikedStates(trackIds)
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(darkBackground)
    ) {
        // Search Bar Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { 
                    searchQuery = it
                    performSearch(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp)),
                placeholder = { Text("What do you want to listen to?", color = Color.Gray) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { 
                            searchQuery = "" 
                            searchResults = null
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF242424),
                    unfocusedContainerColor = Color(0xFF242424),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = spotifyGreen
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        performSearch(searchQuery)
                    }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Filter Chips
            PrimaryScrollableTabRow(
                selectedTabIndex = activeFilter.ordinal,
                containerColor = Color.Transparent,
                edgePadding = 0.dp
            ) {
                SearchFilter.entries.forEach { filter ->
                    val isSelected = activeFilter == filter
                    Surface(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { activeFilter = filter },
                        color = if (isSelected) spotifyGreen else Color(0xFF2A2A2A),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = filter.name.lowercase().replaceFirstChar { it.uppercase() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = if (isSelected) Color.Black else Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }

        // Results Area
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = spotifyGreen
                )
            } else if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (searchResults == null && searchQuery.isEmpty()) {
                Text(
                    text = "Search for tracks, albums, artists or playlists.",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (searchResults != null) {
                val results = searchResults!!
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 100.dp) // Nav bar padding
                ) {
                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.TRACKS) {
                        if (results.tracks.isNotEmpty()) {
                            item { SectionHeader("Tracks") }
                            items(results.tracks) { track ->
                                val trackId = track.id ?: ""
                                val isLiked = spotifyRepo.isTrackLiked(trackId)
                                SearchResultRow(
                                    title = track.name,
                                    subtitle = "Track • ${track.artists.joinToString(", ") { it.name }}",
                                    imageUrl = track.album?.images?.maxByOrNull { it.width ?: 0 }?.url,
                                    onClick = { onTrackClick(track) },
                                    isLiked = isLiked,
                                    onLikeToggle = {
                                        coroutineScope.launch {
                                            spotifyRepo.toggleLikeTrack(trackId)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.ALBUMS) {
                        if (results.albums.isNotEmpty()) {
                            item { SectionHeader("Albums") }
                            items(results.albums) { album ->
                                SearchResultRow(
                                    title = album.name,
                                    subtitle = "Album • ${album.artists.joinToString(", ") { it.name }}",
                                    imageUrl = album.images.maxByOrNull { it.width ?: 0 }?.url,
                                    onClick = { onAlbumClick(album.id, album.name, album.images) }
                                )
                            }
                        }
                    }

                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.PLAYLISTS) {
                        if (results.playlists.isNotEmpty()) {
                            item { SectionHeader("Playlists") }
                            items(results.playlists) { playlist ->
                                SearchResultRow(
                                    title = playlist.name,
                                    subtitle = "Playlist • ${playlist.owner?.name ?: "Spotify"}",
                                    imageUrl = playlist.images.maxByOrNull { it.width ?: 0 }?.url,
                                    onClick = { onPlaylistClick(playlist.id, playlist.name, playlist.images) }
                                )
                            }
                        }
                    }

                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.ARTISTS) {
                        if (results.artists.isNotEmpty()) {
                            item { SectionHeader("Artists") }
                            items(results.artists) { artist ->
                                SearchResultRow(
                                    title = artist.name,
                                    subtitle = "Artist",
                                    imageUrl = artist.images.maxByOrNull { it.width ?: 0 }?.url,
                                    isCircle = true,
                                    onClick = { artist.id?.let(onArtistClick) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            color = Color.White
        ),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SearchResultRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    isCircle: Boolean = false,
    onClick: () -> Unit,
    isLiked: Boolean = false,
    onLikeToggle: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(56.dp)
                .clip(if (isCircle) RoundedCornerShape(28.dp) else RoundedCornerShape(4.dp)),
            color = Color.DarkGray
        ) {
            if (!imageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.take(1).uppercase(),
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onLikeToggle != null) {
            IconButton(
                onClick = onLikeToggle,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = if (isLiked) "♥" else "♡",
                    color = if (isLiked) Color(0xFF1DB954) else Color.White,
                    fontSize = 20.sp
                )
            }
        }
    }
}
