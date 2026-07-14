package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.bridge.YtmAlbumSlim
import com.varuna.rustify.bridge.YtmArtistRef
import com.varuna.rustify.bridge.YtmPlaylist
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.YtmTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * E40 — YouTube Music LIBRARY content (embedded inside LibraryScreen's own header).
 *
 * This is NOT a full screen: it has no back button, no search bar and no background of
 * its own — the outer LibraryScreen provides the search bar + tabs. It shows only the
 * user's LOCAL YTM library (favorites / saved playlists / saved albums / saved artists)
 * plus an entry point that navigates to the first-class [YtMusicSearchScreen].
 *
 * All navigation (search / album / artist / playlist detail) is delegated to the NavHost
 * in MainActivity via callbacks — there is no local navigation state here.
 */

private enum class YtmLibSection { EXPLORE, FAVORITES, PLAYLISTS, ALBUMS, ARTISTS }

private val Green = Color(0xFF1DB954)

@Composable
fun YtMusicLibraryContent(
    repo: YtMusicRepository,
    searchQuery: String = "",
    onPasteLink: () -> Unit = {},
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onOpenAlbum: (browseId: String, title: String) -> Unit,
    onOpenArtist: (channelId: String, name: String) -> Unit,
    onOpenLocalPlaylist: (localId: String) -> Unit,
    onOpenPlaylist: (playlistId: String, title: String) -> Unit = { _, _ -> },
    onAddToQueue: (List<FullTrack>) -> Unit = {},
    onGoToQueue: () -> Unit = {},
    onAddToPlaylist: (YtmTrack) -> Unit = {},
    resultFilter: String = "all",
    onFilterChanged: (String) -> Unit = {},
    useScraper: Boolean = false,
    onToggleScraper: () -> Unit = {},
    currentTrackId: String? = null,
    modifier: Modifier = Modifier
) {
    var section by rememberSaveable { mutableStateOf(YtmLibSection.EXPLORE) }

    // ── Inline search for the Explore tab (driven by the parent's search bar) ──
    var exploreResults by remember { mutableStateOf<YtmSearchResults?>(null) }
    var exploreSearching by remember { mutableStateOf(false) }
    val resultCache = remember { mutableMapOf<String, YtmSearchResults?>() }

    val effectiveQuery = searchQuery.trim()
    LaunchedEffect(effectiveQuery, useScraper) {
        if (effectiveQuery.isBlank()) {
            exploreResults = null
            exploreSearching = false
        } else {
            val cacheKey = "$effectiveQuery|$useScraper"
            val cached = resultCache[cacheKey]
            if (cached != null) {
                exploreResults = cached
                exploreSearching = false
            } else {
                exploreSearching = true
                delay(400)
                val r = if (useScraper) repo.searchScraper(effectiveQuery) else repo.search(effectiveQuery)
                resultCache[cacheKey] = r
                exploreResults = r
                exploreSearching = false
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // ── Section chips ──
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val chips = listOf(
                YtmLibSection.EXPLORE to R.string.ytm_tab_explore,
                YtmLibSection.FAVORITES to R.string.ytm_tab_favorites,
                YtmLibSection.PLAYLISTS to R.string.ytm_tab_playlists,
                YtmLibSection.ALBUMS to R.string.ytm_tab_albums,
                YtmLibSection.ARTISTS to R.string.ytm_tab_artists
            )
            items(chips.size) { i ->
                val (s, label) = chips[i]
                FilterChip(
                    selected = section == s,
                    onClick = { section = s },
                    label = { Text(stringResource(label)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Green,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        when (section) {
            YtmLibSection.EXPLORE -> ExploreSection(
                results = exploreResults,
                searching = exploreSearching,
                query = effectiveQuery,
                repo = repo,
                onTrackClick = onTrackClick,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                onOpenPlaylist = onOpenPlaylist,
                onAddToQueue = onAddToQueue,
                onGoToQueue = onGoToQueue,
                onAddToPlaylist = onAddToPlaylist,
                resultFilter = resultFilter,
                currentTrackId = currentTrackId
            )
            YtmLibSection.FAVORITES -> FavoritesSection(
                repo, onTrackClick, onAddToQueue, onGoToQueue, onAddToPlaylist,
                onOpenArtist, onOpenAlbum, currentTrackId
            )
            YtmLibSection.PLAYLISTS -> PlaylistsSection(repo, onOpenLocalPlaylist)
            YtmLibSection.ALBUMS -> AlbumsSection(repo, onOpenAlbum)
            YtmLibSection.ARTISTS -> ArtistsSection(repo, onOpenArtist)
        }
    }
}

/**
 * E40 — Home/browse por defecto de la pestaña Explore de YTM: filas por mood/categoría (usando el
 * search de YTM que ya existe), para que no esté vacía cuando no tienes nada guardado ni query.
 */
@Composable
private fun YtmBrowseHome(
    repo: YtMusicRepository,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    currentTrackId: String?
) {
    val categories = remember {
        listOf(
            R.string.ytm_home_top to "top hits",
            R.string.dj_mood_chill to "chill lofi relax",
            R.string.dj_mood_energetic to "energetic workout gym",
            R.string.dj_mood_happy to "feel good happy hits",
            R.string.dj_mood_focus to "focus instrumental study"
        )
    }
    val cache = remember { mutableMapOf<String, List<YtmTrack>>() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp)) {
        item {
            Text(
                stringResource(R.string.ytm_home_browse),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )
        }
        items(categories) { (labelRes, q) ->
            YtmBrowseRow(labelRes, q, repo, onTrackClick, currentTrackId, cache)
        }
    }
}

@Composable
private fun YtmBrowseRow(
    labelRes: Int,
    query: String,
    repo: YtMusicRepository,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    currentTrackId: String?,
    cache: MutableMap<String, List<YtmTrack>>
) {
    val cached = cache[query]
    var tracks by remember(query) { mutableStateOf(cached ?: emptyList()) }
    var loading by remember(query) { mutableStateOf(cached == null) }
    LaunchedEffect(query) {
        if (cached != null) return@LaunchedEffect
        loading = true
        val result = runCatching { repo.search(query).tracks.take(12) }.getOrDefault(emptyList())
        tracks = result
        cache[query] = result
        loading = false
    }
    if (!loading && tracks.isEmpty()) return
    Column(Modifier.padding(vertical = 8.dp)) {
        YtmSectionHeader(stringResource(labelRes))
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                CircularProgressIndicator(color = Green, modifier = Modifier.size(22.dp))
            }
        } else {
            val full = tracks.map { it.toFullTrack() }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(tracks) { i, t ->
                    val playing = currentTrackId == "ytm:${t.videoId}"
                    Column(Modifier.width(140.dp).clickable { onTrackClick(full, i) }) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(t.thumbnailUrl)
                                .crossfade(true)
                                .size(420)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(t.title, color = if (playing) Green else Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artists.joinToString(", ") { it.name }, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesSection(
    repo: YtMusicRepository,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAddToQueue: (List<FullTrack>) -> Unit,
    onGoToQueue: () -> Unit,
    onAddToPlaylist: (YtmTrack) -> Unit,
    onOpenArtist: (channelId: String, name: String) -> Unit,
    onOpenAlbum: (browseId: String, title: String) -> Unit,
    currentTrackId: String?
) {
    val favorites = repo.favorites
    if (favorites.isEmpty()) {
        EmptyMessage(stringResource(R.string.ytm_no_favorites)); return
    }
    val full = favorites.map { it.toFullTrack() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
        itemsIndexed(favorites.toList()) { i, t ->
            YtmTrackListItem(
                track = t, index = i, currentTrackId = currentTrackId,
                isFavorite = true,
                onFavoriteToggle = { repo.toggleFavorite(t) },
                onClick = { onTrackClick(full, i) },
                onAddToQueue = { onAddToQueue(listOf(t.toFullTrack())) },
                onGoToQueue = onGoToQueue,
                onAddToPlaylist = { onAddToPlaylist(t) },
                onOpenArtist = { id, name -> onOpenArtist(id, name) },
                onOpenAlbum = { id, _ -> if (id.isNotBlank()) onOpenAlbum(id, "") }
            )
        }
    }
}

@Composable
private fun PlaylistsSection(
    repo: YtMusicRepository,
    onOpenLocalPlaylist: (String) -> Unit
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val playlists = repo.playlists

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { showDialog = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = Green)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.ytm_create_playlist), color = Color.White,
                    fontWeight = FontWeight.Medium)
            }
        }
        if (playlists.isEmpty()) {
            item { EmptyMessage(stringResource(R.string.ytm_no_playlists)) }
        } else {
            items(playlists.toList(), key = { it.localId }) { pl ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenLocalPlaylist(pl.localId) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), color = Color.DarkGray) {
                        val covers = pl.items.mapNotNull { it.thumbnailUrl.takeIf { u -> u.isNotBlank() } }.distinct().take(4)
                        if (covers.isEmpty()) {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                Text(pl.name.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else if (covers.size == 1) {
                            AsyncImage(covers[0], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            val rows = covers.chunked(2)
                            Column(Modifier.fillMaxSize()) {
                                rows.forEach { row ->
                                    Row(Modifier.weight(1f)) {
                                        row.forEach { url ->
                                            AsyncImage(url, null, Modifier.weight(1f).fillMaxHeight(),
                                                contentScale = ContentScale.Crop)
                                        }
                                        if (row.size == 1) Box(Modifier.weight(1f).fillMaxHeight().background(Color.DarkGray))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pl.items.size}", color = Color.LightGray,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { repo.deletePlaylist(pl.localId) }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.ytm_delete), tint = Color.Gray)
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false; name = "" },
            title = { Text(stringResource(R.string.ytm_create_playlist)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.ytm_playlist_name_hint)) },
                    colors = TextFieldDefaults.colors(cursorColor = Green)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        repo.createPlaylist(name.trim())
                        context.ytmToast(R.string.ytm_playlist_created)
                    }
                    name = ""; showDialog = false
                }) { Text(stringResource(R.string.ytm_create_playlist), color = Green) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false; name = "" }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AlbumsSection(
    repo: YtMusicRepository,
    onOpenAlbum: (String, String) -> Unit
) {
    val albums = repo.savedAlbums
    if (albums.isEmpty()) { EmptyMessage(stringResource(R.string.ytm_no_albums)); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
        items(albums.toList(), key = { it.browseId }) { a ->
            YtmResultRow(
                title = a.title, subtitle = a.year?.toString() ?: "", imageUrl = a.thumbnailUrl,
                isCircle = false,
                trailing = {
                    IconButton(onClick = { repo.toggleSavedAlbum(a) }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.ytm_delete), tint = Color.Gray)
                    }
                },
                onClick = { onOpenAlbum(a.browseId, a.title) }
            )
        }
    }
}

@Composable
private fun ArtistsSection(
    repo: YtMusicRepository,
    onOpenArtist: (String, String) -> Unit
) {
    val artists = repo.savedArtists
    if (artists.isEmpty()) { EmptyMessage(stringResource(R.string.ytm_no_artists)); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
        items(artists.toList(), key = { it.id }) { a ->
            YtmResultRow(
                title = a.name, subtitle = stringResource(R.string.ytm_artist_detail),
                imageUrl = null, isCircle = true,
                trailing = {
                    IconButton(onClick = { repo.toggleSavedArtist(a) }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.ytm_delete), tint = Color.Gray)
                    }
                },
                onClick = { onOpenArtist(a.id, a.name) }
            )
        }
    }
}

@Composable
private fun ExploreSection(
    results: YtmSearchResults?,
    searching: Boolean,
    query: String,
    repo: YtMusicRepository,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onOpenAlbum: (browseId: String, title: String) -> Unit,
    onOpenArtist: (channelId: String, name: String) -> Unit,
    onOpenPlaylist: (playlistId: String, title: String) -> Unit,
    onAddToQueue: (List<FullTrack>) -> Unit,
    onGoToQueue: () -> Unit,
    onAddToPlaylist: (YtmTrack) -> Unit,
    resultFilter: String,
    currentTrackId: String?
) {
    if (query.isBlank()) {
        // Default browse home so Explore isn't empty when you have nothing saved / no query yet.
        YtmBrowseHome(repo, onTrackClick, currentTrackId)
        return
    }
    if (searching) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Green)
        }
        return
    }
    if (results == null || results.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(stringResource(R.string.ytm_no_results), color = Color.Gray)
        }
        return
    }
    val r = results
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
        if (r.tracks.isNotEmpty() && (resultFilter == "all" || resultFilter == "tracks")) {
            item { YtmSectionHeader(stringResource(R.string.ytm_section_tracks)) }
            itemsIndexed(r.tracks) { i, t ->
                YtmTrackListItem(
                    track = t, index = i, currentTrackId = currentTrackId,
                    isFavorite = repo.isFavorite(t.videoId),
                    onFavoriteToggle = { repo.toggleFavorite(t) },
                    onClick = { onTrackClick(r.tracks.map { it.toFullTrack() }, i) },
                    onAddToQueue = { onAddToQueue(listOf(t.toFullTrack())) },
                    onGoToQueue = onGoToQueue,
                    onAddToPlaylist = { onAddToPlaylist(t) },
                    onOpenArtist = { id, name -> onOpenArtist(id, name) },
                    onOpenAlbum = { id, _ -> if (id.isNotBlank()) onOpenAlbum(id, "") }
                )
            }
        }
        if (r.albums.isNotEmpty() && (resultFilter == "all" || resultFilter == "albums")) {
            item { YtmSectionHeader(stringResource(R.string.ytm_section_albums)) }
            items(r.albums) { a ->
                YtmResultRow(a.title, a.year?.toString() ?: "", a.thumbnailUrl, false) {
                    onOpenAlbum(a.browseId, a.title)
                }
            }
        }
        if (r.artists.isNotEmpty() && (resultFilter == "all" || resultFilter == "artists")) {
            item { YtmSectionHeader(stringResource(R.string.ytm_section_artists)) }
            items(r.artists) { a ->
                YtmResultRow(a.name, stringResource(R.string.ytm_artist_detail), null, true) {
                    onOpenArtist(a.id, a.name)
                }
            }
        }
        if (r.playlists.isNotEmpty() && (resultFilter == "all" || resultFilter == "playlists")) {
            item { YtmSectionHeader(stringResource(R.string.ytm_section_playlists)) }
            items(r.playlists) { p ->
                YtmResultRow(p.title, p.author ?: "", p.thumbnailUrl, false) {
                    onOpenPlaylist(p.playlistId, p.title)
                }
            }
        }
    }
}

private fun YtmSearchResults.isEmpty() =
    tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()

@Composable
private fun EmptyMessage(msg: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(msg, color = Color.Gray, modifier = Modifier.padding(24.dp))
    }
}
