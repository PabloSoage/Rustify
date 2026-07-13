package com.varuna.rustify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullTrack

/**
 * Mosaico 2x2 (hasta 4 carátulas) para playlists locales, deduplicado por álbum
 * para evitar tiles idénticos. Extraído para poder reutilizarse fuera de LibraryScreen
 * (E30: pantalla de detalle de playlist local).
 *
 * Reutilizable/`internal` a propósito para que LibraryScreen pueda migrar aquí luego.
 */
@Composable
internal fun LocalPlaylistCover(
    tracks: List<FullTrack>,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(4.dp),
    placeholderFontSize: TextUnit = 22.sp
) {
    val covers = remember(tracks) {
        tracks.asSequence()
            .mapNotNull { t ->
                val imgUrl = t.album?.images?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                    ?: t.externalUri.takeIf { it.isNotBlank() }
                val key = t.album?.name?.takeIf { it.isNotBlank() } ?: imgUrl ?: t.id
                if (imgUrl != null) key to imgUrl else null
            }
            .distinctBy { it.first }
            .take(4)
            .map { it.second }
            .toList()
    }
    Surface(
        modifier = modifier.clip(shape),
        color = Color.DarkGray
    ) {
        when (covers.size) {
            0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("♪", color = Color.White, fontSize = placeholderFontSize)
            }
            1 -> AsyncImage(
                model = covers[0],
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            else -> Column(Modifier.fillMaxSize()) {
                val rows = covers.chunked(2)
                rows.forEach { rowItems ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        rowItems.forEach { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
