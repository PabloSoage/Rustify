package com.varuna.rustify.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.YtMusicRepository

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

private enum class YtmLibSection { FAVORITES, PLAYLISTS, ALBUMS, ARTISTS }

private val Green = Color(0xFF1DB954)

@Composable
fun YtMusicLibraryContent(
    repo: YtMusicRepository,
    onOpenSearch: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onOpenAlbum: (browseId: String, title: String) -> Unit,
    onOpenArtist: (channelId: String, name: String) -> Unit,
    onOpenLocalPlaylist: (localId: String) -> Unit,
    currentTrackId: String? = null,
    modifier: Modifier = Modifier
) {
    var section by rememberSaveable { mutableStateOf(YtmLibSection.FAVORITES) }

    Column(modifier.fillMaxSize()) {
        // Entry point: open the full-screen search (single header, real back button).
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenSearch)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = Green)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.ytm_search_action), color = Color.White,
                fontWeight = FontWeight.Medium)
        }

        // Section chips.
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val chips = listOf(
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
            YtmLibSection.FAVORITES -> FavoritesSection(repo, onTrackClick, currentTrackId)
            YtmLibSection.PLAYLISTS -> PlaylistsSection(repo, onOpenLocalPlaylist)
            YtmLibSection.ALBUMS -> AlbumsSection(repo, onOpenAlbum)
            YtmLibSection.ARTISTS -> ArtistsSection(repo, onOpenArtist)
        }
    }
}

@Composable
private fun FavoritesSection(
    repo: YtMusicRepository,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
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
                onClick = { onTrackClick(full, i) }
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
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(pl.name.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
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
private fun EmptyMessage(msg: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(msg, color = Color.Gray, modifier = Modifier.padding(24.dp))
    }
}
