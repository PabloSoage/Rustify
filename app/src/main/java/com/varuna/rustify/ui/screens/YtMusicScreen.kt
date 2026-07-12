package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.YtmAlbum
import com.varuna.rustify.bridge.YtmAlbumSlim
import com.varuna.rustify.bridge.YtmArtist
import com.varuna.rustify.bridge.YtmArtistRef
import com.varuna.rustify.bridge.YtmPlaylist
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.YtmTrack
import com.varuna.rustify.bridge.YtMusicRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private sealed class YtmView {
    data object Main : YtmView()
    data class AlbumDetail(val browseId: String, val title: String) : YtmView()
    data class ArtistDetail(val channelId: String, val name: String) : YtmView()
    data class PlaylistDetail(val playlistId: String, val title: String) : YtmView()
}

private enum class YtmTab(val label: String) {
    ALL("All"), FAVORITES("Favorites"), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtMusicScreen(
    repo: YtMusicRepository,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onBack: () -> Unit
) {
    val dark = Color(0xFF121212)
    val accent = Color(0xFF1DB954)
    val scope = rememberCoroutineScope()

    var view by remember { mutableStateOf<YtmView>(YtmView.Main) }
    var tab by remember { mutableStateOf(YtmTab.ALL) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<YtmSearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { results = null; searching = false; return }
        searchJob = scope.launch {
            searching = true; delay(400.milliseconds)
            try { results = repo.search(q) } catch (_: Exception) { results = null }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize().background(dark)) {
        when (val v = view) {
            is YtmView.Main -> MainContent(
                repo = repo, query = query,
                onQuery = { query = it; search(it) },
                tab = tab, onTab = { tab = it },
                results = results, searching = searching,
                onTrackClick = onTrackClick,
                onAlbum = { view = YtmView.AlbumDetail(it.first, it.second) },
                onArtist = { view = YtmView.ArtistDetail(it.first, it.second) },
                onPlaylist = { view = YtmView.PlaylistDetail(it.first, it.second) },
                onBack = onBack, dark = dark, accent = accent
            )
            is YtmView.AlbumDetail -> AlbumDetailView(
                repo, v.browseId, v.title, { view = YtmView.Main }, onTrackClick, dark, accent
            )
            is YtmView.ArtistDetail -> ArtistDetailView(
                repo, v.channelId, v.name, { view = YtmView.Main }, onTrackClick,
                { id, t -> view = YtmView.AlbumDetail(id, t) }, dark, accent
            )
            is YtmView.PlaylistDetail -> PlaylistDetailView(
                repo, v.playlistId, v.title, { view = YtmView.Main }, onTrackClick, dark, accent
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(
    repo: YtMusicRepository, query: String, onQuery: (String) -> Unit,
    tab: YtmTab, onTab: (YtmTab) -> Unit,
    results: YtmSearchResults?, searching: Boolean,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAlbum: (Pair<String, String>) -> Unit,
    onArtist: (Pair<String, String>) -> Unit,
    onPlaylist: (Pair<String, String>) -> Unit,
    onBack: () -> Unit, dark: Color, accent: Color
) {
    Column(Modifier.fillMaxSize()) {
        BackBar("YouTube Music", onBack)
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text("Search YouTube Music", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.White) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton({ onQuery("") }) {
                    Icon(Icons.Default.Clear, "Clear", tint = Color.White)
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF242424), unfocusedContainerColor = Color(0xFF242424),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = accent
            ),
            singleLine = true
        )
        SecondaryScrollableTabRow(
            selectedTabIndex = tab.ordinal, containerColor = dark, contentColor = Color.White,
            indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tab.ordinal), color = accent) }
        ) {
            YtmTab.entries.forEachIndexed { i, t ->
                Tab(selected = tab == t, onClick = { onTab(t) },
                    selectedContentColor = accent, unselectedContentColor = Color.LightGray,
                    text = {
                        Text(t.label, fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal)
                    })
            }
        }
        when (tab) {
            YtmTab.ALL -> AllTab(query, results, searching, onTrackClick, onAlbum, onArtist, onPlaylist)
            YtmTab.FAVORITES -> FavoritesTab(repo, onTrackClick, accent)
            YtmTab.PLAYLISTS -> PlaylistsTab(repo, onPlaylist, accent)
            YtmTab.ALBUMS -> AlbumsTab(repo, onAlbum, accent)
            YtmTab.ARTISTS -> ArtistsTab(repo, onArtist, accent)
        }
    }
}

// ── All tab ──

@Composable
private fun AllTab(
    query: String, results: YtmSearchResults?, searching: Boolean,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAlbum: (Pair<String, String>) -> Unit,
    onArtist: (Pair<String, String>) -> Unit,
    onPlaylist: (Pair<String, String>) -> Unit
) {
    if (searching) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Color(0xFF1DB954)) }
    } else if (results == null && query.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("Search for tracks, albums or playlists", color = Color.Gray, modifier = Modifier.padding(24.dp))
        }
    } else if (results != null) {
        val r = results!!
        LazyColumn(Modifier.fillMaxSize()) {
            if (r.tracks.isNotEmpty()) {
                item { SectionHeader("Tracks") }
                itemsIndexed(r.tracks) { i, t ->
                    YtmTrackRow(t, onClick = { onTrackClick(r.tracks.map { it.toFullTrack() }, i) })
                }
            }
            if (r.albums.isNotEmpty()) {
                item { SectionHeader("Albums") }
                items(r.albums) { a -> ResultRow(
                    a.title, a.year?.toString() ?: "Unknown year", a.thumbnailUrl, isCircle = false,
                    onClick = { onAlbum(a.browseId to a.title) }
                )}
            }
            if (r.artists.isNotEmpty()) {
                item { SectionHeader("Artists") }
                items(r.artists) { a -> ResultRow(
                    a.name, "Artist", null, isCircle = true,
                    onClick = { onArtist(a.id to a.name) }
                )}
            }
            if (r.playlists.isNotEmpty()) {
                item { SectionHeader("Playlists") }
                items(r.playlists) { p -> ResultRow(
                    p.title, p.author ?: "YouTube Music", p.thumbnailUrl, isCircle = false,
                    onClick = { onPlaylist(p.playlistId to p.title) }
                )}
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No results found", color = Color.Gray) }
    }
}

// ── Favorites tab ──

@Composable
private fun FavoritesTab(
    repo: YtMusicRepository, onTrackClick: (List<FullTrack>, Int) -> Unit, accent: Color
) {
    val f = repo.favorites.takeIf { it.isNotEmpty() } ?: run {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No favorite tracks yet", color = Color.Gray) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(f.toList()) { i, t ->
            YtmTrackRow(t,
                onClick = { onTrackClick(f.toList().map { it.toFullTrack() }, i) },
                trailing = {
                    IconButton(onClick = { repo.toggleFavorite(t) }) {
                        Icon(if (repo.isFavorite(t.videoId)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Favorite", tint = if (repo.isFavorite(t.videoId)) accent else Color.Gray)
                    }
                })
        }
    }
}

// ── Playlists tab ──

@Composable
private fun PlaylistsTab(
    repo: YtMusicRepository, onPlaylist: (Pair<String, String>) -> Unit, accent: Color
) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Button(
            onClick = { showDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Add, null, tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("Create Playlist", color = Color.Black)
        }
        val pls = repo.playlists
        if (pls.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No playlists yet", color = Color.Gray) }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(pls.toList()) { pl ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlaylist(pl.localId to pl.name) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), color = Color.DarkGray) {
                            Box(contentAlignment = Alignment.Center) { Text(pl.name.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pl.items.size} tracks", color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { repo.deletePlaylist(pl.localId) }) {
                            Icon(Icons.Default.Delete, "Delete", tint = Color.Gray)
                        }
                    }
                }
            }
        }
    }
    if (showDialog) AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text("New Playlist", color = Color.White) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                placeholder = { Text("Playlist name", color = Color.Gray) }, singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF242424), unfocusedContainerColor = Color(0xFF242424),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = accent
                )
            )
        },
        confirmButton = { TextButton({
            if (name.isNotBlank()) { repo.createPlaylist(name); name = ""; showDialog = false }
        }) { Text("Create", color = accent) } },
        dismissButton = { TextButton({ showDialog = false }) { Text("Cancel", color = Color.Gray) } },
        containerColor = Color(0xFF1E1E1E)
    )
}

// ── Albums tab ──

@Composable
private fun AlbumsTab(
    repo: YtMusicRepository, onAlbum: (Pair<String, String>) -> Unit, accent: Color
) {
    val a = repo.savedAlbums.takeIf { it.isNotEmpty() } ?: run {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No saved albums", color = Color.Gray) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(a.toList()) { alb ->
            ResultRow(alb.title, alb.year?.toString() ?: "Unknown year", alb.thumbnailUrl, isCircle = false,
                onClick = { onAlbum(alb.browseId to alb.title) },
                trailing = {
                    IconButton(onClick = { repo.toggleSavedAlbum(alb) }) {
                        Icon(if (repo.isAlbumSaved(alb.browseId)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Save", tint = if (repo.isAlbumSaved(alb.browseId)) accent else Color.Gray)
                    }
                })
        }
    }
}

// ── Artists tab ──

@Composable
private fun ArtistsTab(
    repo: YtMusicRepository, onArtist: (Pair<String, String>) -> Unit, accent: Color
) {
    val a = repo.savedArtists.takeIf { it.isNotEmpty() } ?: run {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No saved artists", color = Color.Gray) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(a.toList()) { art ->
            ResultRow(art.name, "Artist", null, isCircle = true,
                onClick = { onArtist(art.id to art.name) },
                trailing = {
                    IconButton(onClick = { repo.toggleSavedArtist(art) }) {
                        Icon(if (repo.isArtistSaved(art.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Save", tint = if (repo.isArtistSaved(art.id)) accent else Color.Gray)
                    }
                })
        }
    }
}

// ── Detail views ──

@Composable
private fun AlbumDetailView(
    repo: YtMusicRepository, browseId: String, title: String, onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit, dark: Color, accent: Color
) {
    var data by remember { mutableStateOf<YtmAlbum?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(browseId) { loading = true; data = repo.getAlbum(browseId); loading = false }

    Column(Modifier.fillMaxSize().background(dark)) {
        BackBar(title, onBack)
        when {
            loading -> CenteredLoading(accent)
            data == null -> CenteredText("Album not found")
            else -> {
                val d = data!!
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        DetailHeader(
                            thumbnail = d.thumbnailUrl, title = d.title, isCircle = false,
                            info1 = d.artists.joinToString(", ") { it.name },
                            info2 = d.year?.toString(),
                            saveLabel = if (repo.isAlbumSaved(browseId)) "Saved" else "Save",
                            isSaved = repo.isAlbumSaved(browseId),
                            accent = accent,
                            onSave = { repo.toggleSavedAlbum(YtmAlbumSlim(browseId, d.title, d.year, d.thumbnailUrl)) }
                        )
                        PlayAllButton { val t = d.tracks.map { it.toFullTrack() }; onTrackClick(t, 0) }
                    }
                    itemsIndexed(d.tracks) { i, t ->
                        YtmTrackRow(t, onClick = { onTrackClick(d.tracks.map { it.toFullTrack() }, i) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistDetailView(
    repo: YtMusicRepository, channelId: String, name: String, onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAlbum: (String, String) -> Unit, dark: Color, accent: Color
) {
    var data by remember { mutableStateOf<YtmArtist?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(channelId) { loading = true; data = repo.getArtist(channelId); loading = false }

    Column(Modifier.fillMaxSize().background(dark)) {
        BackBar(name, onBack)
        when {
            loading -> CenteredLoading(accent)
            data == null -> CenteredText("Artist not found")
            else -> {
                val d = data!!
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        DetailHeader(
                            thumbnail = d.thumbnailUrl, title = d.name, isCircle = true,
                            saveLabel = if (repo.isArtistSaved(channelId)) "Saved" else "Save",
                            isSaved = repo.isArtistSaved(channelId),
                            accent = accent,
                            onSave = { repo.toggleSavedArtist(YtmArtistRef(channelId, d.name)) }
                        )
                    }
                    if (d.topTracks.isNotEmpty()) {
                        item { SectionHeader("Top Tracks") }
                        item { PlayAllButton { val t = d.topTracks.map { it.toFullTrack() }; onTrackClick(t, 0) } }
                        itemsIndexed(d.topTracks) { i, t ->
                            YtmTrackRow(t, onClick = { onTrackClick(d.topTracks.map { it.toFullTrack() }, i) })
                        }
                    }
                    if (d.albums.isNotEmpty()) {
                        item { SectionHeader("Albums") }
                        items(d.albums) { a -> ResultRow(
                            a.title, a.year?.toString() ?: "Unknown year", a.thumbnailUrl, isCircle = false,
                            onClick = { onAlbum(a.browseId, a.title) }
                        )}
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailView(
    repo: YtMusicRepository, playlistId: String, title: String, onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit, dark: Color, accent: Color
) {
    var data by remember { mutableStateOf<YtmPlaylist?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(playlistId) { loading = true; data = repo.getPlaylist(playlistId); loading = false }

    Column(Modifier.fillMaxSize().background(dark)) {
        BackBar(title, onBack)
        when {
            loading -> CenteredLoading(accent)
            data == null -> CenteredText("Playlist not found")
            else -> {
                val d = data!!
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        DetailHeader(
                            thumbnail = d.thumbnailUrl, title = d.title, isCircle = false,
                            info1 = d.author, info2 = "${d.tracks.size} tracks"
                        )
                        PlayAllButton { val t = d.tracks.map { it.toFullTrack() }; onTrackClick(t, 0) }
                    }
                    itemsIndexed(d.tracks) { i, t ->
                        YtmTrackRow(t, onClick = { onTrackClick(d.tracks.map { it.toFullTrack() }, i) })
                    }
                }
            }
        }
    }
}

// ── Shared components ──

@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold, color = Color.White),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun CenteredLoading(accent: Color) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = accent) }
}

@Composable
private fun CenteredText(msg: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(msg, color = Color.Gray) }
}

@Composable
private fun PlayAllButton(onClick: () -> Unit) {
    Button(onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
        Spacer(Modifier.width(4.dp))
        Text("Play All", color = Color.Black)
    }
}

@Composable
private fun DetailHeader(
    thumbnail: String, title: String, isCircle: Boolean,
    info1: String? = null, info2: String? = null,
    saveLabel: String? = null, isSaved: Boolean = false,
    accent: Color = Color(0xFF1DB954),
    onSave: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        val shape = if (isCircle) RoundedCornerShape(60.dp) else RoundedCornerShape(12.dp)
        Surface(Modifier.size(120.dp).clip(shape), color = Color.DarkGray) {
            if (thumbnail.isNotEmpty()) {
                AsyncImage(thumbnail, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            if (info1 != null) { Spacer(Modifier.height(4.dp)); Text(info1, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium) }
            if (info2 != null) { Text(info2, color = Color.Gray, style = MaterialTheme.typography.bodyMedium) }
            if (onSave != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSave, colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSaved) accent else Color.DarkGray)) {
                    Text(saveLabel ?: "Save", color = if (isSaved) Color.Black else Color.White)
                }
            }
        }
    }
}

// ── Track row ──

@Composable
fun YtmTrackRow(
    track: YtmTrack, onClick: () -> Unit, modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)), color = Color.DarkGray) {
            if (track.thumbnailUrl.isNotEmpty()) {
                AsyncImage(track.thumbnailUrl, track.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text(track.title.take(1).uppercase(), color = Color.White) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.isExplicit) {
                    Box(Modifier.padding(end = 6.dp).background(Color.Gray.copy(0.6f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)) {
                        Text("E", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color.Black)
                    }
                }
                Text(track.artists.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(formatSec(track.durationSec), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
        trailing?.invoke()
    }
}

// ── Generic result row (albums / artists / playlists) ──

@Composable
private fun ResultRow(
    title: String, subtitle: String, imageUrl: String?, isCircle: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val shape = if (isCircle) RoundedCornerShape(28.dp) else RoundedCornerShape(4.dp)
        Surface(Modifier.size(56.dp).clip(shape), color = Color.DarkGray) {
            if (!imageUrl.isNullOrEmpty()) {
                AsyncImage(imageUrl, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

private fun formatSec(seconds: Int): String {
    val m = seconds / 60; val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
