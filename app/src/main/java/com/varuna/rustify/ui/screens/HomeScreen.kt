package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.varuna.rustify.R
import com.varuna.rustify.bridge.BrowseSection
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.ui.components.PlaylistItemCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    browseSections: List<BrowseSection>?,
    isRunning: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onItemClick: (BrowseSectionItem) -> Unit,
    onSettingsClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onNewReleasesClick: () -> Unit,
    onMetricsClick: () -> Unit,
    onDjClick: () -> Unit = {},
    onTravelClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val darkBackground = Color(0xFF121212)
    val gradientColor = Color(0xFF2E2E2E)

    Box(modifier = modifier.fillMaxSize().background(darkBackground)) {
        // Aesthetic Top Gradient Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(gradientColor, Color.Transparent)
                    )
                )
        )

        if (isRunning && browseSections == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF1DB954)
            )
        } else if (errorMessage != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.home_error_loading), color = MaterialTheme.colorScheme.error)
                Text(errorMessage, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                ) {
                    Text(stringResource(R.string.general_retry), color = Color.White)
                }
            }
        } else {
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val bottomPadding = if (isLandscape) 16.dp else 100.dp

            var menuOpen by remember { mutableStateOf(false) }
            val activeDownloads by com.varuna.rustify.bridge.DownloadManager.activeDownloadCount.collectAsState()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = bottomPadding)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        val greeting = when (currentHour) {
                            in 5..11 -> androidx.compose.ui.res.stringResource(R.string.home_greeting_morning)
                            in 12..17 -> androidx.compose.ui.res.stringResource(R.string.home_greeting_afternoon)
                            else -> androidx.compose.ui.res.stringResource(R.string.home_greeting_evening)
                        }
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )

                        // Pre-compute menu labels outside the DropdownMenu (which uses a Popup
                        // that may not inherit the overridden LocalContext for i18n).
                        val djLabel = stringResource(R.string.home_dj)
                        val travelLabel = stringResource(R.string.home_travel)
                        val newReleasesLabel = stringResource(R.string.home_new_releases)
                        val metricsLabel = stringResource(R.string.home_metrics)
                        val downloadsLabel = stringResource(R.string.home_downloads)
                        val settingsLabel = stringResource(R.string.home_settings)

                        // Hamburger → a small context menu hangs from the icon (icons + labels
                        // aligned natively by DropdownMenuItem's leadingIcon slot).
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                if (activeDownloads > 0) {
                                    androidx.compose.material3.BadgedBox(
                                        badge = {
                                            androidx.compose.material3.Badge(containerColor = Color.Red) {
                                                Text(activeDownloads.toString(), color = Color.White)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.a11y_open_menu), tint = Color.White)
                                    }
                                } else {
                                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.a11y_open_menu), tint = Color.White)
                                }
                            }

                            androidx.compose.material3.DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                containerColor = Color(0xFF1E1E1E)
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(djLabel, color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.Radio, contentDescription = null, tint = Color(0xFF1DB954)) },
                                    onClick = { menuOpen = false; onDjClick() }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(travelLabel, color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.Map, contentDescription = null, tint = Color.White) },
                                    onClick = { menuOpen = false; onTravelClick() }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(newReleasesLabel, color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.NewReleases, contentDescription = null, tint = Color.White) },
                                    onClick = { menuOpen = false; onNewReleasesClick() }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(metricsLabel, color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null, tint = Color.White) },
                                    onClick = { menuOpen = false; onMetricsClick() }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(downloadsLabel, color = Color.White) },
                                    leadingIcon = {
                                        if (activeDownloads > 0) {
                                            androidx.compose.material3.BadgedBox(
                                                badge = {
                                                    androidx.compose.material3.Badge(containerColor = Color.Red) {
                                                        Text(activeDownloads.toString(), color = Color.White)
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.FileDownload, contentDescription = null, tint = Color.White)
                                            }
                                        } else {
                                            Icon(Icons.Default.FileDownload, contentDescription = null, tint = Color.White)
                                        }
                                    },
                                    onClick = { menuOpen = false; onDownloadsClick() }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(settingsLabel, color = Color.White) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White) },
                                    onClick = { menuOpen = false; onSettingsClick() }
                                )
                            }
                        }
                    }
                }

                browseSections?.let { sections ->
                    items(sections) { section ->
                        BrowseSectionView(
                            section = section,
                            onItemClick = onItemClick
                        )
                    }
                }
            }

        }
    }
}

@Composable
fun BrowseSectionView(
    section: BrowseSection,
    onItemClick: (BrowseSectionItem) -> Unit
) {
    if (section.items.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(section.items) { item ->
                when (item) {
                    is BrowseSectionItem.PlaylistItem -> PlaylistItemCard(
                        title = item.playlist.name,
                        subtitle = item.playlist.description,
                        images = item.playlist.images,
                        onClick = { onItemClick(item) }
                    )
                    is BrowseSectionItem.AlbumItem -> PlaylistItemCard(
                        title = item.album.name,
                        subtitle = item.album.artists.joinToString(", ") { it.name },
                        images = item.album.images,
                        onClick = { onItemClick(item) }
                    )
                    is BrowseSectionItem.ArtistItem -> PlaylistItemCard(
                        title = item.artist.name,
                        subtitle = stringResource(R.string.search_subtitle_artist),
                        images = item.artist.images,
                        isCircle = true,
                        onClick = { onItemClick(item) }
                    )
                }
            }
        }
    }
}
