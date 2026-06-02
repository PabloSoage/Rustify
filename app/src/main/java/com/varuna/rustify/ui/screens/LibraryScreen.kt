package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.varuna.rustify.ui.components.TrackRowItem
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.varuna.rustify.bridge.*
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures

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
    onArtistClick: (String) -> Unit,
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
                LibraryTab.ARTISTS -> LibraryArtists(spotifyRepo, onArtistClick)
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
        itemContent = { _, playlist ->
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
        itemContent = { _, album ->
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
    spotifyRepo: SpotifyRepository,
    onArtistClick: (String) -> Unit
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
        itemContent = { _, artist ->
            SearchResultRow(
                title = artist.name,
                subtitle = "Artist",
                imageUrl = artist.images.maxByOrNull { it.width ?: 0 }?.url,
                isCircle = true,
                onClick = { onArtistClick(artist.id) }
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
    val tracks = spotifyRepo.likedTracks
    val isSyncing = spotifyRepo.isSyncingLikedTracks
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (tracks.isEmpty() && isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF1DB954)
            )
        } else if (tracks.isEmpty()) {
            Text(
                text = "No liked tracks found.",
                color = Color.Gray,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp), // Bottom nav padding
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(tracks, key = { _, track -> track.id ?: "" }) { index, track ->
                        val trackId = track.id ?: ""
                        val isLiked = spotifyRepo.isTrackLiked(trackId)
                        TrackRowItem(
                            index = index + 1,
                            track = track,
                            fallbackCoverUrl = null,
                            onClick = { onTrackClick(track) },
                            isLiked = isLiked,
                            onLikeToggle = {
                                coroutineScope.launch {
                                    spotifyRepo.toggleLikeTrack(track)
                                }
                            }
                        )
                    }
                }

                // Custom scrollbar with date tooltip
                VerticalScrollbarWithTooltip(
                    lazyListState = lazyListState,
                    itemsCount = tracks.size,
                    getDateForItem = { index ->
                        val track = tracks.getOrNull(index)
                        formatAddedAt(track?.addedAt)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 8.dp, bottom = 100.dp)
                )
            }
        }
    }
}

@Composable
fun <T> LibraryContentList(
    isLoading: Boolean,
    errorMessage: String?,
    items: List<T>?,
    onRetry: () -> Unit,
    itemContent: @Composable (Int, T) -> Unit,
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
                    itemsIndexed(items) { index, item ->
                        itemContent(index, item)
                    }
                }
            }
        }
    }
}

fun formatAddedAt(addedAt: String?): String {
    if (addedAt.isNullOrEmpty()) return ""
    try {
        if (addedAt.length >= 7) {
            val year = addedAt.substring(0, 4)
            val monthNum = addedAt.substring(5, 7)
            val monthName = when (monthNum) {
                "01" -> "Jan"
                "02" -> "Feb"
                "03" -> "Mar"
                "04" -> "Apr"
                "05" -> "May"
                "06" -> "Jun"
                "07" -> "Jul"
                "08" -> "Aug"
                "09" -> "Sep"
                "10" -> "Oct"
                "11" -> "Nov"
                "12" -> "Dec"
                else -> ""
            }
            return if (monthName.isNotEmpty()) "$monthName $year" else year
        }
    } catch (e: Exception) {
        // ignore
    }
    return ""
}

@Composable
fun VerticalScrollbarWithTooltip(
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    itemsCount: Int,
    getDateForItem: (Int) -> String,
    modifier: Modifier = Modifier
) {
    if (itemsCount <= 0) return

    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    var scrollbarHeight by remember { mutableStateOf(0f) }

    val coroutineScope = rememberCoroutineScope()

    val firstVisibleIndex = lazyListState.firstVisibleItemIndex
    val scrollFraction = if (itemsCount > 1) firstVisibleIndex.toFloat() / (itemsCount - 1) else 0f

    val dragFraction = if (scrollbarHeight > 0) (dragOffset / scrollbarHeight).coerceIn(0f, 1f) else 0f
    val activeIndex = if (isDragging) {
        (dragFraction * (itemsCount - 1)).toInt().coerceIn(0, itemsCount - 1)
    } else {
        firstVisibleIndex.coerceIn(0, itemsCount - 1)
    }
    val tooltipText = remember(activeIndex, isDragging) { getDateForItem(activeIndex) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(50.dp)
            .onGloballyPositioned { coordinates ->
                scrollbarHeight = coordinates.size.height.toFloat()
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragOffset = offset.y.coerceIn(0f, scrollbarHeight)
                        val targetIndex = (dragFraction * (itemsCount - 1)).toInt().coerceIn(0, itemsCount - 1)
                        coroutineScope.launch {
                            lazyListState.scrollToItem(targetIndex)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset = (dragOffset + dragAmount.y).coerceIn(0f, scrollbarHeight)
                        val targetIndex = (dragFraction * (itemsCount - 1)).toInt().coerceIn(0, itemsCount - 1)
                        coroutineScope.launch {
                            lazyListState.scrollToItem(targetIndex)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .fillMaxHeight()
                .width(2.dp)
                .background(Color.Gray.copy(alpha = 0.2f))
        )

        val handleSize = 40.dp
        val density = LocalDensity.current
        val handleSizePx = with(density) { handleSize.toPx() }
        
        val handleOffset = if (!isDragging) {
            val maxOffset = scrollbarHeight - handleSizePx
            (scrollFraction * maxOffset).coerceAtLeast(0f)
        } else {
            (dragOffset - handleSizePx / 2).coerceIn(0f, scrollbarHeight - handleSizePx)
        }

        val handleOffsetDp = with(density) { handleOffset.toDp() }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 2.dp)
                .offset(y = handleOffsetDp)
                .size(width = 6.dp, height = handleSize)
                .background(
                    color = if (isDragging) Color(0xFF1DB954) else Color.Gray.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(3.dp)
                )
        )

        if (isDragging && tooltipText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = (-16).dp,
                        y = handleOffsetDp + (handleSize / 2) - 16.dp
                    )
                    .background(Color(0xFF2E2E2E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = tooltipText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
