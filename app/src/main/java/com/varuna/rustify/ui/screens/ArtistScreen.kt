package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullArtist
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SimpleAlbum
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.EntityOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.PlaylistItemCard
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.TrackRowItem
import com.varuna.rustify.util.bouncingMarquee
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    artistId: String,
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
    var artistDetails by remember { mutableStateOf<FullArtist?>(null) }
    var topTracks by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var albums by remember { mutableStateOf<List<SimpleAlbum>>(emptyList()) }
    var relatedArtists by remember { mutableStateOf<List<FullArtist>>(emptyList()) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }
    var showEntityMenu by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                // Support local artists (navigated from LibraryLocalMusic)
                if (artistId.startsWith("local_artist:")) {
                    var localTracks = SpotifyRepository.localArtistTracks[artistId]
                    if (localTracks.isNullOrEmpty()) {
                        val artistName = artistId.removePrefix("local_artist:")
                        localTracks = spotifyRepo.localTracks.filter { track -> track.artists.any { it.name == artistName } }
                        SpotifyRepository.localArtistTracks[artistId] = localTracks
                    }
                    val coverUri = localTracks.firstOrNull()?.externalUri
                    val images = if (coverUri.isNullOrBlank()) emptyList() else listOf(SpotifyImage(coverUri, null, null))
                    artistDetails = FullArtist(
                        id = artistId,
                        name = artistId.removePrefix("local_artist:"),
                        externalUri = "",
                        images = images,
                        genres = emptyList(),
                        followersTotal = localTracks.size
                    )
                    topTracks = localTracks
                    albums = emptyList()
                    relatedArtists = emptyList()
                } else {
                    // Fetch all data in parallel
                    val artistDef = async { spotifyRepo.getArtist(artistId) }
                    val tracksDef = async { spotifyRepo.getArtistTopTracks(artistId, limit = 10) }
                    val albumsDef = async { spotifyRepo.getArtistAlbums(artistId, limit = 20) }
                    val relatedDef = async { spotifyRepo.getRelatedArtists(artistId, limit = 10) }

                    artistDetails = artistDef.await()
                    topTracks = tracksDef.await().items
                    albums = albumsDef.await().items
                    relatedArtists = relatedDef.await().items
                }

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
        containerColor = darkBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding())) {
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
                    contentPadding = PaddingValues(bottom = 32.dp, top = 80.dp)
                ) {
                    // HEADER (Image + Name + Follow button)
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
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
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier.bouncingMarquee()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${artist.followersTotal ?: 0} followers",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }

                    if (topTracks.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Top Tracks",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clickable {
                                                if (topTracks.isNotEmpty()) {
                                                    onTrackClick(topTracks, 0)
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
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                    Surface(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clickable {
                                                if (topTracks.isNotEmpty()) {
                                                    onShufflePlay(topTracks)
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
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(topTracks.withIndex().toList(), key = { (_, track) -> track.id ?: "local_${track.name.hashCode()}" }) { (index, track) ->
                            val trackId = track.id ?: ""
                            val isLiked = spotifyRepo.isTrackLiked(trackId)
                            val coverUrl = artist.images.minByOrNull { it.width ?: 999 }?.url
                            
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
                                    val color by animateColorAsState(
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
                                    TrackRowItem(
                                        index = index + 1,
                                        track = track,
                                        fallbackCoverUrl = coverUrl,
                                        onClick = { onTrackClick(topTracks, index) },
                                        isLiked = if (artistId.startsWith("local_artist:")) false else isLiked,
                                        isCurrentTrack = track.id == currentTrackId,
                                        onLikeToggle = if (artistId.startsWith("local_artist:")) null else {
                                            {
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
            
            // Custom floating top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    artistDetails?.let { artist ->
                        val followed = spotifyRepo.isArtistFollowed(artist.id)
                        IconButton(onClick = { coroutineScope.launch { spotifyRepo.toggleFollowArtist(artist) } }) {
                            Icon(
                                imageVector = if (followed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Follow artist",
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

    if (showEntityMenu) {
        EntityOptionsMenuBottomSheet(
            entityType = "artist",
            entityId = artistId,
            entityName = artistDetails?.name ?: "",
            primaryArtistId = null,
            tracks = topTracks,
            onDismiss = { showEntityMenu = false },
            onGoToArtist = {}
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





