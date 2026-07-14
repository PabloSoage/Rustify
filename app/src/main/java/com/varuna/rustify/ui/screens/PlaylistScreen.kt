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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import com.varuna.rustify.bridge.FullPlaylist
import com.varuna.rustify.bridge.SimplePlaylist
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.EntityOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.LocalPlaylistCover
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.TrackRowItem
import com.varuna.rustify.util.bouncingMarquee
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: String,
    playlistName: String,
    playlistImages: List<SpotifyImage>,
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
    val context = androidx.compose.ui.platform.LocalContext.current
    // E30: playlist local. El id ya viene resuelto por el enrutado (prefijo "localpl:").
    val isLocal = playlistId.startsWith("localpl:")
    val localPlaylist = if (isLocal) spotifyRepo.localPlaylists.firstOrNull { it.id == playlistId } else null
    var playlistDetails by remember { mutableStateOf<FullPlaylist?>(null) }
    var tracks by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }
    var showEntityMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteLocalDialog by remember { mutableStateOf(false) }
    var showRenameLocalDialog by remember { mutableStateOf(false) }
    var meId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Pagination state
    var offset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { meId = spotifyRepo.getMe().id } }

    LaunchedEffect(playlistId) {
        isLoading = true
        errorMessage = null
        try {
            if (playlistId.startsWith("localpl:")) {
                // E30: playlist puramente local — resolve ids "local:" contra localTracks.
                // Always resolve fresh: the cache is invalidated on add/remove, but a stale
                // empty snapshot could otherwise freeze the screen at "No tracks found".
                tracks = spotifyRepo.localPlaylistTracks(playlistId)
                SpotifyRepository.localPlaylistTracksCache[playlistId] = tracks
                hasMore = false
            } else {
                playlistDetails = spotifyRepo.getPlaylist(playlistId)
                val response = spotifyRepo.getPlaylistTracks(playlistId, limit = 50, offset = 0)
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

    val primaryImageUrl = playlistImages.maxByOrNull { it.width ?: 0 }?.url
        ?: playlistDetails?.images?.maxByOrNull { it.width ?: 0 }?.url

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
                                    if (playlistId.startsWith("localpl:")) {
                                        tracks = spotifyRepo.localPlaylistTracks(playlistId)
                                        hasMore = false
                                    } else {
                                        playlistDetails = spotifyRepo.getPlaylist(playlistId)
                                        val response = spotifyRepo.getPlaylistTracks(playlistId, limit = 50, offset = 0)
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
                            if (isLocal) {
                                // E30: mosaico 2x2 con las carátulas de las primeras 4 pistas locales.
                                LocalPlaylistCover(
                                    tracks = tracks,
                                    modifier = Modifier
                                        .size(200.dp)
                                        .shadow(16.dp, RoundedCornerShape(12.dp)),
                                    shape = RoundedCornerShape(12.dp),
                                    placeholderFontSize = 64.sp
                                )
                            } else {
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
                                                text = playlistName.take(1).uppercase(),
                                                style = MaterialTheme.typography.headlineLarge,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = if (isLocal) "LOCAL PLAYLIST" else "PLAYLIST",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp
                                    ),
                                    color = spotifyGreen
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isLocal)
                                        (spotifyRepo.localPlaylists.firstOrNull { it.id == playlistId }?.name
                                            ?: localPlaylist?.name ?: playlistName)
                                    else playlistName,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                    modifier = Modifier.bouncingMarquee()
                                )
                                if (isLocal) {
                                    // E30: sin "Spotify Playlist"/owner "Spotify". Subtítulo propio.
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val count = tracks.size
                                    Text(
                                        text = "Playlist local · $count ${if (count == 1) "canción" else "canciones"}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.LightGray
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))

                                    val descriptionText = playlistDetails?.description ?: "Spotify Playlist"

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
                                        val subtitleText = playlistDetails?.owner?.name ?: "Spotify"
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
                                            text = "${playlistDetails?.totalTracks ?: tracks.size} tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
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
                                Text(
                                    text = if (isLocal)
                                        stringResource(R.string.local_playlist_empty)
                                    else "No tracks found",
                                    color = Color.Gray
                                )
                            }
                        }
                    } else {
                        itemsIndexed(tracks, key = { index, track -> track.id ?: "local_${index}_${track.name.hashCode()}" }) { index, track ->
                            // Request more data when we reach near the end of the list
                            if (index >= tracks.size - 5 && !isLoadingMore && hasMore) {
                                LaunchedEffect(index) {
                                    isLoadingMore = true
                                    try {
                                        val response = spotifyRepo.getPlaylistTracks(playlistId, limit = 50, offset = offset)
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
                            // E30: los tracks locales usan favoritos locales, no los "liked" de Spotify.
                            var localFav by remember(trackId) { mutableStateOf(spotifyRepo.isLocalFavorite(trackId)) }
                            val isLiked = if (isLocal) localFav else spotifyRepo.isTrackLiked(trackId)

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
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) Color(0xFF1DB954) else Color.Transparent)
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
                                        fallbackCoverUrl = playlistImages?.firstOrNull()?.url,
                                        onClick = { onTrackClick(tracks, index) },
                                        isLiked = isLiked,
                                        isCurrentTrack = track.id == currentTrackId,
                                        onLikeToggle = {
                                            if (isLocal) {
                                                spotifyRepo.toggleLocalFavorite(trackId)
                                                localFav = !localFav
                                            } else {
                                                coroutineScope.launch {
                                                    spotifyRepo.toggleLikeTrack(track)
                                                }
                                            }
                                        },
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
                    if (isLocal) {
                        IconButton(onClick = { showRenameLocalDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.local_playlist_rename), tint = Color.White)
                        }
                        IconButton(onClick = { showDeleteLocalDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.local_playlist_delete), tint = Color.White)
                        }
                    }
                    // Edit/Follow son de Spotify: solo se muestran cuando hay playlistDetails
                    // (null para playlists locales), por lo que quedan ocultos automáticamente.
                    if (!isLocal && meId != null && playlistDetails?.owner?.id == meId) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit playlist", tint = Color.White)
                        }
                    }
                    playlistDetails?.let { pl ->
                        val followed = spotifyRepo.isPlaylistFollowed(pl.id)
                        IconButton(onClick = {
                            coroutineScope.launch {
                                spotifyRepo.toggleFollowPlaylist(
                                    SimplePlaylist(pl.id, pl.name, pl.description, pl.images, pl.externalUri, pl.owner, pl.totalTracks)
                                )
                            }
                        }) {
                            Icon(
                                imageVector = if (followed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Follow playlist",
                                tint = if (followed) Color(0xFF1DB954) else Color.White
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

    if (showEditDialog) {
        val pl = playlistDetails
        var editName by remember(pl?.id) { mutableStateOf(pl?.name ?: "") }
        var editDesc by remember(pl?.id) { mutableStateOf(pl?.description ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Edit playlist", color = Color.White) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text("Description") }
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val id = pl?.id
                    showEditDialog = false
                    if (id != null && editName.isNotBlank()) {
                        coroutineScope.launch {
                            val res = spotifyRepo.updatePlaylist(id, editName, editDesc)
                            if (res.success) {
                                playlistDetails = playlistDetails?.copy(name = editName, description = editDesc)
                            }
                        }
                    }
                }) { Text("Save", color = Color(0xFF1DB954)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showEntityMenu) {
        EntityOptionsMenuBottomSheet(
            entityType = "playlist",
            entityId = playlistId,
            entityName = playlistName,
            primaryArtistId = null,
            tracks = tracks,
            onDismiss = { showEntityMenu = false },
            onGoToArtist = onArtistClick
        )
    }

    if (showRenameLocalDialog) {
        val currentName = spotifyRepo.localPlaylists.firstOrNull { it.id == playlistId }?.name
            ?: (localPlaylist?.name ?: playlistName)
        var renameText by remember(playlistId, showRenameLocalDialog) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showRenameLocalDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(stringResource(R.string.local_playlist_rename), color = Color.White) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.local_playlist_name_hint)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameLocalDialog = false
                        spotifyRepo.renameLocalPlaylist(playlistId, renameText.trim())
                    },
                    enabled = renameText.isNotBlank()
                ) { Text(stringResource(R.string.local_playlist_create_confirm), color = Color(0xFF1DB954)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameLocalDialog = false }) {
                    Text(stringResource(android.R.string.cancel), color = Color.Gray)
                }
            }
        )
    }

    if (showDeleteLocalDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteLocalDialog = false },
            title = { Text(stringResource(R.string.local_playlist_delete), color = Color.White) },
            text = { Text(stringResource(R.string.local_playlist_delete_msg), color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteLocalDialog = false
                    spotifyRepo.deleteLocalPlaylist(playlistId)
                    android.widget.Toast.makeText(context, R.string.local_playlist_deleted, android.widget.Toast.LENGTH_SHORT).show()
                    onBackClick()
                }) { Text(stringResource(R.string.local_playlist_delete_confirm), color = Color(0xFFCC2200)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteLocalDialog = false }) {
                    Text(stringResource(android.R.string.cancel), color = Color.Gray)
                }
            }
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
            onRemoveFromPlaylist = {
                val trackToRemove = selectedTrackForMenu!!
                coroutineScope.launch {
                    trackToRemove.id?.let { tid ->
                        if (playlistId.startsWith("localpl:")) {
                            spotifyRepo.removeFromLocalPlaylist(playlistId, tid)
                            tracks = spotifyRepo.localPlaylistTracks(playlistId)
                            hasMore = false
                        } else {
                            val res = spotifyRepo.removeTracksFromPlaylist(playlistId, listOf(tid))
                            if (res.success) {
                                val response = spotifyRepo.getPlaylistTracks(playlistId, limit = 50, offset = 0)
                                tracks = response.items
                                offset = tracks.size
                                hasMore = response.hasMore
                            }
                        }
                    }
                }
                selectedTrackForMenu = null
            }
        )
    }
}

