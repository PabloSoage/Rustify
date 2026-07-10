package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.varuna.rustify.R
import com.varuna.rustify.bridge.SimpleAlbum
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository

/**
 * Dedicated, paginated "New Releases" list (SpotifyRepository.getNewReleases).
 * Reached from the Home top bar; Home itself only shows the browse-section preview.
 */
@Composable
fun NewReleasesScreen(
    spotifyRepo: SpotifyRepository,
    onBackClick: () -> Unit,
    onAlbumClick: (String, String, List<SpotifyImage>) -> Unit
) {
    val albums = remember { mutableStateListOf<SimpleAlbum>() }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var nextOffset by remember { mutableStateOf<Int?>(0) }
    var hasMore by remember { mutableStateOf(true) }
    val gridState = rememberLazyGridState()

    suspend fun fetch(offset: Int) {
        val page = spotifyRepo.getNewReleases(limit = 20, offset = offset)
        albums.addAll(page.items)
        nextOffset = page.nextOffset
        hasMore = page.hasMore && page.nextOffset != null
    }

    LaunchedEffect(Unit) {
        isLoading = true
        try { fetch(0) } catch (_: Exception) { hasMore = false } finally { isLoading = false }
    }

    // Load the next page when the user nears the end of the grid.
    LaunchedEffect(gridState, hasMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (last != null && hasMore && !isLoadingMore && last >= albums.size - 4) {
                    val off = nextOffset ?: return@collect
                    isLoadingMore = true
                    try { fetch(off) } catch (_: Exception) { hasMore = false } finally { isLoadingMore = false }
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
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
            Text(
                text = stringResource(R.string.new_releases_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF1DB954))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(albums) { album ->
                    NewReleaseCard(album = album, onClick = { onAlbumClick(album.id, album.name, album.images) })
                }
            }
        }
    }
}

@Composable
private fun NewReleaseCard(album: SimpleAlbum, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        val imgUrl = album.images.firstOrNull()?.url
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp)),
            color = Color.DarkGray
        ) {
            if (!imgUrl.isNullOrEmpty()) {
                AsyncImage(model = imgUrl, contentDescription = album.name, contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.name,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = album.artists.joinToString(", ") { it.name },
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
