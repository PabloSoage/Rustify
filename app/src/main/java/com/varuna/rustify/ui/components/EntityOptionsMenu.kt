package com.varuna.rustify.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SimplePlaylist
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.util.ShareUtils
import kotlinx.coroutines.launch

/**
 * E30-ctx: generic 3-dot context menu for albums, playlists and artists.
 *
 * Mirrors the visual style of [TrackOptionsMenuBottomSheet] (ModalBottomSheet + menu rows +
 * nested playlist selector). Items are enabled per entity type / available data:
 *  - Share (always) via [ShareUtils.shareSpotifyLink].
 *  - Go to artist (only when [primaryArtistId] != null).
 *  - Add all [tracks] to a playlist (only when tracks is not empty).
 *
 * Signature is a hard contract consumed by the detail/listing screens; do not change it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityOptionsMenuBottomSheet(
    entityType: String,        // "album" | "playlist" | "artist"
    entityId: String,
    entityName: String,
    primaryArtistId: String?,  // for "go to artist" from album/playlist; null on artist
    tracks: List<FullTrack>,   // all album/playlist tracks (or artist top tracks); may be empty
    onDismiss: () -> Unit,
    onGoToArtist: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Nested playlist selector state (mirrors TrackOptionsMenu flow).
    var showPlaylistSelector by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<SimplePlaylist>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var isAdding by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }

    // The Spotify ids to add (album/playlist tracks). Filtered downstream by the repo helper too.
    val trackIds = remember(tracks) { tracks.mapNotNull { it.id } }

    // Share label + link path derived from the entity type.
    val shareLabelRes = when (entityType) {
        "album" -> R.string.entity_menu_share_album
        "playlist" -> R.string.entity_menu_share_playlist
        else -> R.string.entity_menu_share_artist
    }

    // Adds the given ids to [playlistId], showing progress + a result/error toast.
    val addedTemplate = stringResource(R.string.entity_menu_added_n_to_playlist)
    fun addAllTo(playlistId: String, playlistName: String) {
        coroutineScope.launch {
            isAdding = true
            try {
                val n = spotifyRepoAddAll(context, playlistId, trackIds)
                Toast.makeText(context, String.format(addedTemplate, n, playlistName), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.entity_menu_add_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isAdding = false
                showPlaylistSelector = false
                onDismiss()
            }
        }
    }

    if (showNewPlaylistDialog) {
        NewPlaylistDialog(
            onDismiss = { showNewPlaylistDialog = false },
            onCreate = { name ->
                showNewPlaylistDialog = false
                coroutineScope.launch {
                    isAdding = true
                    try {
                        val me = spotifyRepoGetMe(context)
                        val created = spotifyRepoCreatePlaylist(context, me, name)
                        // Reuse addAllTo for the freshly created playlist.
                        val n = spotifyRepoAddAll(context, created.first, trackIds)
                        Toast.makeText(context, String.format(addedTemplate, n, created.second), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.entity_menu_add_error, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        isAdding = false
                        showPlaylistSelector = false
                        onDismiss()
                    }
                }
            }
        )
    }

    if (showPlaylistSelector) {
        // Nested playlist picker: "New playlist" row + existing playlists.
        ModalBottomSheet(
            onDismissRequest = { showPlaylistSelector = false },
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.entity_menu_add_all_playlist),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // "New playlist" entry always available at the top.
                EntityMenuRow(
                    icon = Icons.Default.Add,
                    label = stringResource(R.string.entity_menu_new_playlist),
                    onClick = { showNewPlaylistDialog = true }
                )
                HorizontalDivider(color = Color.DarkGray)

                if (isLoadingPlaylists || isAdding) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF1DB954))
                    }
                } else if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.playlist_not_found), color = Color.Gray)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxHeight(0.6f)) {
                        items(playlists) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { addAllTo(playlist.id, playlist.name) }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val imgUrl = playlist.images.minByOrNull { it.width ?: 999 }?.url
                                Surface(
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Color.DarkGray
                                ) {
                                    if (!imgUrl.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = imgUrl,
                                            contentDescription = null
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = playlist.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            HorizontalDivider(color = Color.DarkGray)
                        }
                    }
                }
            }
        }
    } else {
        // Main entity options sheet.
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Header: entity name.
                Text(
                    text = entityName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))

                // Share (always) — plain Spotify URL.
                EntityMenuRow(
                    icon = Icons.Default.Share,
                    label = stringResource(shareLabelRes),
                    onClick = {
                        ShareUtils.shareSpotifyLink(context, entityType, entityId)
                        onDismiss()
                    }
                )

                // F2: extra "Share as Rustify" shown only when the Settings toggle is on.
                val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
                if (prefs.getBoolean("share_as_rustify_link", false)) {
                    EntityMenuRow(
                        icon = Icons.Default.Share,
                        label = stringResource(R.string.entity_menu_share_rustify),
                        onClick = {
                            ShareUtils.shareRustifyLink(context, entityType, entityId)
                            onDismiss()
                        }
                    )
                }

                // Go to artist (only when we have a primary artist id).
                if (!primaryArtistId.isNullOrBlank()) {
                    EntityMenuRow(
                        icon = Icons.Default.Person,
                        label = stringResource(R.string.entity_menu_go_artist),
                        onClick = {
                            onGoToArtist(primaryArtistId)
                            onDismiss()
                        }
                    )
                }

                // Add all tracks to a playlist (only when there are tracks).
                if (trackIds.isNotEmpty()) {
                    EntityMenuRow(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        label = stringResource(R.string.entity_menu_add_all_playlist),
                        onClick = {
                            showPlaylistSelector = true
                            isLoadingPlaylists = true
                            coroutineScope.launch {
                                try {
                                    playlists = spotifyRepoGetPlaylists(context)
                                } catch (_: Exception) {
                                    Toast.makeText(
                                        context,
                                        R.string.entity_menu_load_playlists_error,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } finally {
                                    isLoadingPlaylists = false
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** Simple name-only "New playlist" dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text(stringResource(R.string.entity_menu_new_playlist)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.entity_menu_new_playlist_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.entity_menu_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.entity_menu_cancel)) }
        }
    )
}

/** Menu row matching TrackOptionsMenu's MenuOptionItem style (that one is private to its file). */
@Composable
private fun EntityMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// --- Thin suspend adapters over SpotifyRepository (keep the composable self-contained) ---

private suspend fun spotifyRepoGetPlaylists(context: android.content.Context): List<SimplePlaylist> {
    val repo = SpotifyRepository(context)
    return repo.getSavedPlaylists(limit = 50).items
}

private suspend fun spotifyRepoAddAll(
    context: android.content.Context,
    playlistId: String,
    trackIds: List<String>
): Int {
    val repo = SpotifyRepository(context)
    return repo.addAllTracksToPlaylist(context, playlistId, trackIds)
}

/** @return the current user's id. */
private suspend fun spotifyRepoGetMe(context: android.content.Context): String {
    val repo = SpotifyRepository(context)
    return repo.getMe().id
}

/** @return (playlistId, playlistName) of the created playlist. */
private suspend fun spotifyRepoCreatePlaylist(
    context: android.content.Context,
    userId: String,
    name: String
): Pair<String, String> {
    val repo = SpotifyRepository(context)
    val pl = repo.createPlaylist(userId, name)
    return pl.id to pl.name
}
