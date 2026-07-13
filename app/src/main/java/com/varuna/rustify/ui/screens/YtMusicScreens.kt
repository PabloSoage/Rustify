package com.varuna.rustify.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.YtmAlbum
import com.varuna.rustify.bridge.YtmAlbumSlim
import com.varuna.rustify.bridge.YtmArtist
import com.varuna.rustify.bridge.YtmArtistRef
import com.varuna.rustify.bridge.YtmPlaylist
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.YtmTrack
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.ui.components.TrackRowItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =============================================================================
// E40 — YouTube Music top-level screens (first-class NavHost destinations).
//
// These replace the old embedded YtMusicScreen "screen-in-a-screen". Each screen
// owns a single system TopAppBar (real back button), reuses TrackRowItem and the
// app theme (spotify green 0xFF1DB954), and localizes via stringResource.
// Playback is delegated to the existing `ytm:` pipeline via onTrackClick +
// YtmTrack.toFullTrack() — no AudioPlayerService changes.
// =============================================================================

private val YtmGreen = Color(0xFF1DB954)
private val YtmDark = Color(0xFF121212)

/** Convert a list of YTM tracks to FullTracks (id = "ytm:videoId") for the player. */
private fun List<YtmTrack>.toFullTracks(): List<FullTrack> = map { it.toFullTrack() }

// -----------------------------------------------------------------------------
// Search screen
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtMusicSearchScreen(
    repo: YtMusicRepository,
    onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAlbum: (browseId: String, title: String) -> Unit,
    onArtist: (channelId: String, name: String) -> Unit,
    onPlaylist: (playlistId: String, title: String) -> Unit,
    currentTrackId: String? = null
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<YtmSearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun runSearch(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { results = null; searching = false; return }
        searchJob = scope.launch {
            searching = true
            delay(400)
            results = repo.search(q)
            searching = false
        }
    }

    Scaffold(
        containerColor = YtmDark,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ytm_title), color = Color.White, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = YtmDark)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; runSearch(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text(stringResource(R.string.ytm_search_placeholder), color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.White) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; runSearch("") }) {
                        Icon(Icons.Default.Clear, "Clear", tint = Color.White)
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF242424),
                    unfocusedContainerColor = Color(0xFF242424),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = YtmGreen
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch(query) })
            )

            when {
                searching -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = YtmGreen)
                }
                results == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(stringResource(R.string.ytm_search_empty), color = Color.Gray,
                        modifier = Modifier.padding(24.dp))
                }
                results!!.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(stringResource(R.string.ytm_no_results), color = Color.Gray)
                }
                else -> {
                    val r = results!!
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                        if (r.tracks.isNotEmpty()) {
                            item { YtmSectionHeader(stringResource(R.string.ytm_section_tracks)) }
                            itemsIndexed(r.tracks) { i, t ->
                                YtmTrackListItem(
                                    track = t, index = i, currentTrackId = currentTrackId,
                                    isFavorite = repo.isFavorite(t.videoId),
                                    onFavoriteToggle = { repo.toggleFavorite(t) },
                                    onClick = { onTrackClick(r.tracks.toFullTracks(), i) }
                                )
                            }
                        }
                        if (r.albums.isNotEmpty()) {
                            item { YtmSectionHeader(stringResource(R.string.ytm_section_albums)) }
                            items(r.albums) { a ->
                                YtmResultRow(a.title, a.year?.toString() ?: "", a.thumbnailUrl, false) {
                                    onAlbum(a.browseId, a.title)
                                }
                            }
                        }
                        if (r.artists.isNotEmpty()) {
                            item { YtmSectionHeader(stringResource(R.string.ytm_section_artists)) }
                            items(r.artists) { a ->
                                YtmResultRow(a.name, stringResource(R.string.ytm_artist_detail), null, true) {
                                    onArtist(a.id, a.name)
                                }
                            }
                        }
                        if (r.playlists.isNotEmpty()) {
                            item { YtmSectionHeader(stringResource(R.string.ytm_section_playlists)) }
                            items(r.playlists) { p ->
                                YtmResultRow(p.title, p.author ?: "", p.thumbnailUrl, false) {
                                    onPlaylist(p.playlistId, p.title)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun YtmSearchResults.isEmpty() =
    tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()

// -----------------------------------------------------------------------------
// Album detail
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtMusicAlbumScreen(
    repo: YtMusicRepository,
    browseId: String,
    title: String,
    onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    currentTrackId: String? = null
) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<YtmAlbum?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(browseId, attempt) { loading = true; data = repo.getAlbum(browseId); loading = false }

    YtmDetailScaffold(title, onBack) { padding ->
        when {
            loading -> YtmLoading(padding)
            data == null -> YtmError(padding) { attempt++ }
            else -> {
                val d = data!!
                LazyColumn(Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 100.dp)) {
                    item {
                        YtmDetailHeader(
                            thumbnail = d.thumbnailUrl, title = d.title, isCircle = false,
                            info1 = d.artists.joinToString(", ") { it.name },
                            info2 = d.year?.toString(),
                            isSaved = repo.isAlbumSaved(browseId),
                            onSave = {
                                val wasSaved = repo.isAlbumSaved(browseId)
                                repo.toggleSavedAlbum(YtmAlbumSlim(browseId, d.title, d.year, d.thumbnailUrl))
                                context.ytmToast(if (wasSaved) R.string.ytm_removed_album else R.string.ytm_saved_album)
                            },
                            onPlayAll = { onTrackClick(d.tracks.toFullTracks(), 0) }
                        )
                    }
                    itemsIndexed(d.tracks) { i, t ->
                        YtmTrackListItem(
                            track = t, index = i, currentTrackId = currentTrackId,
                            isFavorite = repo.isFavorite(t.videoId),
                            onFavoriteToggle = { repo.toggleFavorite(t) },
                            onClick = { onTrackClick(d.tracks.toFullTracks(), i) }
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Artist detail
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtMusicArtistScreen(
    repo: YtMusicRepository,
    channelId: String,
    name: String,
    onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onAlbum: (browseId: String, title: String) -> Unit,
    currentTrackId: String? = null
) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<YtmArtist?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(channelId, attempt) { loading = true; data = repo.getArtist(channelId); loading = false }

    YtmDetailScaffold(name, onBack) { padding ->
        when {
            loading -> YtmLoading(padding)
            data == null -> YtmError(padding) { attempt++ }
            else -> {
                val d = data!!
                LazyColumn(Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 100.dp)) {
                    item {
                        YtmDetailHeader(
                            thumbnail = d.thumbnailUrl, title = d.name, isCircle = true,
                            info1 = null, info2 = null,
                            isSaved = repo.isArtistSaved(channelId),
                            onSave = {
                                val wasSaved = repo.isArtistSaved(channelId)
                                repo.toggleSavedArtist(YtmArtistRef(channelId, d.name))
                                context.ytmToast(if (wasSaved) R.string.ytm_removed_artist else R.string.ytm_saved_artist)
                            },
                            onPlayAll = if (d.topTracks.isNotEmpty()) {
                                { onTrackClick(d.topTracks.toFullTracks(), 0) }
                            } else null
                        )
                    }
                    if (d.topTracks.isNotEmpty()) {
                        item { YtmSectionHeader(stringResource(R.string.ytm_top_tracks)) }
                        itemsIndexed(d.topTracks) { i, t ->
                            YtmTrackListItem(
                                track = t, index = i, currentTrackId = currentTrackId,
                                isFavorite = repo.isFavorite(t.videoId),
                                onFavoriteToggle = { repo.toggleFavorite(t) },
                                onClick = { onTrackClick(d.topTracks.toFullTracks(), i) }
                            )
                        }
                    }
                    if (d.albums.isNotEmpty()) {
                        item { YtmSectionHeader(stringResource(R.string.ytm_section_albums)) }
                        items(d.albums) { a ->
                            YtmResultRow(a.title, a.year?.toString() ?: "", a.thumbnailUrl, false) {
                                onAlbum(a.browseId, a.title)
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Remote playlist detail
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtMusicPlaylistScreen(
    repo: YtMusicRepository,
    playlistId: String,
    title: String,
    onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    currentTrackId: String? = null
) {
    var data by remember { mutableStateOf<YtmPlaylist?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(playlistId, attempt) { loading = true; data = repo.getPlaylist(playlistId); loading = false }

    YtmDetailScaffold(title, onBack) { padding ->
        when {
            loading -> YtmLoading(padding)
            data == null -> YtmError(padding) { attempt++ }
            else -> {
                val d = data!!
                LazyColumn(Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 100.dp)) {
                    item {
                        YtmDetailHeader(
                            thumbnail = d.thumbnailUrl, title = d.title, isCircle = false,
                            info1 = d.author, info2 = "${d.tracks.size}",
                            isSaved = null, onSave = null,
                            onPlayAll = { onTrackClick(d.tracks.toFullTracks(), 0) }
                        )
                    }
                    itemsIndexed(d.tracks) { i, t ->
                        YtmTrackListItem(
                            track = t, index = i, currentTrackId = currentTrackId,
                            isFavorite = repo.isFavorite(t.videoId),
                            onFavoriteToggle = { repo.toggleFavorite(t) },
                            onClick = { onTrackClick(d.tracks.toFullTracks(), i) }
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Local (saved) playlist detail
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtMusicLocalPlaylistScreen(
    repo: YtMusicRepository,
    localId: String,
    onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    currentTrackId: String? = null
) {
    // Read the reactive list so add/remove recomposes.
    val pl = repo.playlists.firstOrNull { it.localId == localId }

    YtmDetailScaffold(pl?.name ?: "", onBack) { padding ->
        if (pl == null) {
            YtmNotFound(padding)
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 100.dp)) {
                item {
                    YtmDetailHeader(
                        thumbnail = pl.items.firstOrNull()?.thumbnailUrl ?: "",
                        title = pl.name, isCircle = false,
                        info1 = null, info2 = "${pl.items.size}",
                        isSaved = null, onSave = null,
                        onPlayAll = if (pl.items.isNotEmpty()) {
                            { onTrackClick(pl.items.toFullTracks(), 0) }
                        } else null
                    )
                }
                itemsIndexed(pl.items) { i, t ->
                    YtmTrackListItem(
                        track = t, index = i, currentTrackId = currentTrackId,
                        isFavorite = repo.isFavorite(t.videoId),
                        onFavoriteToggle = { repo.toggleFavorite(t) },
                        onClick = { onTrackClick(pl.items.toFullTracks(), i) }
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Shared components
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YtmDetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = YtmDark,
        topBar = {
            TopAppBar(
                title = { Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = YtmDark)
            )
        },
        content = content
    )
}

@Composable
private fun YtmLoading(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
        CircularProgressIndicator(color = YtmGreen)
    }
}

@Composable
private fun YtmNotFound(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
        Text(stringResource(R.string.ytm_not_found), color = Color.Gray)
    }
}

/** Error state for remote loads: a graceful message + a retry button (no crash on null). */
@Composable
private fun YtmError(padding: PaddingValues, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(stringResource(R.string.ytm_load_error), color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = YtmGreen)
            ) {
                Text(stringResource(R.string.ytm_retry), color = Color.Black)
            }
        }
    }
}

@Composable
fun YtmSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** A YTM track rendered through the shared TrackRowItem, with a favorite heart. */
@Composable
fun YtmTrackListItem(
    track: YtmTrack,
    index: Int,
    currentTrackId: String?,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit
) {
    val full = remember(track) { track.toFullTrack() }
    TrackRowItem(
        index = index + 1,
        track = full,
        fallbackCoverUrl = track.thumbnailUrl.takeIf { it.isNotBlank() },
        onClick = onClick,
        isLiked = isFavorite,
        isCurrentTrack = full.id == currentTrackId,
        onLikeToggle = onFavoriteToggle
    )
}

/** Album / artist / playlist result row (image + title + subtitle). */
@Composable
fun YtmResultRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    isCircle: Boolean,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val shape = if (isCircle) CircleShape else RoundedCornerShape(4.dp)
        Surface(Modifier.size(56.dp).clip(shape), color = Color.DarkGray) {
            if (!imageUrl.isNullOrBlank()) {
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
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun YtmDetailHeader(
    thumbnail: String,
    title: String,
    isCircle: Boolean,
    info1: String?,
    info2: String?,
    isSaved: Boolean?,
    onSave: (() -> Unit)?,
    onPlayAll: (() -> Unit)?
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            val shape = if (isCircle) CircleShape else RoundedCornerShape(12.dp)
            Surface(Modifier.size(120.dp).clip(shape), color = Color.DarkGray) {
                if (thumbnail.isNotBlank()) {
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
                if (!info1.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(info1, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                }
                if (!info2.isNullOrBlank()) {
                    Text(info2, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
                if (isSaved != null && onSave != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSaved) YtmGreen else Color.DarkGray)
                    ) {
                        Icon(
                            if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            null, tint = if (isSaved) Color.Black else Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (isSaved) R.string.ytm_unsave else R.string.ytm_save),
                            color = if (isSaved) Color.Black else Color.White
                        )
                    }
                }
            }
        }
        if (onPlayAll != null) {
            Button(
                onClick = onPlayAll,
                colors = ButtonDefaults.buttonColors(containerColor = YtmGreen),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ytm_play_all), color = Color.Black)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Short, localized save/favorite feedback. Kept here so all YTM screens share it. */
internal fun Context.ytmToast(@StringRes msg: Int) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
