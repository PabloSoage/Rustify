package com.varuna.rustify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.varuna.rustify.bridge.FullTrack

@Composable
fun TrackRowItem(
    index: Int,
    track: FullTrack,
    fallbackCoverUrl: String?,
    onClick: () -> Unit,
    isLiked: Boolean = false,
    onLikeToggle: (() -> Unit)? = null,
    isScrollbarDragging: Boolean = false,
    onMoreClick: (() -> Unit)? = null
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
            modifier = Modifier.widthIn(min = 32.dp, max = 48.dp).padding(end = 8.dp),
            maxLines = 1,
            softWrap = false
        )

        // Track Cover art thumbnail
        val trackImageUrl = track.album?.images?.minByOrNull { it.width ?: 999 }?.url ?: fallbackCoverUrl
        val context = LocalContext.current
        val imageRequest = ImageRequest.Builder(context)
            .data(trackImageUrl)
            .apply {
                if (isScrollbarDragging) {
                    networkCachePolicy(CachePolicy.DISABLED)
                }
            }
            .build()

        Surface(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Color.DarkGray
        ) {
            if (!trackImageUrl.isNullOrEmpty()) {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    contentDescription = "Track Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loading = {
                        TrackPlaceholder(track = track)
                    },
                    error = {
                        TrackPlaceholder(track = track)
                    }
                )
            } else {
                TrackPlaceholder(track = track)
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

        // Like Button
        if (onLikeToggle != null) {
            SpotifyLikeButton(
                isLiked = isLiked,
                onClick = onLikeToggle
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        if (onMoreClick != null) {
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = Color.LightGray
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
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

@Composable
fun SpotifyLikeButton(
    isLiked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        if (isLiked) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(Color(0xFF1DB954), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Added to Liked",
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add to Liked",
                tint = Color.Gray,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun TrackPlaceholder(track: FullTrack) {
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
