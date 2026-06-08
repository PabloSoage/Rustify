package com.varuna.rustify.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SimplePlaylist
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import kotlinx.coroutines.launch

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
                    text = "Añadir a Playlist",
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
                        Text("No se encontraron playlists", color = Color.Gray)
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
                                                    Toast.makeText(context, "Añadido a ${playlist.name}", Toast.LENGTH_SHORT).show()
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
                    label = "Añadir a la cola",
                    onClick = {
                        onAddToQueue()
                        Toast.makeText(context, "Añadido a la cola", Toast.LENGTH_SHORT).show()
                    }
                )

                MenuOptionItem(
                    icon = Icons.Default.Add,
                    label = "Añadir a Playlist",
                    onClick = {
                        showPlaylistSelector = true
                        isLoadingPlaylists = true
                        coroutineScope.launch {
                            try {
                                playlists = spotifyRepo.getSavedPlaylists(limit = 50).items
                            } catch (e: Exception) {
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
                        label = "Quitar de esta Playlist",
                        iconColor = Color(0xFFCC2200),
                        labelColor = Color(0xFFCC2200),
                        onClick = onRemoveFromPlaylist
                    )
                }

                MenuOptionItem(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    label = "Ir a la cola",
                    onClick = onGoToQueue
                )

                track.album?.let { album ->
                    MenuOptionItem(
                        icon = Icons.Default.Album,
                        label = "Ir al Álbum",
                        onClick = {
                            onGoToAlbum(album.id, album.name, album.images)
                        }
                    )
                }

                if (track.artists.isNotEmpty()) {
                    MenuOptionItem(
                        icon = Icons.Default.Person,
                        label = "Ir al Artista",
                        onClick = {
                            onGoToArtist(track.artists.first().id)
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
