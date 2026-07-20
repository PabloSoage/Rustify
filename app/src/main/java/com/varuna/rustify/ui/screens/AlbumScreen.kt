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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullAlbum
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.EntityOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.TrackRowItem
import com.varuna.rustify.util.bouncingMarquee
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
    onGoToRadio: ((String, String) -> Unit)? = null,
    onShufflePlay: (List<FullTrack>) -> Unit = {},
    modifier: Modifier = Modifier,
    currentTrackId: String? = null
) {
    var albumDetails by remember { mutableStateOf<FullAlbum?>(null) }
    var tracks by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }
    var showEntityMenu by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Pagination state
    var offset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(albumId) {
        isLoading = true
        errorMessage = null
        try {
            // Support local albums (navigated from LibraryLocalMusic)
            if (albumId.startsWith("local_album:")) {
                var localTracks = SpotifyRepository.localAlbumTracks[albumId]
                if (localTracks.isNullOrEmpty()) {
                    localTracks = spotifyRepo.localTracks.filter { it.album?.name == albumName }
                    SpotifyRepository.localAlbumTracks[albumId] = localTracks
                }
                val coverUri = localTracks.firstOrNull()?.externalUri
                val images = if (coverUri.isNullOrBlank()) emptyList() else listOf(SpotifyImage(coverUri, null, null))
                albumDetails = FullAlbum(
                    id = albumId,
                    name = albumName,
                    externalUri = "",
                    releaseDate = null,
                    releaseDatePrecision = null,
                    images = images,
                    artists = localTracks.firstOrNull()?.artists ?: emptyList(),
                    albumType = "album",
                    totalTracks = localTracks.size,
                    recordLabel = null,
                    genres = emptyList()
                )
                tracks = localTracks
                hasMore = false
                offset = localTracks.size
            } else {
                albumDetails = spotifyRepo.getAlbum(albumId)
                val response = spotifyRepo.getAlbumTracks(albumId, limit = 50, offset = 0)
                tracks = response.items
                hasMore = response.hasMore
                offset = tracks.size
            }

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
                                    if (albumId.startsWith("local_album:")) {
                                        var localTracks = SpotifyRepository.localAlbumTracks[albumId]
                                        if (localTracks.isNullOrEmpty()) {
                                            localTracks = spotifyRepo.localTracks.filter { it.album?.name == albumName }
                                            SpotifyRepository.localAlbumTracks[albumId] = localTracks
                                        }
                                        albumDetails = FullAlbum(
                                            id = albumId,
                                            name = albumName,
                                            externalUri = "",
                                            releaseDate = null,
                                            releaseDatePrecision = null,
                                            images = emptyList(),
                                            artists = localTracks.firstOrNull()?.artists ?: emptyList(),
                                            albumType = "album",
                                            totalTracks = localTracks.size,
                                            recordLabel = null,
                                            genres = emptyList()
                                        )
                                        tracks = localTracks
                                        hasMore = false
                                        offset = localTracks.size
                                    } else {
                                        albumDetails = spotifyRepo.getAlbum(albumId)
                                        val response = spotifyRepo.getAlbumTracks(albumId, limit = 50, offset = 0)
                                        tracks = response.items
                                        hasMore = response.hasMore
                                        offset = tracks.size
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
                        Text(stringResource(R.string.general_retry), color = Color.White)
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
                                    modifier = Modifier.bouncingMarquee()
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                val descriptionText = "Album by " + (albumDetails?.artists?.joinToString(", ") { it.name } ?: "Unknown Artist")
                                Text(
                                    text = descriptionText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.LightGray,
                                    maxLines = 1,
                                    modifier = Modifier.bouncingMarquee()
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

                            Surface(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clickable {
                                        if (tracks.isNotEmpty()) {
                                            onShufflePlay(tracks)
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
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = stringResource(R.string.cd_shuffle_play),
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
                        itemsIndexed(tracks, key = { index, track -> track.id ?: "local_${index}_${track.name.hashCode()}" }) { index, track ->
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

                            val dismissState = rememberSwipeToDismissBoxState(
                                positionalThreshold = { it * 0.4f }
                            )

                            LaunchedEffect(dismissState.currentValue) {
                                if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
                                    onAddToQueue(track)
                                    android.widget.Toast.makeText(context, context.getString(R.string.added_to_queue), android.widget.Toast.LENGTH_SHORT).show()
                                    dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                                }
                            }
                            val trackId = track.id ?: ""
                            val isLiked = spotifyRepo.isTrackLiked(trackId)
                            
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromEndToStart = false,
                                backgroundContent = {
                                    val color by androidx.compose.animation.animateColorAsState(
                                        if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) spotifyGreen else Color.Transparent,
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
                                    TrackRowItem(
                                        index = index + 1,
                                        track = track,
                                        fallbackCoverUrl = primaryImageUrl,
                                        onClick = { onTrackClick(tracks, index) },
                                        isLiked = if (albumId.startsWith("local_album:")) false else isLiked,
                                        isCurrentTrack = track.id == currentTrackId,
                                        onLikeToggle = if (albumId.startsWith("local_album:")) null else { {
                                            coroutineScope.launch {
                                                spotifyRepo.toggleLikeTrack(track)
                                            }
                                        } },
                                        onMoreClick = { selectedTrackForMenu = track }
                                    )
                                }
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back), tint = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    albumDetails?.let { album ->
                        val saved = spotifyRepo.isAlbumSaved(album.id)
                        IconButton(onClick = { coroutineScope.launch { spotifyRepo.toggleSaveAlbum(album) } }) {
                            Icon(
                                imageVector = if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Save album",
                                tint = if (saved) Color(0xFF1DB954) else Color.White
                            )
                        }
                    }
                    IconButton(onClick = { showEntityMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options), tint = Color.White)
                    }
                }
            }
        }
    }

    if (showEntityMenu) {
        EntityOptionsMenuBottomSheet(
            entityType = "album",
            entityId = albumId,
            entityName = albumName,
            primaryArtistId = albumDetails?.artists?.firstOrNull()?.id,
            tracks = tracks,
            onDismiss = { showEntityMenu = false },
            onGoToArtist = onArtistClick
        )
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
            } },
            onRemoveFromPlaylist = null
        )
    }
}


