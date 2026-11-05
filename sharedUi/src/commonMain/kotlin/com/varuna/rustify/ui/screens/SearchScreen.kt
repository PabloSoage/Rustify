package com.varuna.rustify.ui.screens

import com.varuna.rustify.ui.toast
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.varuna.rustify.ui.resources.*
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NormalizedSearchResults
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.SpotifyLikeButton
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import com.varuna.rustify.util.LinkTarget
import com.varuna.rustify.util.RecentSearches

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
    modifier: Modifier = Modifier,
    onGoToRadio: ((String, String) -> Unit)? = null,
    currentTrackId: String?
) {
    val darkBackground = Color(0xFF121212)
    val spotifyGreen = Color(0xFF1DB954)
    val platform = com.varuna.rustify.ui.LocalAppPlatform.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val isLandscape = com.varuna.rustify.ui.isLandscape()
    val bottomPadding = if (isLandscape) 16.dp else 100.dp

    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<NormalizedSearchResults?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var recentSearches by remember { mutableStateOf(RecentSearches.list(platform.prefs())) }
    // The category being browsed, while its query is still the one in the field.
    var browsing by remember { mutableStateOf<SearchCategory?>(null) }

    // Kept only when the search was meant: submitted, or followed by opening a result.
    fun keepQuery() {
        if (searchQuery.isNotBlank()) recentSearches = RecentSearches.add(platform.prefs(), searchQuery)
    }

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
                // Use a single searchAll call and filter the results locally per active filter.
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
                placeholder = {
                    Text(stringResource(Res.string.search_placeholder), color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = stringResource(Res.string.nav_search), tint = Color.White)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            searchResults = null
                            // The category chose the Playlists filter, not the user.
                            if (browsing != null) activeFilter = SearchFilter.ALL
                            browsing = null
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                        }
                    } else {
                        // Paste a Spotify link from the clipboard and navigate to it rather than playing a stub.
                        IconButton(onClick = {
                            val pasted = platform.readClipboard()
                            if (pasted.isNullOrBlank()) {
                                platform.toast(Res.string.paste_clipboard_empty)
                                return@IconButton
                            }
                            when (val link = LinkTarget.parse(pasted)) {
                                is LinkTarget.SpotifyTrack -> {
                                    com.varuna.rustify.platform.Log.d("SearchScreen", "Pasted Spotify track: ${link.id}")
                                    onOpenTrack(link.id)
                                }
                                is LinkTarget.SpotifyAlbum -> onAlbumClick(link.id, "", emptyList())
                                is LinkTarget.SpotifyPlaylist -> onPlaylistClick(link.id, "", emptyList())
                                is LinkTarget.SpotifyArtist -> onArtistClick(link.id)
                                // A YouTube Music link is a link, just not one this screen opens.
                                else -> platform.toast(Res.string.paste_no_spotify_link)
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
                        keepQuery()
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
                        SearchFilter.ALL -> stringResource(Res.string.search_filter_all)
                        SearchFilter.TRACKS -> stringResource(Res.string.search_filter_tracks)
                        SearchFilter.ALBUMS -> stringResource(Res.string.search_filter_albums)
                        SearchFilter.ARTISTS -> stringResource(Res.string.search_filter_artists)
                        SearchFilter.PLAYLISTS -> stringResource(Res.string.search_filter_playlists)
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
                SearchEmptyState(
                    recent = recentSearches,
                    onRecentClick = { q ->
                        searchQuery = q
                        focusManager.clearFocus()
                        keepQuery()
                        performSearch(q)
                    },
                    onRecentRemove = { q -> recentSearches = RecentSearches.remove(platform.prefs(), q) },
                    onClearRecent = { recentSearches = RecentSearches.clear(platform.prefs()) },
                    onCategoryClick = { cat ->
                        // A category is a way in, not something the user typed: not kept as recent.
                        searchQuery = cat.query
                        browsing = cat
                        activeFilter = SearchFilter.PLAYLISTS
                        focusManager.clearFocus()
                        performSearch(cat.query)
                    },
                    bottomPadding = bottomPadding
                )
            } else if (searchResults != null) {
                val results = searchResults!!
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomPadding)
                ) {
                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.TRACKS) {
                        if (results.tracks.isNotEmpty()) {
                            item { SectionHeader(stringResource(Res.string.search_header_tracks)) }
                            items(results.tracks) { track ->
                                val trackId = track.id ?: ""
                                val isLiked = spotifyRepo.isTrackLiked(trackId)
                                
                                val dismissState = rememberSwipeToDismissBoxState(
                                    positionalThreshold = { it * 0.4f }
                                )

                                LaunchedEffect(dismissState.currentValue) {
                                    if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
                                        onAddToQueue(track)
                                        platform.toast(Res.string.added_to_queue)
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
                                            subtitle = stringResource(Res.string.search_subtitle_track, track.artists.joinToString(", ") { it.name }),
                                            imageUrl = track.album?.images?.maxByOrNull { it.width ?: 0 }?.url,
                                            onClick = { keepQuery(); onTrackClick(track) },
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
                            item { SectionHeader(stringResource(Res.string.search_header_albums)) }
                            items(results.albums) { album ->
                                SearchResultRow(
                                    title = album.name,
                                    subtitle = stringResource(Res.string.search_subtitle_album, album.artists.joinToString(", ") { it.name }),
                                    imageUrl = album.images.maxByOrNull { it.width ?: 0 }?.url,
                                    onClick = { keepQuery(); onAlbumClick(album.id, album.name, album.images) }
                                )
                            }
                        }
                    }

                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.PLAYLISTS) {
                        if (results.playlists.isNotEmpty()) {
                            item { SectionHeader(stringResource(Res.string.search_header_playlists)) }
                            // Browsing a category, Spotify's own playlists for it come first.
                            val playlists = if (browsing?.query == searchQuery) results.playlists.sortedByDescending { it.owner?.id == "spotify" }
                                else results.playlists
                            items(playlists) { playlist ->
                                SearchResultRow(
                                    title = playlist.name,
                                    subtitle = stringResource(Res.string.search_subtitle_playlist, playlist.owner?.name ?: "Spotify"),
                                    imageUrl = playlist.images.maxByOrNull { it.width ?: 0 }?.url,
                                    onClick = { keepQuery(); onPlaylistClick(playlist.id, playlist.name, playlist.images) }
                                )
                            }
                        }
                    }

                    if (activeFilter == SearchFilter.ALL || activeFilter == SearchFilter.ARTISTS) {
                        if (results.artists.isNotEmpty()) {
                            item { SectionHeader(stringResource(Res.string.search_header_artists)) }
                            items(results.artists) { artist ->
                                SearchResultRow(
                                    title = artist.name,
                                    subtitle = stringResource(Res.string.search_subtitle_artist),
                                    imageUrl = artist.images.maxByOrNull { it.width ?: 0 }?.url,
                                    isCircle = true,
                                    onClick = { keepQuery(); onArtistClick(artist.id) }
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


