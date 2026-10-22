package com.varuna.rustify.ui.screens

import com.varuna.rustify.bridge.PlaylistRef
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullPlaylist
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SimplePlaylist
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
    modifier: Modifier = Modifier,
    onGoToRadio: ((String, String) -> Unit)? = null,
    onShufflePlay: (List<FullTrack>) -> Unit = {},
    currentTrackId: String?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Local playlist: the id already carries the "localpl:" prefix resolved by routing.
    val isLocal = PlaylistRef.isLocal(playlistId)
    val localPlaylist = if (isLocal) spotifyRepo.localPlaylists.firstOrNull { it.id == playlistId } else null
    var playlistDetails by remember { mutableStateOf<FullPlaylist?>(null) }
    // Spotify playlists use the repository's live list (it survives screen recreation such as
    // opening/closing the miniplayer and loads fully in the background). Local playlists use local state.
    var localTracksState by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    val spotifyTracks = if (!isLocal) spotifyRepo.playlistTracksLive(playlistId) else null
    val tracks: List<FullTrack> = if (isLocal) localTracksState else spotifyTracks!!

    // Find in this playlist (issue #2). The repo loads every page in the background, so once it is
    // done the index holds the whole playlist, not the part scrolled into view; it is rebuilt each
    // time the list grows. Ids are positions, so a song the playlist repeats is found at each place.
    var findQuery by remember(playlistId) { mutableStateOf("") }
    // Its own index name per screen: with one shared name, opening another playlist on top and
    // coming back left this one searching an index the other had replaced and then forgotten.
    val findIndex = remember(playlistId) { "${com.varuna.rustify.util.LocalSearch.PLAYLIST}:${java.util.UUID.randomUUID()}" }
    // A copy as the key: for a Spotify playlist `tracks` is the repo's live list, one object that
    // grows as pages arrive, so keying on it froze the index at the moment it was still empty.
    val trackSnapshot = tracks.toList()
    val indexedTracks = remember(trackSnapshot) {
        trackSnapshot.withIndex().toList().also { all ->
            com.varuna.rustify.util.LocalSearch.index(
                findIndex, all, { it.index.toString() },
                { (_, t) -> listOf(t.name) + t.artists.map { a -> a.name } + listOf(t.album?.name.orEmpty()) }
            )
        }
    }
    val shownTracks = remember(indexedTracks, findQuery) {
        com.varuna.rustify.util.LocalSearch.filter(
            findIndex, indexedTracks, findQuery
        ) { it.index.toString() }
    }
    androidx.compose.runtime.DisposableEffect(playlistId) {
        onDispose { com.varuna.rustify.util.LocalSearch.forget(findIndex) }
    }

    // Which of these have been heard through — point I. A row with no id gets a positional stand-in
    // rather than being dropped: dropping it would shift every tick after it onto the wrong song.
    // This is the list where the bitfield's realignment earns its keep, because playlists are the
    // ones that get edited.
    val listened = com.varuna.rustify.bridge.rememberListened(
        contextId = "playlist:$playlistId",
        trackIds = tracks.mapIndexed { position, t -> t.id?.takeIf { it.isNotBlank() } ?: "#$position" },
        refreshKey = currentTrackId
    )

    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }
    var showEntityMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteLocalDialog by remember { mutableStateOf(false) }
    var showRenameLocalDialog by remember { mutableStateOf(false) }
    var meId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Spinner: for local playlists it is driven by isLoading; for Spotify by the repo's observable
    // flag (plus the initial isLoading to avoid flashing "no tracks" before loading starts).
    val loadingNow = if (isLocal) isLoading else (isLoading || spotifyRepo.isPlaylistLoading(playlistId))

    LaunchedEffect(Unit) { runCatching { meId = spotifyRepo.getMe().id } }

    // Recommended tracks under the playlist. Each "refresh" skips what was already offered as well
    // as what is in the playlist, so it brings different ones instead of the same ten again.
    var recommended by remember(playlistId) { mutableStateOf<List<FullTrack>>(emptyList()) }
    var recommendRound by remember(playlistId) { mutableStateOf(0) }
    val offered = remember(playlistId) { mutableSetOf<String>() }
    val playlistLoaded = !isLocal && !spotifyRepo.isPlaylistLoading(playlistId) && tracks.isNotEmpty()
    LaunchedEffect(playlistId, playlistLoaded, recommendRound) {
        if (!playlistLoaded) return@LaunchedEffect
        val skip = tracks.mapNotNull { it.id } + offered
        val next = runCatching { spotifyRepo.extendPlaylist(playlistId, skip.distinct(), 10) }.getOrDefault(emptyList())
        if (next.isNotEmpty()) {
            next.mapNotNullTo(offered) { it.id }
            recommended = next
        }
    }

    LaunchedEffect(playlistId) {
        errorMessage = null
        if (isLocal) {
            isLoading = true
            try {
                // Purely local playlist: resolve "local:" ids against localTracks.
                localTracksState = spotifyRepo.localPlaylistTracks(playlistId)
                SpotifyRepository.localPlaylistTracksCache[playlistId] = localTracksState
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load details"
            } finally {
                isLoading = false
            }
        } else {
            // Header metadata is best-effort; the track list loads independently in the background.
            runCatching { playlistDetails = spotifyRepo.getPlaylist(playlistId) }
                .onFailure { if (spotifyTracks!!.isEmpty()) errorMessage = it.message }
            // Load all pages in the background into the repo's live list (idempotent).
            spotifyRepo.loadFullPlaylist(playlistId)
            isLoading = false   // from here on the repo's observable flag takes over
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

            if (loadingNow && tracks.isEmpty()) {
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
                        text = stringResource(R.string.common_something_wrong), 
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
                                errorMessage = null
                                if (isLocal) {
                                    isLoading = true
                                    try {
                                        localTracksState = spotifyRepo.localPlaylistTracks(playlistId)
                                    } catch (e: Exception) {
                                        errorMessage = e.message
                                    } finally {
                                        isLoading = false
                                    }
                                } else {
                                    runCatching { playlistDetails = spotifyRepo.getPlaylist(playlistId) }
                                    spotifyRepo.loadFullPlaylist(playlistId, force = true)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen)
                    ) {
                        Text(stringResource(R.string.general_retry), color = Color.White)
                    }
                }
            } else {
                // Pull-to-refresh, the same gesture the library's playlist list already has. Inside a
                // playlist the list is served from the repository's cached live list, so a playlist
                // edited elsewhere (or one left short by a failed page) had no way to be re-fetched
                // without leaving the screen. `force` is what makes it a real re-read rather than the
                // idempotent no-op loadFullPlaylist normally is.
                PullToRefreshBox(
                    isRefreshing = if (isLocal) isLoading else spotifyRepo.isPlaylistLoading(playlistId),
                    onRefresh = {
                        coroutineScope.launch {
                            if (isLocal) {
                                isLoading = true
                                try {
                                    localTracksState = spotifyRepo.localPlaylistTracks(playlistId)
                                    SpotifyRepository.localPlaylistTracksCache[playlistId] = localTracksState
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                } finally {
                                    isLoading = false
                                }
                            } else {
                                runCatching { playlistDetails = spotifyRepo.getPlaylist(playlistId) }
                                spotifyRepo.loadFullPlaylist(playlistId, force = true)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                LazyColumn(
                    state = lazyListState,
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
                                // 2x2 mosaic of the covers of the first four local tracks.
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
                                    else playlistName.ifBlank { playlistDetails?.name.orEmpty() },
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                    modifier = Modifier.bouncingMarquee()
                                )
                                if (isLocal) {
                                    // Use a dedicated subtitle instead of "Spotify Playlist" / owner "Spotify".
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val count = tracks.size
                                    Text(
                                        text = stringResource(R.string.local_playlist_label) + " · " + pluralStringResource(R.plurals.track_count, count, count),
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
                                            text = (playlistDetails?.totalTracks ?: tracks.size).let { n -> pluralStringResource(R.plurals.track_count, n, n) },
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
                        if (tracks.size > 1) {
                            item(key = "find_in_playlist") {
                                FindInPlaylistField(
                                    query = findQuery,
                                    onQueryChange = { findQuery = it },
                                    searchedOf = if (loadingNow) tracks.size else null
                                )
                            }
                        }
                        if (shownTracks.isEmpty() && findQuery.isNotBlank()) {
                            item(key = "find_no_results") {
                                Text(
                                    stringResource(R.string.playlist_find_none, findQuery),
                                    color = Color.Gray,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                                )
                            }
                        }
                        // `index` stays the song's place in the whole playlist: playing from a result
                        // plays the playlist from there, and the listened ticks line up.
                        items(shownTracks, key = { (index, track) -> track.id?.takeIf { it.isNotBlank() }?.let { "$it@$index" } ?: "local_${index}_${track.name.hashCode()}" }) { (index, track) ->
                            // No scroll pagination: the repo loads all pages in the background
                            // (loadFullPlaylist), so the list is already complete here.
                            val trackId = track.id ?: ""
                            // Local tracks use local favorites, not Spotify's "liked" state.
                            var localFav by remember(trackId) { mutableStateOf(spotifyRepo.isLocalFavorite(trackId)) }
                            val isLiked = if (isLocal) localFav else spotifyRepo.isTrackLiked(trackId)

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
                                        fallbackCoverUrl = playlistImages.firstOrNull()?.url,
                                        onClick = { onTrackClick(tracks, index) },
                                        isLiked = isLiked,
                                        isCurrentTrack = track.id == currentTrackId,
                                        isListened = listened.getOrElse(index) { false },
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
                        
                        // "Loading more" footer while the repo finishes fetching pages in the background.
                        if (!isLocal && spotifyRepo.isPlaylistLoading(playlistId)) {
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

                        // "Recommended" (4.6): Spotify's playlist extender, which answers without an
                        // account. Asked once the whole playlist is in, so what is already there is skipped.
                        if (!isLocal && !spotifyRepo.isPlaylistLoading(playlistId) && recommended.isNotEmpty()) {
                            item(key = "recommended_header") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.playlist_recommended), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                        Text(stringResource(R.string.playlist_recommended_hint), color = Color.Gray, fontSize = 12.sp)
                                    }
                                    androidx.compose.material3.TextButton(onClick = { recommendRound++ }) {
                                        Text(stringResource(R.string.playlist_recommended_more), color = spotifyGreen)
                                    }
                                }
                            }
                            itemsIndexed(recommended, key = { _, t -> "rec_" + (t.id ?: t.name) }) { index, track ->
                                TrackRowItem(
                                    index = index + 1,
                                    track = track,
                                    fallbackCoverUrl = null,
                                    onClick = { onTrackClick(recommended, index) },
                                    isLiked = spotifyRepo.isTrackLiked(track.id ?: ""),
                                    isCurrentTrack = track.id == currentTrackId,
                                    isListened = false,
                                    onLikeToggle = { coroutineScope.launch { spotifyRepo.toggleLikeTrack(track) } },
                                    onMoreClick = { selectedTrackForMenu = track }
                                )
                            }
                        }
                    }
                }
                }
            }

            // Draggable scrollbar with tooltip (as in Liked Songs), shown only for long lists.
            if (tracks.size > 25) {
                VerticalScrollbarWithTooltip(
                    lazyListState = lazyListState,
                    itemsCount = tracks.size + 2, // +2 for the LazyColumn headers (cover + buttons)
                    getDateForItem = { idx ->
                        val ti = idx - 2
                        tracks.getOrNull(ti)?.let { t -> t.artists.firstOrNull()?.name?.take(14) ?: "${ti + 1}" } ?: ""
                    },
                    onDragStateChanged = {},
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 80.dp, bottom = 32.dp, end = 2.dp)
                )
            }

            // Custom floating top bar. Over a fade, not bare: scrolled, its icons sat straight on top of
            // the rows below and were hard to tell apart from them (4.7).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color(0xFF121212), Color(0xEB121212), Color.Transparent)
                        )
                    )
                    .padding(top = 40.dp, start = 8.dp, end = 8.dp, bottom = 16.dp),
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
                    // Edit/Follow are Spotify-only: they appear only when playlistDetails is present
                    // (null for local playlists), so they are hidden automatically.
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
                                    SimplePlaylist(pl.id, pl.name, pl.description, pl.images, pl.externalUri, pl.owner, pl.tracks)
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
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(stringResource(R.string.playlist_edit_title), color = Color.White) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(stringResource(R.string.common_name)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text(stringResource(R.string.common_description)) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
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
                }) { Text(stringResource(R.string.ytm_save), color = Color(0xFF1DB954)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = Color.Gray)
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
            onGoToArtist = onArtistClick,
            // `tracks` here is a list that fills in from the network while you look at it, so acting
            // on it is acting on however far it got. Waiting for the background load would not fix
            // it either: that load keeps whatever it managed on a network error, so a short list and
            // a finished list are indistinguishable afterwards — which is the same silence that made
            // this a bug. Fetched outright instead. A local playlist is whole by construction.
            loadAllTracks = if (isLocal) null else {
                { spotifyRepo.getAllPlaylistTracks(playlistId) }
            }
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
            // Only for a row that is in the playlist, and only where removing can work: a local playlist
            // or one you own. It was offered everywhere — for Spotify's own playlists, signed out, and
            // for the recommended tracks below the list, which are not in it (seen in 4.6).
            onRemoveFromPlaylist = if (selectedTrackForMenu in tracks && (isLocal || (meId != null && playlistDetails?.owner?.id == meId))) ({
                val trackToRemove = selectedTrackForMenu!!
                coroutineScope.launch {
                    trackToRemove.id?.let { tid ->
                        if (PlaylistRef.isLocal(playlistId)) {
                            spotifyRepo.removeFromLocalPlaylist(playlistId, tid)
                            localTracksState = spotifyRepo.localPlaylistTracks(playlistId)
                        } else {
                            val res = spotifyRepo.removeTracksFromPlaylist(playlistId, listOf(tid))
                            if (res.success) spotifyRepo.loadFullPlaylist(playlistId, force = true)
                        }
                    }
                }
                selectedTrackForMenu = null
            }) else null
        )
    }
}


/** The "find in playlist" box (issue #2). [searchedOf] is how many songs are loaded yet, while loading. */
@Composable
private fun FindInPlaylistField(query: String, onQueryChange: (String) -> Unit, searchedOf: Int?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.playlist_find_hint), color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.playlist_find_clear), tint = Color.Gray)
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF242424), unfocusedContainerColor = Color(0xFF242424),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Color(0xFF1DB954)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (searchedOf != null && query.isNotBlank()) {
            Text(stringResource(R.string.playlist_find_loading, searchedOf), color = Color.Gray, fontSize = 12.sp)
        }
    }
}
