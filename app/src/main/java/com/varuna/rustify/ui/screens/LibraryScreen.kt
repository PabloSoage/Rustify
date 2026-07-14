package com.varuna.rustify.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.bridge.YtmTrack
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.TrackOptionsMenuBottomSheet
import com.varuna.rustify.ui.components.TrackRowItem
import kotlinx.coroutines.launch
import com.varuna.rustify.bridge.LocalPlaylist

enum class LibraryTab {
    PLAYLISTS,
    ALBUMS,
    ARTISTS,
    TRACKS,
    LOCAL,
    YTMUSIC
}


@Composable
fun LibraryScreen(
    spotifyRepo: SpotifyRepository,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    selectedGroup: String,
    onGroupSelected: (String) -> Unit,
    onPlaylistClick: (String, String, List<SpotifyImage>) -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAddToQueue: (FullTrack) -> Unit,
    onGoToQueue: () -> Unit,
    onArtistClick: (String) -> Unit,
    onGoToRadio: ((String, String) -> Unit)? = null,
    onOpenSettings: () -> Unit,
    // E40 — YTM: shared repo + navigation callbacks to first-level NavHost screens.
    ytmRepo: YtMusicRepository? = null,
    onYtmOpenSearch: () -> Unit = {},
    onYtmOpenAlbum: (String, String) -> Unit = { _, _ -> },
    onYtmOpenArtist: (String, String) -> Unit = { _, _ -> },
    onYtmOpenLocalPlaylist: (String) -> Unit = {},
    onYtmOpenPlaylist: (String, String) -> Unit = { _, _ -> },
    onYtmPasteLink: () -> Unit = {},
    modifier: Modifier = Modifier,
    currentTrackId: String? = null
) {
    val darkBackground = Color(0xFF121212)
    val spotifyGreen = Color(0xFF1DB954)
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
    val enableLocalMusic = prefs.getBoolean("enable_local_music", true)
    val enableYtmMusic = prefs.getBoolean("enable_ytm_music", true)
    var ytmResultFilter by remember { mutableStateOf("all") }
    var ytmUseScraper by remember { mutableStateOf(prefs.getString("ytm_search_mode", "api") == "scraper") }
    // React to the scraper toggle being changed elsewhere (Settings) while this screen is alive.
    androidx.compose.runtime.DisposableEffect(prefs) {
        val djScraperListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "ytm_search_mode") ytmUseScraper = p?.getString("ytm_search_mode", "api") == "scraper"
        }
        prefs.registerOnSharedPreferenceChangeListener(djScraperListener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(djScraperListener) }
    }
    var ytmPendingPlaylistTrack by remember { mutableStateOf<YtmTrack?>(null) }
    var showYtmCreatePlaylistDialog by remember { mutableStateOf(false) }
    var ytmNewPlaylistName by remember { mutableStateOf("") }
    
    val tabs = remember(enableLocalMusic, enableYtmMusic) {
        LibraryTab.entries.filter { tab ->
            when (tab) {
                LibraryTab.LOCAL -> enableLocalMusic
                LibraryTab.YTMUSIC -> enableYtmMusic
                else -> true
            }
        }
    }

    // -- Search bar collapsible state --
    // searchBarOffset in [-searchBarFullHeightPx, 0]; 0 = fully shown, negative = collapsed.
    var searchBarOffset by remember { mutableFloatStateOf(0f) }
    var searchBarFullHeightPx by remember { mutableFloatStateOf(0f) }
    var hasMeasuredSearchBar by remember { mutableStateOf(false) }
    var tabsHeightPx by remember { mutableFloatStateOf(0f) }
    var hasMeasuredTabs by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // Dynamic visible header height: shrinks from (searchBarHeight + tabsHeight) down
    // to tabsHeight as the bar collapses. Used as content padding-top.
    val visibleHeaderHeightDp = with(density) {
        (tabsHeightPx + (searchBarFullHeightPx + searchBarOffset).coerceAtLeast(0f)).toDp()
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            // Both collapse (scroll up) and expand (scroll down) happen here.
            // When swiping up (available.y < 0), the bar collapses first before the list scrolls.
            // When swiping down (available.y > 0), the bar expands first before the list scrolls.
            // Net movement = padding shrinks/grows X + list scrolls 0 = X. 1:1 with finger.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (hasMeasuredSearchBar) {
                    val prevOffset = searchBarOffset
                    searchBarOffset = (searchBarOffset + available.y)
                        .coerceIn(-searchBarFullHeightPx, 0f)
                    return Offset(0f, searchBarOffset - prevOffset) // consume used amount
                }
                return Offset.Zero
            }
        }
    }

    var globalSearchQuery by rememberSaveable { mutableStateOf("") }

    // Root Box: content underneath, header (bar+tabs) overlay on top.
    // The header Column translates as a block: when the bar collapses, the tabs
    // slide up to the top of the screen. The content has a FIXED padding-top
    // equal to the full header height, so the list never reflows.
    // clipToBounds prevents the bar from leaking into the status bar area.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(darkBackground)
            .clipToBounds()
            .nestedScroll(nestedScrollConnection)
    ) {
        // --- Content (list): dynamic padding-top that shrinks as the bar collapses ---
        // Using padding change (not graphicsLayer) means the Box expands upward,
        // giving the LazyColumn more visible area — no whole-container translation,
        // no black gap at the bottom, and no scroll-position jump.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = visibleHeaderHeightDp)
        ) {
            when (selectedTab) {
                LibraryTab.PLAYLISTS -> LibraryPlaylists(spotifyRepo, onPlaylistClick, globalSearchQuery)
                LibraryTab.ALBUMS -> LibraryAlbums(spotifyRepo, onAlbumClick, globalSearchQuery)
                LibraryTab.ARTISTS -> LibraryArtists(spotifyRepo, onArtistClick, globalSearchQuery)
                LibraryTab.TRACKS -> LibraryTracks(
                    spotifyRepo = spotifyRepo,
                    onTrackClick = onTrackClick,
                    onAddToQueue = onAddToQueue,
                    onGoToQueue = onGoToQueue,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onGoToRadio = onGoToRadio,
                    currentTrackId = currentTrackId,
                    searchQuery = globalSearchQuery
                )
                LibraryTab.LOCAL -> LibraryLocalMusic(
                    spotifyRepo = spotifyRepo,
                    onTrackClick = onTrackClick,
                    onAddToQueue = onAddToQueue,
                    onGoToQueue = onGoToQueue,
                    onOpenSettings = onOpenSettings,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick,
                    currentTrackId = currentTrackId,
                    searchQuery = globalSearchQuery,
                    selectedGroup = selectedGroup,
                    onGroupSelected = onGroupSelected
                )
                LibraryTab.YTMUSIC -> {
                    // Fallback repo if the caller didn't hoist one (keeps preview/testing simple).
                    val repo = ytmRepo ?: remember { YtMusicRepository(context.applicationContext) }
                    YtMusicLibraryContent(
                        repo = repo,
                        searchQuery = globalSearchQuery,
                        onPasteLink = onYtmPasteLink,
                        onTrackClick = onTrackClick,
                        onOpenAlbum = onYtmOpenAlbum,
                        onOpenArtist = onYtmOpenArtist,
                        onOpenLocalPlaylist = onYtmOpenLocalPlaylist,
                        onOpenPlaylist = onYtmOpenPlaylist,
                        onAddToQueue = { tracks -> tracks.forEach { onAddToQueue(it) } },
                        onGoToQueue = onGoToQueue,
                        onAddToPlaylist = { track -> ytmPendingPlaylistTrack = track },
                        resultFilter = ytmResultFilter,
                        onFilterChanged = { ytmResultFilter = it },
                        useScraper = ytmUseScraper,
                        onToggleScraper = {
                            ytmUseScraper = !ytmUseScraper
                            prefs.edit().putString("ytm_search_mode", if (ytmUseScraper) "scraper" else "api").apply()
                        },
                        currentTrackId = currentTrackId
                    )
                }
            }
        }

        // --- Header overlay (search bar + tabs): translates as a single block ---
        // When searchBarOffset == 0 → fully visible (bar + tabs).
        // When searchBarOffset == -searchBarFullHeightPx → bar hidden, tabs at very top.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = searchBarOffset }
        ) {
            // Search bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(darkBackground)
                    .onGloballyPositioned { coordinates ->
                        val h = coordinates.size.height.toFloat()
                        if (h > 0 && !hasMeasuredSearchBar) {
                            searchBarFullHeightPx = h
                            hasMeasuredSearchBar = true
                        }
                    }
            ) {
                OutlinedTextField(
                    value = globalSearchQuery,
                    onValueChange = { globalSearchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    placeholder = { Text(stringResource(R.string.search_placeholder), color = Color.Gray) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                    },
                    trailingIcon = {
                        Row {
                            if (selectedTab == LibraryTab.YTMUSIC) {
                                var showFilterMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { showFilterMenu = true }) {
                                        Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = Color.White)
                                    }
                                    DropdownMenu(
                                        expanded = showFilterMenu,
                                        onDismissRequest = { showFilterMenu = false }
                                    ) {
                                        Text(stringResource(R.string.ytm_search_filter_title), color = Color.Gray,
                                            fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                        val filters = listOf("all" to R.string.search_filter_all, "tracks" to R.string.search_filter_tracks,
                                            "albums" to R.string.search_filter_albums, "artists" to R.string.search_filter_artists,
                                            "playlists" to R.string.search_filter_playlists)
                                        filters.forEach { (f, label) ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(label), fontSize = 14.sp,
                                                    color = if (ytmResultFilter == f) Color(0xFF1DB954) else Color.White) },
                                                onClick = { ytmResultFilter = f; showFilterMenu = false },
                                                leadingIcon = if (ytmResultFilter == f) {{ Icon(Icons.Default.Check, null, tint = Color(0xFF1DB954)) }}
                                                    else {{ Spacer(Modifier.size(24.dp)) }}
                                            )
                                        }
                                        HorizontalDivider(color = Color.DarkGray)
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.settings_ytm_scraper), fontSize = 14.sp,
                                                color = if (ytmUseScraper) Color(0xFFE65100) else Color.White) },
                                            onClick = {
                                                ytmUseScraper = !ytmUseScraper
                                                prefs.edit().putString("ytm_search_mode", if (ytmUseScraper) "scraper" else "api").apply()
                                                showFilterMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.FilterList, null,
                                                    tint = if (ytmUseScraper) Color(0xFFE65100) else Color.White)
                                            }
                                        )
                                        HorizontalDivider(color = Color.DarkGray)
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.ytm_paste_link), fontSize = 14.sp, color = Color.White) },
                                            onClick = { showFilterMenu = false; onYtmPasteLink() },
                                            leadingIcon = { Icon(Icons.Default.Add, null, tint = Color.White) }
                                        )
                                    }
                                }
                            }
                            if (globalSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { globalSearchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                                }
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

            // Tabs
            androidx.compose.material3.SecondaryScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = darkBackground,
                contentColor = Color.White,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTab.ordinal),
                        color = spotifyGreen
                    )
                },
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val h = coordinates.size.height.toFloat()
                    if (h > 0 && !hasMeasuredTabs) {
                        tabsHeightPx = h
                        hasMeasuredTabs = true
                    }
                }
            ) {
                tabs.forEachIndexed { index, tab ->
                    val title = when (tab) {
                        LibraryTab.PLAYLISTS -> stringResource(R.string.library_tab_playlists)
                        LibraryTab.ALBUMS -> stringResource(R.string.library_tab_albums)
                        LibraryTab.ARTISTS -> stringResource(R.string.library_tab_artists)
                        LibraryTab.LOCAL -> stringResource(R.string.library_tab_local_music)
                        LibraryTab.TRACKS -> stringResource(R.string.library_tab_liked_tracks)
                        LibraryTab.YTMUSIC -> stringResource(R.string.ytm_title)
                    }
                    Tab(
                        selected = selectedTab == tab,
                        onClick = {
                            onTabSelected(tab)
                        },
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
        }

        // ── YTM: playlist selector dialog (uses YTM playlists, not local) ──
        if (ytmPendingPlaylistTrack != null) {
            val track = ytmPendingPlaylistTrack!!
            val repo = ytmRepo ?: remember { YtMusicRepository(context.applicationContext) }
            AlertDialog(
                onDismissRequest = { ytmPendingPlaylistTrack = null },
                title = { Text(stringResource(R.string.local_playlist_selector_title)) },
                text = {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                ytmNewPlaylistName = ""; showYtmCreatePlaylistDialog = true
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, null, tint = Color(0xFF1DB954))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.local_playlist_create), color = Color.White)
                        }
                        HorizontalDivider(color = Color.DarkGray)
                        if (repo.playlists.isEmpty()) {
                            Text(stringResource(R.string.playlist_not_found), color = Color.Gray, modifier = Modifier.padding(16.dp))
                        } else {
                            repo.playlists.forEach { pl ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        repo.addToPlaylist(pl.localId, track)
                                        ytmPendingPlaylistTrack = null
                                    }.padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(pl.name, color = Color.White,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { ytmPendingPlaylistTrack = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }
        if (showYtmCreatePlaylistDialog) {
            val repo = ytmRepo ?: remember { YtMusicRepository(context.applicationContext) }
            AlertDialog(
                onDismissRequest = { showYtmCreatePlaylistDialog = false; ytmNewPlaylistName = "" },
                title = { Text(stringResource(R.string.local_playlist_create)) },
                text = {
                    OutlinedTextField(
                        value = ytmNewPlaylistName, onValueChange = { ytmNewPlaylistName = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.local_playlist_name_hint)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (ytmNewPlaylistName.isNotBlank()) {
                            val pl = repo.createPlaylist(ytmNewPlaylistName.trim())
                            ytmPendingPlaylistTrack?.let { repo.addToPlaylist(pl.localId, it) }
                        }
                        showYtmCreatePlaylistDialog = false
                        ytmNewPlaylistName = ""
                        ytmPendingPlaylistTrack = null
                    }) { Text(stringResource(R.string.local_playlist_create_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showYtmCreatePlaylistDialog = false; ytmNewPlaylistName = "" }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
fun LibraryPlaylists(
    spotifyRepo: SpotifyRepository,
    onPlaylistClick: (String, String, List<SpotifyImage>) -> Unit,
    searchQuery: String = ""
) {
    val playlists = spotifyRepo.savedPlaylists
    val isLoading = spotifyRepo.isSyncingPlaylists
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (playlists.isEmpty() && !isLoading) {
            spotifyRepo.syncPlaylists()
        }
    }

    LibraryContentList(
        isLoading = isLoading,
        errorMessage = null,
        items = if (isLoading && playlists.isEmpty()) null else playlists.toList(),
        onRetry = {
            coroutineScope.launch {
                spotifyRepo.syncPlaylists()
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
        searchQuery = searchQuery,
        filterPredicate = { playlist, query ->
            val combinedQuery = if (query.isBlank() && searchQuery.isNotBlank()) searchQuery else query
            if (combinedQuery.isBlank()) true
            else playlist.name.contains(combinedQuery, ignoreCase = true) || 
            (playlist.owner?.name?.contains(combinedQuery, ignoreCase = true) == true)
        }
    )
}

@Composable
fun LibraryAlbums(
    spotifyRepo: SpotifyRepository,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    searchQuery: String = ""
) {
    val albums = spotifyRepo.savedAlbums
    val isLoading = spotifyRepo.isSyncingAlbums
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (albums.isEmpty() && !isLoading) {
            spotifyRepo.syncAlbums()
        }
    }

    LibraryContentList(
        isLoading = isLoading,
        errorMessage = null,
        items = if (isLoading && albums.isEmpty()) null else albums.toList(),
        onRetry = {
            coroutineScope.launch {
                spotifyRepo.syncAlbums()
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
        searchQuery = searchQuery,
        filterPredicate = { album, query ->
            val combinedQuery = if (query.isBlank() && searchQuery.isNotBlank()) searchQuery else query
            if (combinedQuery.isBlank()) true
            else album.name.contains(combinedQuery, ignoreCase = true) || 
            album.artists.any { it.name.contains(combinedQuery, ignoreCase = true) }
        }
    )
}

@Composable
fun LibraryArtists(
    spotifyRepo: SpotifyRepository,
    onArtistClick: (String) -> Unit,
    searchQuery: String = ""
) {
    val artists = spotifyRepo.followedArtists
    val isLoading = spotifyRepo.isSyncingArtists
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (artists.isEmpty() && !isLoading) {
            spotifyRepo.syncArtists()
        }
    }

    LibraryContentList(
        isLoading = isLoading,
        errorMessage = null,
        items = if (isLoading && artists.isEmpty()) null else artists.toList(),
        onRetry = {
            coroutineScope.launch {
                spotifyRepo.syncArtists()
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
        searchQuery = searchQuery,
        filterPredicate = { artist, query ->
            val combinedQuery = if (query.isBlank() && searchQuery.isNotBlank()) searchQuery else query
            if (combinedQuery.isBlank()) true
            else artist.name.contains(combinedQuery, ignoreCase = true)
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
    onArtistClick: (String) -> Unit,
    onGoToRadio: ((String, String) -> Unit)? = null,
    currentTrackId: String? = null,
    searchQuery: String = ""
) {
    val tracks = spotifyRepo.likedTracks
    val isSyncing = spotifyRepo.isSyncingLikedTracks
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()
    var isScrollbarDragging by remember { mutableStateOf(false) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val bottomPadding = if (isLandscape) 16.dp else 100.dp

    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) tracks
        else tracks.filter { 
            it.name.contains(searchQuery, ignoreCase = true) || 
            it.artists.any { artist -> artist.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

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
                    contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(filteredTracks, key = { index, track -> track.id ?: "local_${index}_${track.name.hashCode()}" }) { index, track ->
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
                            backgroundContent = {
                                val color by animateColorAsState(
                                    if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd)
                                        Color(0xFF1DB954) else Color.Transparent,
                                    animationSpec = tween(300),
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
                                    isCurrentTrack = track.id == currentTrackId,
                                    onLikeToggle = {
                                        coroutineScope.launch {
                                            spotifyRepo.toggleLikeTrack(track)
                                        }
                                    },
                                    isScrollbarDragging = isScrollbarDragging,
                                    onMoreClick = { selectedTrackForMenu = track },
                                    modifier = Modifier.background(Color(0xFF121212))
                                )
                            }
                        )
                    }
                }

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
            onGoToRadio = onGoToRadio?.let { cb -> {
                val t = selectedTrackForMenu!!
                cb(t.id ?: "", t.name)
                selectedTrackForMenu = null
            } },
            onRemoveFromPlaylist = null
        )
    }
}

@Composable
fun LibraryLocalMusic(
    spotifyRepo: SpotifyRepository,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAddToQueue: (FullTrack) -> Unit,
    onGoToQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String, String, List<SpotifyImage>) -> Unit,
    currentTrackId: String? = null,
    searchQuery: String = "",
    selectedGroup: String,
    onGroupSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val tracks = spotifyRepo.localTracks
    val isLoading = spotifyRepo.isScanningLocalTracks
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTrackForMenu by remember { mutableStateOf<FullTrack?>(null) }

    val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
    val localMusicDirs = prefs.getStringSet("local_music_directories", emptySet()) ?: emptySet()
    
    LaunchedEffect(localMusicDirs) {
        spotifyRepo.scanLocalMusic()
    }

    val sortedTracks: List<FullTrack> = tracks.sortedBy { it.name.lowercase().trim() }

    val filteredTracks: List<FullTrack> = if (searchQuery.isBlank()) sortedTracks
        else sortedTracks.filter { track ->
            track.name.contains(searchQuery, ignoreCase = true) ||
            track.artists.any { artist -> artist.name.contains(searchQuery, ignoreCase = true) }
        }

    val albumGroups: List<Pair<String, List<FullTrack>>> = filteredTracks.groupBy { it.album?.name ?: "Unknown Album" }
        .toList().sortedBy { it.first.lowercase().trim() }
    val artistGroups: List<Pair<String, List<FullTrack>>> = filteredTracks.groupBy { it.artists.firstOrNull()?.name ?: "Unknown Artist" }
        .toList().sortedBy { it.first.lowercase().trim() }

    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val bottomPadding = if (isLandscape) 16.dp else 100.dp
    val groupOptions = listOf(
        stringResource(R.string.local_group_tracks) to "Tracks",
        stringResource(R.string.local_group_albums) to "Albums",
        stringResource(R.string.local_group_artists) to "Artists",
        stringResource(R.string.local_group_playlists) to "Playlists",
        stringResource(R.string.local_group_favorites) to "Favorites"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(groupOptions.size) { i ->
                val (label, key) = groupOptions[i]
                androidx.compose.material3.FilterChip(
                    selected = selectedGroup == key,
                    onClick = { onGroupSelected(key) },
                    label = { Text(label) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF1DB954),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // Content
        if (localMusicDirs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.library_local_not_configured), color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                    ) {
                        Text(stringResource(R.string.library_local_configure_btn), color = Color.White)
                    }
                }
            }
        } else if (isLoading && tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF1DB954))
            }
        } else if (errorMessage != null && tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
            }
        } else if (filteredTracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_local_no_tracks), color = Color.Gray)
            }
        } else when (selectedGroup) {
            "Tracks" -> {
                val tracksLazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = tracksLazyListState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(filteredTracks, key = { index, t -> t.id ?: "local_${index}_${t.name.hashCode()}" }) { index, track ->
                            val cover = track.externalUri.takeIf { it?.isNotBlank() == true }
                            TrackRowItem(
                                index = index + 1,
                                track = track,
                                fallbackCoverUrl = cover,
                                onClick = { onTrackClick(filteredTracks, index) },
                                isLiked = track.id?.let { spotifyRepo.isLocalFavorite(it) } ?: false,
                                isCurrentTrack = track.id == currentTrackId,
                                onLikeToggle = { track.id?.let { spotifyRepo.toggleLocalFavorite(it) } },
                                onMoreClick = { selectedTrackForMenu = track }
                            )
                        }
                    }
                    VerticalScrollbarWithTooltip(
                        lazyListState = tracksLazyListState,
                        itemsCount = filteredTracks.size,
                        getDateForItem = { "" },
                        onDragStateChanged = { },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 8.dp, bottom = bottomPadding)
                    )
                }
            }
            "Albums" -> {
                val albumsLazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = albumsLazyListState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        albumGroups.forEach { (albumName, albumTracks) ->
                            item(key = "album_$albumName") {
                                val sortedTracks = albumTracks.sortedBy { it.name.lowercase() }
                                SearchResultRow(
                                    title = albumName,
                                    subtitle = "${albumTracks.size} tracks",
                                    imageUrl = albumTracks.firstOrNull()?.externalUri,
                                    onClick = {
                                        val localId = "local_album:$albumName"
                                        SpotifyRepository.localAlbumTracks[localId] = sortedTracks
                                        onAlbumClick(localId, albumName, emptyList())
                                    }
                                )
                            }
                        }
                    }
                    VerticalScrollbarWithTooltip(
                        lazyListState = albumsLazyListState,
                        itemsCount = albumGroups.size,
                        getDateForItem = { "" },
                        onDragStateChanged = { },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 8.dp, bottom = bottomPadding)
                    )
                }
            }
            "Artists" -> {
                val artistsLazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = artistsLazyListState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        artistGroups.forEach { (artistName, artistTracks) ->
                            item(key = "artist_$artistName") {
                                val sortedTracks = artistTracks.sortedBy { it.name.lowercase() }
                                SearchResultRow(
                                    title = artistName,
                                    subtitle = "${artistTracks.size} tracks",
                                    imageUrl = artistTracks.firstOrNull()?.externalUri,
                                    isCircle = true,
                                    onClick = {
                                        val localId = "local_artist:$artistName"
                                        SpotifyRepository.localArtistTracks[localId] = sortedTracks
                                        onArtistClick(localId)
                                    }
                                )
                            }
                        }
                    }
                    VerticalScrollbarWithTooltip(
                        lazyListState = artistsLazyListState,
                        itemsCount = artistGroups.size,
                        getDateForItem = { "" },
                        onDragStateChanged = { },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 8.dp, bottom = bottomPadding)
                    )
                }
            }

            // E30 — Playlists locales (crear/listar/navegar al detalle).
            "Playlists" -> {
                val playlistsLazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
                // Sin remember: lee el SnapshotStateList en composición → recompone al añadir/borrar.
                val playlists = spotifyRepo.localPlaylists.filter {
                    searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
                }
                var showCreateDialog by remember { mutableStateOf(false) }
                var newPlaylistName by remember { mutableStateOf("") }
                var pendingDelete by remember { mutableStateOf<LocalPlaylist?>(null) }
                val deletedMsg = stringResource(R.string.local_playlist_deleted)

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = playlistsLazyListState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = "create_local_playlist") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCreateDialog = true }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF1DB954))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.local_playlist_create),
                                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        items(playlists.size, key = { playlists[it].id }) { i ->
                            val pl = playlists[i]
                            LocalPlaylistRow(
                                playlist = pl,
                                resolvedTracks = remember(pl.id, pl.trackIds.size) {
                                    spotifyRepo.localPlaylistTracks(pl.id)
                                },
                                onClick = {
                                    val resolved = spotifyRepo.localPlaylistTracks(pl.id)
                                    SpotifyRepository.localPlaylistTracksCache[pl.id] = resolved
                                    onPlaylistClick(pl.id, pl.name, emptyList())
                                },
                                onDelete = { pendingDelete = pl }
                            )
                        }
                    }
                    VerticalScrollbarWithTooltip(
                        lazyListState = playlistsLazyListState,
                        itemsCount = playlists.size + 1,
                        getDateForItem = { "" },
                        onDragStateChanged = { },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 8.dp, bottom = bottomPadding)
                    )
                }

                if (showCreateDialog) {
                    AlertDialog(
                        onDismissRequest = { showCreateDialog = false; newPlaylistName = "" },
                        title = { Text(stringResource(R.string.local_playlist_create)) },
                        text = {
                            OutlinedTextField(
                                value = newPlaylistName,
                                onValueChange = { newPlaylistName = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.local_playlist_name_hint)) }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    spotifyRepo.createLocalPlaylist(newPlaylistName.trim())
                                }
                                showCreateDialog = false
                                newPlaylistName = ""
                            }) { Text(stringResource(R.string.local_playlist_create_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCreateDialog = false; newPlaylistName = "" }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    )
                }

                pendingDelete?.let { pl ->
                    AlertDialog(
                        onDismissRequest = { pendingDelete = null },
                        title = { Text(stringResource(R.string.local_playlist_delete)) },
                        text = { Text(stringResource(R.string.local_playlist_delete_msg)) },
                        confirmButton = {
                            TextButton(onClick = {
                                spotifyRepo.deleteLocalPlaylist(pl.id)
                                android.widget.Toast.makeText(context, deletedMsg, android.widget.Toast.LENGTH_SHORT).show()
                                pendingDelete = null
                            }) { Text(stringResource(R.string.local_playlist_delete_confirm), color = Color(0xFFCC2200)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDelete = null }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    )
                }
            }

            // E30 — Favoritos locales (tracks "local:" marcados como favoritos).
            "Favorites" -> {
                val favsLazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
                // Sin remember: localFavoriteTracks() lee localTracks + localFavoriteIds (observables).
                val favs = spotifyRepo.localFavoriteTracks().filter {
                    searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = favsLazyListState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(favs, key = { index, t -> t.id ?: "fav_${index}_${t.name.hashCode()}" }) { index, track ->
                            val cover = track.externalUri.takeIf { it?.isNotBlank() == true }
                            TrackRowItem(
                                index = index + 1,
                                track = track,
                                fallbackCoverUrl = cover,
                                onClick = { onTrackClick(favs, index) },
                                isLiked = true,
                                isCurrentTrack = track.id == currentTrackId,
                                onLikeToggle = { track.id?.let { spotifyRepo.toggleLocalFavorite(it) } },
                                onMoreClick = { selectedTrackForMenu = track }
                            )
                        }
                    }
                    if (favs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.local_favorites_empty), color = Color.Gray)
                        }
                    }
                    VerticalScrollbarWithTooltip(
                        lazyListState = favsLazyListState,
                        itemsCount = favs.size,
                        getDateForItem = { "" },
                        onDragStateChanged = { },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 8.dp, bottom = bottomPadding)
                    )
                }
            }
        }
    }


    // Bottom sheet accessible from both drill-down and main views
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
fun <T> LibraryContentList(
    isLoading: Boolean,
    errorMessage: String?,
    items: List<T>?,
    onRetry: () -> Unit,
    itemContent: @Composable (Int, T) -> Unit,
    emptyMessage: String,
    filterPredicate: ((T, String) -> Boolean)? = null,
    searchQuery: String = ""
) {
    val filteredItems = remember(items, searchQuery) {
        if (items == null) null
        else if (searchQuery.isBlank() || filterPredicate == null) items
        else items.filter { filterPredicate(it, searchQuery) }
    }

    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val bottomPadding = if (isLandscape) 16.dp else 100.dp
    
    Column(modifier = Modifier.fillMaxSize()) {
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
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomPadding)
                ) {
                    
                    if (filteredItems.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text(text = emptyMessage, color = Color.Gray)
                            }
                        }
                    } else {
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
            .widthIn(min = 16.dp)
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

/**
 * E30 — Row de playlist local con grid 2x2 de covers (deduplicados por álbum)
 * + botón de eliminar. Reemplaza a SearchResultRow que pasaba imageUrl=null (sin carátula).
 */
@Composable
private fun LocalPlaylistRow(
    playlist: LocalPlaylist,
    resolvedTracks: List<FullTrack>,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LocalPlaylistCoverGrid(tracks = resolvedTracks)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${playlist.trackIds.size} ${stringResource(R.string.local_group_tracks)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                maxLines = 1
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.local_playlist_delete),
                tint = Color.LightGray
            )
        }
    }
}

/** Grid 2x2 (hasta 4 covers) deduplicados por álbum para evitar caratulas idénticas. */
@Composable
private fun LocalPlaylistCoverGrid(tracks: List<FullTrack>) {
    val covers = remember(tracks) {
        tracks.asSequence()
            .mapNotNull { t ->
                val imgUrl = t.album?.images?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                    ?: t.externalUri?.takeIf { it.isNotBlank() }
                val key = t.album?.name?.takeIf { it.isNotBlank() } ?: imgUrl ?: t.id
                if (imgUrl != null) key to imgUrl else null
            }
            .distinctBy { it.first }
            .take(4)
            .map { it.second }
            .toList()
    }
    Surface(
        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
        color = Color.DarkGray
    ) {
        when (covers.size) {
            0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("♪", color = Color.White, fontSize = 22.sp)
            }
            1 -> AsyncImage(
                model = covers[0],
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            else -> Column(Modifier.fillMaxSize()) {
                val rows = covers.chunked(2)
                rows.forEach { rowItems ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        rowItems.forEach { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}


