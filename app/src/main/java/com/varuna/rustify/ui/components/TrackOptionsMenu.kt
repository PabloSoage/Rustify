@file:Suppress("SpellCheckingInspection")

package com.varuna.rustify.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SimplePlaylist
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsMenuBottomSheet(
    track: FullTrack,
    spotifyRepo: SpotifyRepository,
    onDismiss: () -> Unit,
    onAddToQueue: () -> Unit,
    onGoToQueue: () -> Unit,
    onGoToAlbum: (String, String, List<SpotifyImage>) -> Unit,
    onGoToArtist: (String) -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showPlaylistSelector by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<SimplePlaylist>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }

    val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
    val downloadUriStr = prefs.getString("download_directory", null)
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Handle if needed
    }

    if (showPlaylistSelector) {
        // Nested Playlist Selection Bottom Sheet
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
                    text = stringResource(R.string.track_menu_add_playlist),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (isLoadingPlaylists) {
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
                                    .clickable {
                                        coroutineScope.launch {
                                            track.id?.let { trackId ->
                                                val res = spotifyRepo.addTracksToPlaylist(playlist.id, listOf(trackId))
                                                if (res.success) {
                                                    Toast.makeText(context, context.getString(R.string.added_to_playlist, playlist.name), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Error: ${res.error}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            showPlaylistSelector = false
                                            onDismiss()
                                        }
                                    }
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
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop
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
        // Main Track Options Sheet
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Header: Track Artwork, Title, Artist
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val trackImageUrl = track.album?.images?.minByOrNull { it.width ?: 999 }?.url
                    Surface(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        color = Color.DarkGray
                    ) {
                        if (!trackImageUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = trackImageUrl,
                                contentDescription = track.name,
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artists.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))

                // Menu items
                MenuOptionItem(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = stringResource(R.string.track_menu_add_queue),
                    onClick = {
                        onAddToQueue()
                        Toast.makeText(context, context.getString(R.string.queue_added), Toast.LENGTH_SHORT).show()
                    }
                )

                MenuOptionItem(
                    icon = Icons.Default.Add,
                    label = stringResource(R.string.track_menu_add_playlist),
                    onClick = {
                        showPlaylistSelector = true
                        isLoadingPlaylists = true
                        coroutineScope.launch {
                            try {
                                playlists = spotifyRepo.getSavedPlaylists(limit = 50).items
                            } catch (_: Exception) {
                                Toast.makeText(context, "Error al cargar playlists", Toast.LENGTH_SHORT).show()
                            } finally {
                                isLoadingPlaylists = false
                            }
                        }
                    }
                )

                if (onRemoveFromPlaylist != null) {
                    MenuOptionItem(
                        icon = Icons.Default.RemoveCircleOutline,
                        label = stringResource(R.string.track_menu_remove_playlist),
                        iconColor = Color(0xFFCC2200),
                        labelColor = Color(0xFFCC2200),
                        onClick = onRemoveFromPlaylist
                    )
                }

                MenuOptionItem(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    label = stringResource(R.string.track_menu_go_queue),
                    onClick = onGoToQueue
                )

                if (track.album != null) {
                    val album = track.album
                    MenuOptionItem(
                        icon = Icons.Default.Album,
                        label = stringResource(R.string.track_menu_go_album),
                        onClick = {
                            onGoToAlbum(album.id, album.name, album.images)
                        }
                    )
                }

                if (track.artists.isNotEmpty()) {
                    MenuOptionItem(
                        icon = Icons.Default.Person,
                        label = stringResource(R.string.track_menu_go_artist),
                        onClick = {
                            onGoToArtist(track.artists.first().id)
                        }
                    )
                }

                val isLocalTrack = track.id?.startsWith("local:") == true
                if (!isLocalTrack && downloadUriStr != null) {
                    val downloadingStr = stringResource(R.string.track_menu_getting_url)
                    val errorUrlStr = stringResource(R.string.track_menu_url_not_found)
                    val notificationTitle = stringResource(R.string.track_menu_downloading, track.name ?: "")
                    val notificationConnecting = stringResource(R.string.track_menu_connecting)
                    val notificationComplete = stringResource(R.string.track_menu_download_complete)
                    val notificationError = stringResource(R.string.track_menu_download_error)
                    val toastComplete = stringResource(R.string.track_menu_downloaded_toast, track.name ?: "")
                    val downloadProgressFormat = stringResource(R.string.track_menu_download_progress)
                    
                    MenuOptionItem(
                        icon = Icons.Default.Download,
                        label = stringResource(R.string.track_menu_download),
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            
                            val trackId = track.id ?: return@MenuOptionItem
                            val trackName = track.name ?: "Unknown"
                            val trackArtist = track.artists.joinToString(", ") { it.name }
                            
                            com.varuna.rustify.bridge.DownloadManager.enqueueDownload(
                                context = context,
                                trackId = trackId,
                                trackName = trackName,
                                trackArtist = trackArtist,
                                spotifyRepo = spotifyRepo,
                                downloadUriStr = downloadUriStr
                            )
                            
                            Toast.makeText(context, downloadingStr, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MenuOptionItem(
    icon: ImageVector,
    label: String,
    iconColor: Color = Color.White,
    labelColor: Color = Color.White,
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
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
