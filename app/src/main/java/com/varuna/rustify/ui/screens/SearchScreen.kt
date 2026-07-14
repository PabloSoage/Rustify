package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NormalizedSearchResults
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.SpotifyLikeButton
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import com.varuna.rustify.util.SpotifyLink
import com.varuna.rustify.util.SpotifyLinkParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

enum class SearchFilter {
    ALL, TRACKS, ALBUMS, ARTISTS, PLAYLISTS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    spotifyRepo: SpotifyRepository,
    onTrackClick: (FullTrack) -> Unit,
    onOpenTrack: (String) -> Unit,
    onAddToQueue: (FullTrack) -> Unit,
    onGoToQueue: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onPlaylistClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    onGoToRadio: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    currentTrackId: String? = null
) {
    val darkBackground = Color(0xFF121212)
    val spotifyGreen = Color(0xFF1DB954)
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val bottomPadding = if (isLandscape) 16.dp else 100.dp

    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }

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
            delay(500.milliseconds)
            try {
                // To keep it simple, we use searchAll for ALL filter and specific ones for others.
                // Or we can just use searchAll and filter locally for demonstration.
                val results = spotifyRepo.searchAll(query, limit = 20)
                searchResults = results

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Ignore cancellation exceptions
                throw e
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
                placeholder = { Text(stringResource(R.string.search_placeholder), color = Color.Gray) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.nav_search), tint = Color.White)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            searchResults = null
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                        }
                    } else {
                        // Paste Spotify link from clipboard (E20: navigate, don't reproduce a stub).
                        IconButton(onClick = {
                            val pasted = try {
                                val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                cb?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                            } catch (e: Exception) {
                                android.util.Log.w("SearchScreen", "Clipboard read failed: ${e.message}")
                                null
                            }
                            if (pasted.isNullOrBlank()) {
                                android.widget.Toast.makeText(context, R.string.paste_clipboard_empty, android.widget.Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            when (val link = SpotifyLinkParser.parse(pasted)) {
                                is SpotifyLink.Track -> {
                                    android.util.Log.d("SearchScreen", "Pasted Spotify track: ${link.id}")
                                    onOpenTrack(link.id)
                                }
                                is SpotifyLink.Album -> onAlbumClick(link.id, "", emptyList())
                                is SpotifyLink.Playlist -> onPlaylistClick(link.id, "", emptyList())
                                is SpotifyLink.Artist -> onArtistClick(link.id)
                                null -> android.widget.Toast.makeText(context, R.string.paste_no_spotify_link, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Paste Spotify link", tint = Color.White)
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

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(SearchFilter.entries) { filter ->
                    val isSelected = activeFilter == filter
                    val filterName = when (filter) {
                        SearchFilter.ALL -> stringResource(R.string.search_filter_all)
                        SearchFilter.TRACKS -> stringResource(R.string.search_filter_tracks)
                        SearchFilter.ALBUMS -> stringResource(R.string.search_filter_albums)
                        SearchFilter.ARTISTS -> stringResource(R.string.search_filter_artists)
                        SearchFilter.PLAYLISTS -> stringResource(R.string.search_filter_playlists)
                    }
                    
                    Surface(
                        color = if (isSelected) spotifyGreen else Color(0xFF2A2A2A),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable { activeFilter = filter }
                    ) {
                        Text(
                            text = filterName,
                            color = if (isSelected) Color.Black else Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                    text = stringResource(R.string.search_empty_prompt),
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (searchResults != null) {
                val results = searchResults!!
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomPadding)
                ) {
                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.TRACKS) {
                        if (results.tracks.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.search_header_tracks)) }
                            items(results.tracks) { track ->
                                val trackId = track.id ?: ""
                                val isLiked = spotifyRepo.isTrackLiked(trackId)
                                
                                val dismissState = rememberSwipeToDismissBoxState(
                                    positionalThreshold = { it * 0.4f }
                                )

                                LaunchedEffect(dismissState.currentValue) {
                                    if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
                                        onAddToQueue(track)
                                        android.widget.Toast.makeText(context, "Added to queue", android.widget.Toast.LENGTH_SHORT).show()
                                        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                                    }
                                }

                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromEndToStart = false,
                                    backgroundContent = {
                                        val color by androidx.compose.animation.animateColorAsState(
                                            if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) Color(0xFF1DB954) else Color.Transparent,
                                            animationSpec = androidx.compose.animation.core.tween(300),
                                            label = "SwipeBackgroundColor"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(color)
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                                    contentDescription = "Add to Queue",
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                    },
                                    content = {
                                        SearchResultRow(
                                            title = track.name,
                                            subtitle = stringResource(R.string.search_subtitle_track, track.artists.joinToString(", ") { it.name }),
                                            imageUrl = track.album?.images?.maxByOrNull { it.width ?: 0 }?.url,
                                            onClick = { onTrackClick(track) },
                                            isLiked = isLiked,
                                            isCurrentTrack = track.id == currentTrackId,
                                            onLikeToggle = {
                                                coroutineScope.launch {
                                                    spotifyRepo.toggleLikeTrack(track)
                                                }
                                            },
                                            onMoreClick = {
                                                selectedTrackForMenu = track
                                            },
                                            modifier = Modifier.background(Color(0xFF121212))
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.ALBUMS) {
                        if (results.albums.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.search_header_albums)) }
                            items(results.albums) { album ->
                                SearchResultRow(
                                    title = album.name,
                                    subtitle = stringResource(R.string.search_subtitle_album, album.artists.joinToString(", ") { it.name }),
                                    imageUrl = album.images.maxByOrNull { it.width ?: 0 }?.url,
                                    onClick = { onAlbumClick(album.id, album.name, album.images) }
                                )
                            }
                        }
                    }

                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.PLAYLISTS) {
                        if (results.playlists.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.search_header_playlists)) }
                            items(results.playlists) { playlist ->
                                SearchResultRow(
                                    title = playlist.name,
                                    subtitle = stringResource(R.string.search_subtitle_playlist, playlist.owner?.name ?: "Spotify"),
                                    imageUrl = playlist.images.maxByOrNull { it.width ?: 0 }?.url,
                                    onClick = { onPlaylistClick(playlist.id, playlist.name, playlist.images) }
                                )
                            }
                        }
                    }

                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.ARTISTS) {
                        if (results.artists.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.search_header_artists)) }
                            items(results.artists) { artist ->
                                SearchResultRow(
                                    title = artist.name,
                                    subtitle = stringResource(R.string.search_subtitle_artist),
                                    imageUrl = artist.images.maxByOrNull { it.width ?: 0 }?.url,
                                    isCircle = true,
                                    onClick = { onArtistClick(artist.id) }
                                )
                            }
                        }
                    }
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
            onGoToRadio = onGoToRadio?.let { cb -> {
                val t = selectedTrackForMenu!!
                cb(t.id ?: "", t.name)
                selectedTrackForMenu = null
            } }
        )
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCircle: Boolean = false,
    isLiked: Boolean = false,
    isCurrentTrack: Boolean = false,
    onLikeToggle: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
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
                color = if (isCurrentTrack) Color(0xFF1DB954) else Color.White,
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
            SpotifyLikeButton(
                isLiked = isLiked,
                onClick = onLikeToggle,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        if (onMoreClick != null) {
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.padding(start = 4.dp).size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = Color.LightGray
                )
            }
        }
    }
}


