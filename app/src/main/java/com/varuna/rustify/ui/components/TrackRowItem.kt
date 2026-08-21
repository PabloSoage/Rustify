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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack

@Composable
fun TrackRowItem(
    index: Int,
    track: FullTrack,
    fallbackCoverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLiked: Boolean = false,
    isCurrentTrack: Boolean = false,
    /**
     * Heard all the way through, within this context — point I.
     *
     * Shown in place of the track number, which is the one slot on the row that is already a fixed
     * width and already about position. The number goes back the moment the track is the one
     * playing: "where am I" beats "have I heard this" when both want to say something.
     */
    isListened: Boolean = false,
    onLikeToggle: (() -> Unit)? = null,
    isScrollbarDragging: Boolean = false,
    onMoreClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track index, plus a tick once it has been heard through.
        //
        // The number stays. An earlier version replaced it with the tick, which read cleanly and
        // threw away the one thing the column is for — you could no longer tell track 3 from track
        // 11 on the rows you had listened to, which is exactly where you are looking when you come
        // back to an album. So the tick is added rather than substituted, and the number goes green
        // with it so the row still reads at a glance.
        Row(
            modifier = Modifier.widthIn(min = 32.dp, max = 56.dp).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val heard = isListened && !isCurrentTrack
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (heard) Color(0xFF1DB954) else Color.Gray,
                maxLines = 1,
                softWrap = false
            )
            if (heard) {
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.track_already_listened),
                    tint = Color(0xFF1DB954),
                    modifier = Modifier.size(12.dp)
                )
            }
        }

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

        // Title, and below: Artist + Duration
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
        ) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isCurrentTrack) Color(0xFF1DB954) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Duration — pushed to the right, next to the like button
                Text(
                    text = formatDuration(track.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onMoreClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = Color.LightGray,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
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
