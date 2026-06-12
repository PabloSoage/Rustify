@file:Suppress("SpellCheckingInspection")

package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.FullAlbum
import com.varuna.rustify.bridge.FullArtist
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SimplePlaylist
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.TrackRowItem
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.varuna.rustify.R
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.animation.animateColorAsState
import kotlinx.coroutines.delay

enum class LibraryTab {
    PLAYLISTS,
    ALBUMS,
    ARTISTS,
    TRACKS,
    LOCAL
}

@Composable
fun LibraryScreen(
    spotifyRepo: SpotifyRepository,
    onPlaylistClick: (String, String, List<SpotifyImage>) -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAddToQueue: (FullTrack) -> Unit,
    onGoToQueue: () -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkBackground = Color(0xFF121212)
    val spotifyGreen = Color(0xFF1DB954)
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.PLAYLISTS) }

    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
    val enableLocalMusic = prefs.getBoolean("enable_local_music", true)
    
    val tabs = remember(enableLocalMusic) {
        if (enableLocalMusic) LibraryTab.entries else LibraryTab.entries.filter { it != LibraryTab.LOCAL }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(darkBackground)
    ) {
        // Header
        Text(
            text = androidx.compose.ui.res.stringResource(com.varuna.rustify.R.string.nav_library),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White
            ),
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
        )

        PrimaryScrollableTabRow(
            selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
            containerColor = darkBackground,
            contentColor = Color.White,
            edgePadding = 16.dp,
            indicator = {
                val index = tabs.indexOf(selectedTab).coerceAtLeast(0)
                TabRowDefaults.PrimaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(index),
                    color = spotifyGreen
                )
            }
        ) {
            tabs.forEachIndexed { index, tab ->
                val title = when (tab) {
                    LibraryTab.PLAYLISTS -> androidx.compose.ui.res.stringResource(com.varuna.rustify.R.string.library_tab_playlists)
                    LibraryTab.ALBUMS -> androidx.compose.ui.res.stringResource(com.varuna.rustify.R.string.library_tab_albums)
                    LibraryTab.ARTISTS -> androidx.compose.ui.res.stringResource(com.varuna.rustify.R.string.library_tab_artists)
                    LibraryTab.LOCAL -> androidx.compose.ui.res.stringResource(com.varuna.rustify.R.string.library_tab_local_music)
                    LibraryTab.TRACKS -> androidx.compose.ui.res.stringResource(com.varuna.rustify.R.string.library_tab_liked_tracks)
                }
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    selectedContentColor = spotifyGreen,
                    unselectedContentColor = Color.LightGray
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                LibraryTab.PLAYLISTS -> LibraryPlaylists(spotifyRepo, onPlaylistClick)
                LibraryTab.ALBUMS -> LibraryAlbums(spotifyRepo, onAlbumClick)
                LibraryTab.ARTISTS -> LibraryArtists(spotifyRepo, onArtistClick)
                LibraryTab.TRACKS -> LibraryTracks(
                    spotifyRepo = spotifyRepo,
                    onTrackClick = onTrackClick,
                    onAddToQueue = onAddToQueue,
                    onGoToQueue = onGoToQueue,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick
                )
                LibraryTab.LOCAL -> LibraryLocalMusic(
                    onTrackClick = onTrackClick,
                    onAddToQueue = onAddToQueue,
                    onGoToQueue = onGoToQueue
                )
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
                subtitle = playlist.owner?.name ?: "Unknown",
                imageUrl = playlist.images?.firstOrNull()?.url,
                onClick = { onPlaylistClick(playlist.id, playlist.name, playlist.images ?: emptyList()) }
            )
        },
        emptyMessage = "No playlists found.",
        filterPredicate = { playlist, query ->
            playlist.name.contains(query, ignoreCase = true) || 
            (playlist.owner?.name?.contains(query, ignoreCase = true) == true)
        }
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
                subtitle = album.artists.joinToString(", ") { it.name },
                imageUrl = album.images?.firstOrNull()?.url,
                onClick = { onAlbumClick(album.id, album.name, album.images ?: emptyList()) }
            )
        },
        emptyMessage = "No albums found.",
        filterPredicate = { album, query ->
            album.name.contains(query, ignoreCase = true) || 
            album.artists.any { it.name.contains(query, ignoreCase = true) }
        }
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
                imageUrl = artist.images?.firstOrNull()?.url,
                isCircle = true,
                onClick = { onArtistClick(artist.id) }
            )
        },
        emptyMessage = "No artists found.",
        filterPredicate = { artist, query ->
            artist.name.contains(query, ignoreCase = true)
        }
    )
}

@Composable
fun LibraryTracks(
    spotifyRepo: SpotifyRepository,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAddToQueue: (FullTrack) -> Unit,
    onGoToQueue: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit
) {
    val tracks = spotifyRepo.likedTracks
    val isSyncing = spotifyRepo.isSyncingLikedTracks
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var isScrollbarDragging by remember { mutableStateOf(false) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val bottomPadding = if (isLandscape) 16.dp else 100.dp

    var searchQuery by remember { mutableStateOf("") }
    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) tracks
        else tracks.filter { 
            it.name.contains(searchQuery, ignoreCase = true) || 
            it.artists.any { artist -> artist.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp)),
            placeholder = { Text(stringResource(R.string.search_placeholder), color = Color.Gray) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF242424),
                unfocusedContainerColor = Color(0xFF242424),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Color(0xFF1DB954)
            ),
            singleLine = true
        )

        if (tracks.isEmpty() && isSyncing) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF1DB954)
                )
            }
        } else if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "No liked tracks found.",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding), // Bottom nav padding
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(filteredTracks, key = { _, track -> track.id ?: "" }) { index, track ->
                        val trackId = track.id ?: ""
                        val isLiked = spotifyRepo.isTrackLiked(trackId)
                        
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { dismissValue ->
                                if (dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                                    onAddToQueue(track)
                                    coroutineScope.launch {
                                        delay(200)
                                        // No action needed to reset, just returning false bounces it back automatically.
                                    }
                                }
                                false // Always return false so the item bounces back and is never actually dismissed
                            }
                        )

                        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val color by animateColorAsState(
                                    if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) Color(0xFF1DB954) else Color.Transparent,
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
                            enableDismissFromEndToStart = false,
                            content = {
                                TrackRowItem(
                                    index = index + 1,
                                    track = track,
                                    fallbackCoverUrl = null,
                                    onClick = { onTrackClick(filteredTracks, index) },
                                    isLiked = isLiked,
                                    onLikeToggle = {
                                        coroutineScope.launch {
                                            spotifyRepo.toggleLikeTrack(track)
                                        }
                                    },
                                    isScrollbarDragging = isScrollbarDragging,
                                    onMoreClick = { selectedTrackForMenu = track },
                                    modifier = Modifier.background(Color(0xFF121212)) // Ensure background is solid during swipe
                                )
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
                    onDragStateChanged = { isScrollbarDragging = it },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 8.dp, bottom = bottomPadding)
                )
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

@Composable
fun LibraryLocalMusic(
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAddToQueue: (FullTrack) -> Unit,
    onGoToQueue: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var tracks by remember { mutableStateOf<List<FullTrack>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }

    val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
    val localMusicDirs = prefs.getStringSet("local_music_directories", emptySet()) ?: emptySet()

    LaunchedEffect(localMusicDirs) {
        if (localMusicDirs.isNotEmpty() && tracks == null) {
            isLoading = true
            errorMessage = null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val localTracks = mutableListOf<FullTrack>()
                    val retriever = android.media.MediaMetadataRetriever()
                    
                    for (dirUriStr in localMusicDirs) {
                        try {
                            val treeUri = android.net.Uri.parse(dirUriStr)
                            val dirFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                            dirFile?.listFiles()?.forEach { file ->
                                val mime = file.type
                                if (mime?.startsWith("audio/") == true) {
                                    try {
                                        retriever.setDataSource(context, file.uri)
                                        val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.name ?: "Unknown"
                                        val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                                        val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"
                                        val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                        val durationMs = durationStr?.toLongOrNull() ?: 0L
                                        
                                        localTracks.add(
                                            FullTrack(
                                                id = "local:${file.uri}",
                                                name = title,
                                                artists = listOf(com.varuna.rustify.bridge.SimpleArtist("local_artist", artist, "", emptyList())),
                                                album = com.varuna.rustify.bridge.SimpleAlbum("local_album", album, "", null, null, emptyList(), emptyList(), null),
                                                durationMs = durationMs.toInt(),
                                                explicit = false,
                                                isrc = "",
                                                addedAt = "",
                                                externalUri = ""
                                            )
                                        )
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    retriever.release()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        tracks = localTracks
                        isLoading = false
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        errorMessage = e.message
                        isLoading = false
                    }
                }
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredTracks = remember(tracks, searchQuery) {
        if (tracks == null) null
        else if (searchQuery.isBlank()) tracks
        else tracks!!.filter { 
            it.name.contains(searchQuery, ignoreCase = true) || 
            it.artists.any { artist -> artist.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (localMusicDirs.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp)),
                placeholder = { Text(stringResource(R.string.search_placeholder), color = Color.Gray) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF242424),
                    unfocusedContainerColor = Color(0xFF242424),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color(0xFF1DB954)
                ),
                singleLine = true
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (localMusicDirs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Local Music not configured", color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            // Let the user know they need to configure it in settings
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                    ) {
                        Text("Configure in Settings", color = Color.White)
                    }
                }
            } else if (isLoading && tracks == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF1DB954)
                )
            } else if (errorMessage != null && tracks == null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
            } else if (filteredTracks != null) {
                if (filteredTracks.isEmpty()) {
                    Text(
                        text = "No local tracks found.",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val bottomPadding = if (isLandscape) 16.dp else 100.dp
                    
                    LazyColumn(
                        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(filteredTracks) { index, track ->
                            TrackRowItem(
                                index = index + 1,
                                track = track,
                                fallbackCoverUrl = null,
                                onClick = { onTrackClick(filteredTracks, index) },
                                isLiked = false,
                                onLikeToggle = {},
                                onMoreClick = { selectedTrackForMenu = track }
                            )
                        }
                    }
                }
            }
        }
    }
        
        if (selectedTrackForMenu != null) {
            TrackOptionsMenuBottomSheet(
                track = selectedTrackForMenu!!,
                spotifyRepo = SpotifyRepository(context), // Dummy repo for options, won't be used for local
                onDismiss = { selectedTrackForMenu = null },
                onAddToQueue = {
                    onAddToQueue(selectedTrackForMenu!!)
                    selectedTrackForMenu = null
                },
                onGoToQueue = {
                    onGoToQueue()
                    selectedTrackForMenu = null
                },
                onGoToAlbum = { _, _, _ -> },
                onGoToArtist = { _ -> },
                onRemoveFromPlaylist = null
            )
        }
    }

@Composable
fun <T> LibraryContentList(
    isLoading: Boolean,
    errorMessage: String?,
    items: List<T>?,
    onRetry: () -> Unit,
    itemContent: @Composable (Int, T) -> Unit,
    emptyMessage: String,
    filterPredicate: ((T, String) -> Boolean)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredItems = remember(items, searchQuery) {
        if (items == null) null
        else if (searchQuery.isBlank() || filterPredicate == null) items
        else items.filter { filterPredicate(it, searchQuery) }
    }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val bottomPadding = if (isLandscape) 16.dp else 100.dp
    
    Column(modifier = Modifier.fillMaxSize()) {
        if (filterPredicate != null) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp)),
                placeholder = { Text(stringResource(R.string.search_placeholder), color = Color.Gray) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF242424),
                    unfocusedContainerColor = Color(0xFF242424),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color(0xFF1DB954)
                ),
                singleLine = true
            )
        }
        
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
        } else if (filteredItems != null) {
            if (filteredItems.isEmpty()) {
                Text(
                    text = emptyMessage,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding)
                ) {
                    itemsIndexed(filteredItems) { index, item ->
                        itemContent(index, item)
                    }
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
    } catch (_: Exception) {
        // ignore
    }
    return ""
}

@Composable
fun VerticalScrollbarWithTooltip(
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    itemsCount: Int,
    getDateForItem: (Int) -> String,
    onDragStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (itemsCount <= 0) return

    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var scrollbarHeight by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isDragging) {
        onDragStateChanged(isDragging)
    }

    val firstVisibleIndex by remember { derivedStateOf { lazyListState.firstVisibleItemIndex } }
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
                        val newDragOffset = offset.y.coerceIn(0f, scrollbarHeight)
                        dragOffset = newDragOffset
                        val currentFraction = if (scrollbarHeight > 0) newDragOffset / scrollbarHeight else 0f
                        val targetIndex = (currentFraction * (itemsCount - 1)).toInt().coerceIn(0, itemsCount - 1)
                        scrollJob?.cancel()
                        scrollJob = coroutineScope.launch {
                            lazyListState.scrollToItem(targetIndex)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newDragOffset = (dragOffset + dragAmount.y).coerceIn(0f, scrollbarHeight)
                        dragOffset = newDragOffset
                        val currentFraction = if (scrollbarHeight > 0) newDragOffset / scrollbarHeight else 0f
                        val targetIndex = (currentFraction * (itemsCount - 1)).toInt().coerceIn(0, itemsCount - 1)
                        scrollJob?.cancel()
                        scrollJob = coroutineScope.launch {
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
