// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/ui/App.kt
package com.varuna.rustify.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SimplePlaylist
import com.varuna.rustify.bridge.SpotifyUser
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.decodeWire
import com.varuna.rustify.engine.PlayerUi
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.jam.JamSession
import com.varuna.rustify.spotify.SpotifyLibrary
import kotlinx.coroutines.launch

/** The same green the Android app's dark theme is built on, so the two read as one product. */
private val Accent = Color(0xFF1DB954)

@Composable
fun RustifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, secondary = Accent),
        content = { Surface(modifier = Modifier.fillMaxSize()) { content() } },
    )
}

private enum class Section(val label: String) {
    HOME("Home"), SEARCH("Search"), LIKED("Liked songs"), PLAYLISTS("Playlists"), METRICS("Metrics"), JAM("Jam"),
    ACCOUNT("Account"),
}

/** Something on Home that opens into a list of songs. */
private data class Opened(val key: String, val title: String, val load: suspend () -> List<FullTrack>)

/**
 * The desktop app: search (YouTube Music without an account, Spotify with one), the Spotify library
 * — liked songs and playlists, each played as a whole queue — and the account. Home, artists and
 * albums come as the Android screens' logic moves to the shared module (docs 25 §7).
 */
@Composable
fun App(player: QueuePlayer, spotify: SpotifyLibrary, jam: JamSession) {
    val ui by player.ui.collectAsState()
    var section by remember { mutableStateOf(Section.HOME) }
    var user by remember { mutableStateOf<SpotifyUser?>(null) }

    LaunchedEffect(Unit) { user = runCatching { spotify.restore() }.getOrNull() }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = section.ordinal) {
            Section.entries.forEach { s ->
                Tab(selected = section == s, onClick = { section = s }, text = { Text(s.label) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                Section.HOME -> HomeSection(player, ui, spotify)
                Section.SEARCH -> SearchSection(player, ui, spotify, signedIn = user != null)
                Section.LIKED -> SignedIn(user) { LikedSection(player, ui, spotify) }
                Section.PLAYLISTS -> SignedIn(user) { PlaylistsSection(player, ui, spotify) }
                Section.METRICS -> MetricsSection()
                Section.JAM -> JamSection(jam)
                Section.ACCOUNT -> AccountSection(spotify, user) { user = it }
            }
        }
        HorizontalDivider()
        NowPlayingBar(ui, player)
    }
}

@Composable
private fun SignedIn(user: SpotifyUser?, content: @Composable () -> Unit) {
    if (user != null) {
        content()
    } else {
        Centered("Sign in to Spotify in Account to see your library.")
    }
}

@Composable
internal fun Centered(text: String) {
    Box(Modifier.fillMaxSize()) {
        Text(text, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A list that loads once; [load] is the whole list (every page), not a screen's worth. */
@Composable
internal fun <T> Loaded(key: Any, load: suspend () -> List<T>, content: @Composable (List<T>) -> Unit) {
    var items by remember(key) { mutableStateOf<List<T>?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        runCatching { load() }.onSuccess { items = it }.onFailure { error = it.message ?: "Could not load" }
    }
    when {
        error != null -> Centered(error.orEmpty())
        items == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        else -> content(items.orEmpty())
    }
}

@Composable
private fun SearchSection(player: QueuePlayer, ui: PlayerUi, spotify: SpotifyLibrary, signedIn: Boolean) {
    var query by remember { mutableStateOf("") }
    var fromSpotify by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FullTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        error = null
        scope.launch {
            val found = runCatching {
                if (fromSpotify && signedIn) {
                    spotify.searchTracks(q)
                } else {
                    decodeWire<YtmSearchResults>(NativeEngine.searchYtMusic(q)).tracks.map { it.toFullTrack() }
                }
            }
            searching = false
            found.onSuccess { results = it }.onFailure { error = it.message ?: "Search failed" }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(if (fromSpotify) "Search songs on Spotify" else "Search songs on YouTube Music") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            Spacer(Modifier.width(12.dp))
            FilterChip(selected = !fromSpotify, onClick = { fromSpotify = false }, label = { Text("YouTube Music") })
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = fromSpotify,
                onClick = { fromSpotify = true },
                enabled = signedIn,
                label = { Text("Spotify") },
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = { search() }, enabled = !searching) { Text("Search") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Centered(error.orEmpty())
                results.isEmpty() -> Centered("Search for a song, an artist or an album.")
                else -> TrackList(results, ui, player)
            }
        }
    }
}

/** Spotify's Home sections, without an account as on the phone; a playlist or album opens its songs. */
@Composable
private fun HomeSection(player: QueuePlayer, ui: PlayerUi, spotify: SpotifyLibrary) {
    var open by remember { mutableStateOf<Opened?>(null) }
    val opened = open
    if (opened != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open = null }) { Text("‹ Home") }
                Text(opened.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Loaded(opened.key, opened.load) { tracks -> TrackList(tracks, ui, player) }
        }
        return
    }
    Loaded("home", { spotify.home() }) { sections ->
        LazyColumn(Modifier.fillMaxSize()) {
            sections.forEach { section ->
                item(key = "h-${section.id}") {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                // Indexed: the same playlist can appear twice in a section, and a key must not.
                itemsIndexed(section.items, key = { i, it -> "${section.id}-$i-${keyOf(it)}" }) { _, item ->
                    val (title, subtitle, opener) = when (item) {
                        is BrowseSectionItem.PlaylistItem -> Triple(
                            item.playlist.name,
                            item.playlist.owner?.name.orEmpty(),
                            Opened("pl-${item.playlist.id}", item.playlist.name) { spotify.playlistTracks(item.playlist.id) },
                        )
                        is BrowseSectionItem.AlbumItem -> Triple(
                            item.album.name,
                            item.album.artists.joinToString(", ") { it.name },
                            Opened("al-${item.album.id}", item.album.name) { spotify.albumTracks(item.album.id) },
                        )
                    }
                    Column(Modifier.fillMaxWidth().clickable { open = opener }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun keyOf(item: BrowseSectionItem): String = when (item) {
    is BrowseSectionItem.PlaylistItem -> "pl-${item.playlist.id}"
    is BrowseSectionItem.AlbumItem -> "al-${item.album.id}"
}

@Composable
private fun LikedSection(player: QueuePlayer, ui: PlayerUi, spotify: SpotifyLibrary) {
    Loaded("liked", { spotify.likedSongs() }) { tracks ->
        if (tracks.isEmpty()) Centered("No liked songs yet.") else TrackList(tracks, ui, player)
    }
}

@Composable
private fun PlaylistsSection(player: QueuePlayer, ui: PlayerUi, spotify: SpotifyLibrary) {
    var open by remember { mutableStateOf<SimplePlaylist?>(null) }
    val playlist = open
    if (playlist != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open = null }) { Text("‹ Playlists") }
                Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Loaded(playlist.id, { spotify.playlistTracks(playlist.id) }) { tracks -> TrackList(tracks, ui, player) }
        }
        return
    }
    Loaded("playlists", { spotify.playlists() }) { playlists ->
        LazyColumn(Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { p ->
                Column(Modifier.fillMaxWidth().clickable { open = p }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(p.owner?.name, p.totalTracks?.let { "$it songs" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountSection(spotify: SpotifyLibrary, user: SpotifyUser?, onUser: (SpotifyUser?) -> Unit) {
    var cookie by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (user != null) {
            Text("Signed in as ${user.name ?: user.id}", fontWeight = FontWeight.SemiBold)
            Button(onClick = {
                spotify.logout()
                onUser(null)
            }) { Text("Sign out") }
            return@Column
        }
        Text("Spotify", style = MaterialTheme.typography.titleLarge)
        Text(
            "Rustify signs in with your browser session, as on the phone: open open.spotify.com signed in, " +
                "copy the value of the sp_dc cookie and paste it here. It stays on this computer.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = cookie,
            onValueChange = { cookie = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("sp_dc") },
            visualTransformation = PasswordVisualTransformation(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = cookie.isNotBlank() && !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { spotify.login(cookie) }
                        .onSuccess {
                            cookie = ""
                            onUser(it)
                        }
                        .onFailure { error = it.message ?: "Sign-in failed" }
                    busy = false
                }
            },
        ) { Text(if (busy) "Signing in…" else "Sign in") }
    }
}

@Composable
private fun TrackList(tracks: List<FullTrack>, ui: PlayerUi, player: QueuePlayer) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { i, t -> "$i-${t.id}" }) { index, track ->
            TrackRow(
                track = track,
                current = ui.current?.id != null && ui.current?.id == track.id,
                onClick = { player.playAll(tracks, index) },
            )
        }
    }
}

@Composable
private fun TrackRow(track: FullTrack, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) Accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatMs(track.durationMs.toLong()), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NowPlayingBar(ui: PlayerUi, player: QueuePlayer) {
    // While dragging, the slider shows the finger, not the engine; the seek goes on release.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = ui.durationMs ?: (ui.current?.durationMs?.toLong() ?: 0L)
    var repeat by remember { mutableIntStateOf(0) }
    val repeatModes = listOf("off", "all", "one")

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    ui.current?.name ?: "Not playing",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ui.error ?: ui.current?.artists?.joinToString(", ") { it.name }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = player::previous, enabled = ui.current != null) { Text("⏮") }
                Button(onClick = player::togglePause, enabled = ui.current != null && !ui.loading) {
                    Text(if (ui.playing) "⏸" else "▶")
                }
                TextButton(onClick = player::next, enabled = ui.current != null) { Text("⏭") }
                TextButton(onClick = {
                    repeat = (repeat + 1) % repeatModes.size
                    player.setRepeat(repeatModes[repeat])
                }) { Text("Repeat: ${repeatModes[repeat]}") }
            }
            Spacer(Modifier.width(24.dp))
            Text("Vol", style = MaterialTheme.typography.bodySmall)
            Slider(value = ui.volume, onValueChange = player::setVolume, modifier = Modifier.width(140.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatMs(if (dragging) (dragValue * duration).toLong() else ui.positionMs),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = when {
                    dragging -> dragValue
                    duration > 0 -> (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                    else -> 0f
                },
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    if (duration > 0) player.seek((dragValue * duration).toLong())
                    dragging = false
                },
                enabled = duration > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatMs(duration), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
