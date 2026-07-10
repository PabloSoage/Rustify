package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.ui.components.TrackRowItem

/**
 * E95: "Song radio" — related tracks for a seed track, via SpotifyRepository.getTrackRadio.
 * Reached from the track 3-dot menu ("Go to radio"). Play all / add all to queue / open a track.
 */
@Composable
fun RadioScreen(
    trackId: String,
    trackName: String,
    spotifyRepo: SpotifyRepository,
    audioPlayerService: AudioPlayerService,
    onBackClick: () -> Unit,
    onOpenTrack: (String) -> Unit
) {
    var tracks by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(trackId) {
        isLoading = true
        try {
            tracks = spotifyRepo.getTrackRadio(trackId)
        } catch (_: Exception) {
            tracks = emptyList()
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // Top bar: back + "Radio" / seed track name.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Radio",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF1DB954),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = trackName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF1DB954))
            }
            tracks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.radio_empty),
                    color = Color.Gray,
                    modifier = Modifier.padding(24.dp)
                )
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { audioPlayerService.loadPlaylist(tracks, 0) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.radio_play_all), color = Color.Black)
                    }
                    OutlinedButton(
                        onClick = { audioPlayerService.enqueueAll(tracks) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.track_menu_add_queue), color = Color.White)
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(tracks) { index, track ->
                        TrackRowItem(
                            index = index + 1,
                            track = track,
                            fallbackCoverUrl = null,
                            onClick = { audioPlayerService.loadPlaylist(tracks, index) },
                            onMoreClick = { track.id?.let(onOpenTrack) }
                        )
                    }
                }
            }
        }
    }
}
