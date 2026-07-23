package com.varuna.rustify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.components.TrackRowItem

/**
 * E108 — Todas las canciones de un artista (toda su discografía) en orden de lanzamiento
 * (más reciente primero). La lista vive en el repo ([SpotifyRepository.artistAllTracksLive]) y se carga
 * en segundo plano, así que sobrevive a abrir/cerrar el miniplayer y va apareciendo progresivamente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistAllSongsScreen(
    artistId: String,
    artistName: String,
    spotifyRepo: SpotifyRepository,
    onBack: () -> Unit,
    onTrackClick: (List<FullTrack>, Int) -> Unit,
    onShufflePlay: (List<FullTrack>) -> Unit = {},
    currentTrackId: String? = null,
) {
    val green = Color(0xFF1DB954)
    val tracks = spotifyRepo.artistAllTracksLive(artistId)
    val loading = spotifyRepo.isArtistAllLoading(artistId)
    val listState = rememberLazyListState()

    LaunchedEffect(artistId) { spotifyRepo.loadArtistAllTracks(artistId) }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = { Text(artistName, color = Color.White, maxLines = 1, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { if (tracks.isNotEmpty()) onShufflePlay(tracks.toList()) }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = green)
                    }
                    IconButton(onClick = { if (tracks.isNotEmpty()) onTrackClick(tracks.toList(), 0) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = green)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tracks.isEmpty() && loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = green) }
            } else if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tracks found", color = Color.Gray) }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "${tracks.size} ${if (tracks.size == 1) "canción" else "canciones"}",
                            color = Color.Gray, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    itemsIndexed(tracks, key = { index, t -> t.id?.takeIf { it.isNotBlank() } ?: "all_${index}_${t.name.hashCode()}" }) { index, track ->
                        TrackRowItem(
                            index = index + 1,
                            track = track,
                            fallbackCoverUrl = track.album?.images?.firstOrNull()?.url,
                            onClick = { onTrackClick(tracks.toList(), index) },
                            isCurrentTrack = track.id != null && track.id == currentTrackId
                        )
                    }
                    if (loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = green, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                if (tracks.size > 25) {
                    VerticalScrollbarWithTooltip(
                        lazyListState = listState,
                        itemsCount = tracks.size + 1, // +1 por la cabecera del contador
                        getDateForItem = { idx -> tracks.getOrNull(idx - 1)?.name?.take(14) ?: "" },
                        onDragStateChanged = {},
                        modifier = Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp, horizontal = 2.dp)
                    )
                }
            }
        }
    }
}
