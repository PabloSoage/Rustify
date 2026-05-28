package com.varuna.rustify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun TrackRowItem(
    index: Int,
    track: FullTrack,
    fallbackCoverUrl: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track Index Number
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.Gray,
            modifier = Modifier.width(32.dp)
        )

        // Track Cover art thumbnail
        val trackImageUrl = track.album?.images?.minByOrNull { it.width ?: 999 }?.url ?: fallbackCoverUrl
        Surface(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Color.DarkGray
        ) {
            if (!trackImageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = trackImageUrl,
                    contentDescription = "Track Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = track.name.take(1).uppercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artists
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Explicit Badge
                if (track.explicit) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .background(Color.Gray.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "E",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.Black,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.8f
                        )
                    }
                }
                Text(
                    text = track.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Track Duration
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
    }
}

fun formatDuration(ms: Int): String {
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format(java.util.Locale.getDefault(), "%d:%02d", mins, secs)
}
