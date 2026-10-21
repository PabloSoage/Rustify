package com.varuna.rustify.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.rustify.R

/**
 * A category on the empty Search screen: the label is translated, the query stays in English
 * because that is what Spotify's editorial playlists are named after.
 */
data class SearchCategory(@param:StringRes val label: Int, val query: String, val color: Color)

val searchCategories = listOf(
    SearchCategory(R.string.search_cat_pop, "pop", Color(0xFFE8115B)),
    SearchCategory(R.string.search_cat_rock, "rock", Color(0xFFBA5D07)),
    SearchCategory(R.string.search_cat_hiphop, "hip hop", Color(0xFF8D67AB)),
    SearchCategory(R.string.search_cat_latin, "latin", Color(0xFFE1118C)),
    SearchCategory(R.string.search_cat_electronic, "electronic", Color(0xFF0D73EC)),
    SearchCategory(R.string.search_cat_indie, "indie", Color(0xFF608108)),
    SearchCategory(R.string.search_cat_rnb, "r&b", Color(0xFFDC148C)),
    SearchCategory(R.string.search_cat_jazz, "jazz", Color(0xFF477D95)),
    SearchCategory(R.string.search_cat_classical, "classical", Color(0xFF7D4B32)),
    SearchCategory(R.string.search_cat_metal, "metal", Color(0xFF503750)),
    SearchCategory(R.string.search_cat_chill, "chill", Color(0xFF1E3264)),
    SearchCategory(R.string.search_cat_focus, "focus", Color(0xFF503750)),
    SearchCategory(R.string.search_cat_workout, "workout", Color(0xFF777777)),
    SearchCategory(R.string.search_cat_party, "party", Color(0xFFAF2896)),
    SearchCategory(R.string.search_cat_sleep, "sleep", Color(0xFF1E3264)),
    SearchCategory(R.string.search_cat_decades, "80s", Color(0xFFBA5D07)),
)

/**
 * What Search shows before anything is typed (4.7): the recent searches, then categories to
 * browse. It used to be one line of grey text in the middle of an empty screen.
 */
@Composable
fun SearchEmptyState(
    recent: List<String>,
    onRecentClick: (String) -> Unit,
    onRecentRemove: (String) -> Unit,
    onClearRecent: () -> Unit,
    onCategoryClick: (SearchCategory) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val columns = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / 180).coerceIn(2, 6)
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (recent.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.search_recent),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearRecent) {
                        Text(stringResource(R.string.search_recent_clear), color = Color.LightGray)
                    }
                }
            }
            items(recent, key = { "recent:$it" }) { q ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRecentClick(q) }
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        q,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onRecentRemove(q) }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.search_recent_remove, q),
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        item {
            Text(
                stringResource(R.string.search_browse),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        // Rows of tiles, as many as fit at ~180 dp (two on a phone, five or six on a tablet in
        // landscape, where two stretched into bars). A nested grid cannot live inside a LazyColumn.
        items(searchCategories.chunked(columns)) { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { cat -> CategoryTile(cat, onCategoryClick, Modifier.weight(1f)) }
                repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryTile(cat: SearchCategory, onClick: (SearchCategory) -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(cat.color, cat.color.copy(alpha = 0.55f))))
            .clickable { onClick(cat) }
            .padding(12.dp)
    ) {
        Text(
            stringResource(cat.label),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}
