package com.varuna.rustify.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.R
import com.varuna.rustify.bridge.ArtistAbout
import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * An artist page's "About" (4.7): monthly listeners and world rank, the biography (four lines,
 * tap for the rest), the cities that listen most, and upcoming concerts.
 */
@Composable
fun ArtistAboutSection(about: ArtistAbout) {
    val numbers = NumberFormat.getIntegerInstance()
    val green = Color(0xFF1DB954)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.artist_about),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1E1E)).padding(16.dp)
        ) {
            about.monthlyListeners?.let {
                Text(
                    stringResource(R.string.artist_monthly_listeners, numbers.format(it)),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            }
            about.worldRank?.let {
                Text(stringResource(R.string.artist_world_rank, it), color = green, fontSize = 13.sp)
            }
            if (about.biography.isNotBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(10.dp))
                Text(
                    about.biography,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.animateContentSize().clickable { expanded = !expanded }
                )
            }
            if (about.topCities.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                about.topCities.take(5).forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.city}, ${c.country}", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.artist_city_listeners, numbers.format(c.listeners)),
                            color = Color.Gray, fontSize = 12.sp
                        )
                    }
                }
            }
        }
        if (about.concerts.isNotEmpty()) {
            Text(
                stringResource(R.string.artist_on_tour),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )
            val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
            about.concerts.take(10).forEach { c ->
                val date = runCatching { OffsetDateTime.parse(c.startsAt).format(dateFormat) }.getOrDefault(c.startsAt)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Start) {
                    Text(date, color = green, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(c.city, c.venue).filter { it.isNotBlank() }.joinToString(" · "),
                            color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
