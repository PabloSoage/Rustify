package com.varuna.rustify.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.TrackCredits

/**
 * Who wrote, played and produced a track (4.6) — the official clients' "Show credits". The groups
 * and their order are Spotify's; a contributor with an artist page opens it.
 */
@Composable
fun TrackCreditsDialog(
    trackId: String,
    spotifyRepo: SpotifyRepository,
    onArtistClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var credits by remember(trackId) { mutableStateOf<TrackCredits?>(null) }
    var failed by remember(trackId) { mutableStateOf(false) }
    LaunchedEffect(trackId) {
        credits = runCatching { spotifyRepo.getTrackCredits(trackId) }.getOrNull()
        failed = credits == null
    }
    val green = Color(0xFF1DB954)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282828),
        title = { Text(stringResource(R.string.track_menu_credits), color = Color.White) },
        text = {
            val c = credits
            when {
                c != null && c.groups.isNotEmpty() -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    c.groups.forEach { group ->
                        item {
                            Text(
                                group.name, color = green, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(group.people) { p ->
                            val id = p.artistId
                            Box(
                                Modifier.fillMaxWidth()
                                    .then(if (id != null) Modifier.clickable { onArtistClick(id) } else Modifier)
                                    .padding(vertical = 6.dp)
                            ) {
                                androidx.compose.foundation.layout.Column {
                                    Text(p.name, color = if (id != null) Color.White else Color.LightGray, fontSize = 15.sp)
                                    if (p.role.isNotEmpty()) Text(p.role, color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (c.sources.isNotEmpty()) item {
                        Text(
                            stringResource(R.string.track_credits_source, c.sources.joinToString(", ")),
                            color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
                failed || c != null -> Text(stringResource(R.string.track_credits_none), color = Color.Gray)
                else -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = green)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close), color = green) } }
    )
}
