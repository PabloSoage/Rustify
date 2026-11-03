// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/ui/App.kt
package com.varuna.rustify.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyUser
import com.varuna.rustify.bridge.YtmSearchResults
import com.varuna.rustify.bridge.decodeWire
import com.varuna.rustify.engine.PlayerUi
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.jam.JamSession
import com.varuna.rustify.spotify.SpotifyLibrary
import kotlinx.coroutines.launch

/**
 * The phone's colours (the app's dark theme: `#121212` behind everything, `#1DB954` for what is
 * active), so the desktop reads as the same product as the Android tablet layout it follows.
 */
internal object Palette {
    val Background = Color(0xFF121212)
    val Rail = Color(0xFF0E0E0E)
    val Card = Color(0xFF1E1E1E)
    val Bar = Color(0xFF181818)
    val Accent = Color(0xFF1DB954)
    val Muted = Color(0xFFB3B3B3)
}

@Composable
fun RustifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            secondary = Palette.Accent,
            background = Palette.Background,
            surface = Palette.Background,
            surfaceVariant = Palette.Card,
            onSurfaceVariant = Palette.Muted,
        ),
        content = { Surface(modifier = Modifier.fillMaxSize(), color = Palette.Background) { content() } },
    )
}

private enum class Section(val label: () -> String, val icon: ImageVector) {
    HOME({ S.home }, Icons.Default.Home),
    SEARCH({ S.search }, Icons.Default.Search),
    LIBRARY({ S.library }, Icons.Default.LibraryMusic),
    METRICS({ S.metrics }, Icons.Default.BarChart),
    JAM({ S.jam }, Icons.Default.Groups),
    ACCOUNT({ S.account }, Icons.Default.Person),
}

/** A playlist or album opened from anywhere: what the header shows and how to get every song. */
private data class Collection(
    val key: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val images: List<SpotifyImage>,
    val load: suspend () -> List<FullTrack>,
)

/**
 * The desktop app, laid out as the Android tablet is: a rail on the left (Home, Search, Library and the
 * rest), the screen beside it, the mini player along the bottom, and the full player over everything.
 */
@Composable
fun App(player: QueuePlayer, spotify: SpotifyLibrary, jam: JamSession) {
    val ui by player.ui.collectAsState()
    var section by remember { mutableStateOf(Section.HOME) }
    var user by remember { mutableStateOf<SpotifyUser?>(null) }
    // What was opened on top of the section, newest last; Back pops it.
    val opened = remember { mutableStateListOf<Collection>() }
    var fullPlayer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { user = runCatching { spotify.restore() }.getOrNull() }
    val open: (Collection) -> Unit = { opened.add(it) }

    if (fullPlayer) {
        NowPlaying(ui, player, onClose = { fullPlayer = false })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            NavigationRail(containerColor = Palette.Rail, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(12.dp))
                Section.entries.forEach { s ->
                    NavigationRailItem(
                        selected = section == s && opened.isEmpty(),
                        onClick = {
                            section = s
                            opened.clear()
                        },
                        icon = { Icon(s.icon, contentDescription = null) },
                        label = { Text(s.label(), fontSize = 12.sp) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Palette.Accent,
                            selectedTextColor = Palette.Accent,
                            indicatorColor = Color.White.copy(alpha = 0.08f),
                            unselectedIconColor = Palette.Muted,
                            unselectedTextColor = Palette.Muted,
                        ),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val top = opened.lastOrNull()
                if (top != null) {
                    CollectionScreen(top, ui, player, onBack = { opened.removeAt(opened.lastIndex) })
                } else {
                    when (section) {
                        Section.HOME -> HomeSection(spotify, signedIn = user != null, open = open)
                        Section.SEARCH -> SearchSection(player, ui, spotify, signedIn = user != null)
                        Section.LIBRARY -> LibrarySection(spotify, user, open = open)
                        Section.METRICS -> MetricsSection()
                        Section.JAM -> JamSection(jam)
                        Section.ACCOUNT -> AccountSection(spotify, user) { user = it }
                    }
                }
            }
        }
        MiniPlayer(ui, player, onOpen = { if (ui.current != null) fullPlayer = true })
    }
}

@Composable
internal fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp)) {
        Text(text, Modifier.align(Alignment.Center), color = Palette.Muted)
    }
}

/** A list that loads once; [load] is the whole list (every page), not a screen's worth. */
@Composable
internal fun <T> Loaded(key: Any, load: suspend () -> List<T>, content: @Composable (List<T>) -> Unit) {
    var items by remember(key) { mutableStateOf<List<T>?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        runCatching { load() }.onSuccess { items = it }.onFailure { error = it.message ?: S.couldNotLoad }
    }
    when {
        error != null -> Centered(error.orEmpty())
        items == null -> Box(Modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.Accent)
        }
        else -> content(items.orEmpty())
    }
}

// =================================================================================================
// HOME
// =================================================================================================

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> S.goodMorning
    in 12..19 -> S.goodAfternoon
    else -> S.goodEvening
}

/** The country as the chart playlists spell it ("Top 50 - Spain"), as on Android. */
private fun chartCountry(): String? = when (java.util.Locale.getDefault().country) {
    "US" -> "USA"
    "GB" -> "UK"
    else -> java.util.Locale.getDefault().getDisplayCountry(java.util.Locale.ENGLISH).takeIf { it.isNotBlank() }
}

private fun collectionOf(item: BrowseSectionItem, spotify: SpotifyLibrary): Collection = when (item) {
    is BrowseSectionItem.PlaylistItem -> item.playlist.let { p ->
        Collection("pl-${p.id}", S.playlist, p.name, p.owner?.name.orEmpty(), p.images) { spotify.playlistTracks(p.id) }
    }
    is BrowseSectionItem.AlbumItem -> item.album.let { a ->
        Collection("al-${a.id}", S.album, a.name, a.artists.joinToString(", ") { it.name }, a.images) {
            // An album's tracks come without the album on them; put it back for the covers.
            spotify.albumTracks(a.id).map { t -> if (t.album == null) t.copy(album = a) else t }
        }
    }
}

/** Spotify's sections — the personal Home with an account, the public one without — as rows of covers. */
@Composable
private fun HomeSection(spotify: SpotifyLibrary, signedIn: Boolean, open: (Collection) -> Unit) {
    Loaded("home-$signedIn", {
        if (signedIn) spotify.home()
        else spotify.publicHome(chartCountry(), chartsTitle = S.charts, moodsTitle = S.moods)
    }) { sections ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "greeting") {
                Box(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Palette.Accent.copy(alpha = 0.25f), Color.Transparent)))
                        .padding(start = 24.dp, top = 28.dp, bottom = 12.dp),
                ) {
                    Text(greeting(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
            }
            sections.forEach { section ->
                item(key = "h-${section.id}") {
                    Column {
                        Text(
                            section.title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // Indexed: the same playlist can appear twice in a section, and a key must not.
                            itemsIndexed(section.items, key = { i, it -> "${section.id}-$i-${keyOf(it)}" }) { _, item ->
                                val c = collectionOf(item, spotify)
                                CoverCard(c.title, c.subtitle, c.images, onClick = { open(c) })
                            }
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

/** The phone's playlist card (PlaylistItemCard), a size up for a pointer and a bigger screen. */
@Composable
private fun CoverCard(title: String, subtitle: String, images: List<SpotifyImage>, onClick: () -> Unit) {
    Column(Modifier.width(176.dp).clip8().clickable(onClick = onClick).padding(4.dp)) {
        RemoteImage(images.best(300), title, RoundedCornerShape(8.dp), Modifier.size(168.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, fontSize = 12.sp, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun Modifier.clip8() = this.then(Modifier.background(Color.Transparent, RoundedCornerShape(8.dp)))

// =================================================================================================
// COLLECTION (playlist / album)
// =================================================================================================

@Composable
private fun CollectionScreen(c: Collection, ui: PlayerUi, player: QueuePlayer, onBack: () -> Unit) {
    Loaded(c.key, c.load) { tracks ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xFF2A2A2A), Palette.Background)))
                        .padding(24.dp),
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back) }
                    Row(verticalAlignment = Alignment.Bottom) {
                        RemoteImage(c.images.best(600), c.title, RoundedCornerShape(8.dp), Modifier.size(200.dp))
                        Spacer(Modifier.width(24.dp))
                        Column {
                            Text(c.kind, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Muted)
                            Text(c.title, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(c.subtitle, S.songs(tracks.size)).filter { it.isNotBlank() }.joinToString(" • "),
                                color = Palette.Muted,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { player.playAll(tracks, 0) },
                                    enabled = tracks.isNotEmpty(),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black),
                                    modifier = Modifier.size(56.dp),
                                    contentPadding = PaddingValues(0.dp),
                                ) { Icon(Icons.Default.PlayArrow, contentDescription = S.play, modifier = Modifier.size(32.dp)) }
                                Spacer(Modifier.width(16.dp))
                                IconButton(onClick = { player.playAll(tracks.shuffled(), 0) }, enabled = tracks.isNotEmpty()) {
                                    Icon(Icons.Default.Shuffle, contentDescription = S.shuffle, tint = Palette.Muted)
                                }
                            }
                        }
                    }
                }
            }
            itemsIndexed(tracks, key = { i, t -> "$i-${t.id}" }) { index, track ->
                TrackRow(
                    number = index + 1,
                    track = track,
                    current = ui.current?.id != null && ui.queue == tracks && ui.index == index,
                    onClick = { player.playAll(tracks, index) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(number: Int?, track: FullTrack, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                "$number",
                color = if (current) Palette.Accent else Palette.Muted,
                modifier = Modifier.width(36.dp),
                fontSize = 14.sp,
            )
        }
        RemoteImage(track.album?.images.best(64), track.name, RoundedCornerShape(4.dp), Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) Palette.Accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artists.joinToString(", ") { it.name },
                fontSize = 13.sp,
                color = Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatMs(track.durationMs.toLong()), fontSize = 13.sp, color = Palette.Muted)
    }
}

// =================================================================================================
// SEARCH
// =================================================================================================

@Composable
private fun SearchSection(player: QueuePlayer, ui: PlayerUi, spotify: SpotifyLibrary, signedIn: Boolean) {
    var query by remember { mutableStateOf("") }
    var fromSpotify by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FullTrack>?>(null) }
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
            found.onSuccess { results = it }.onFailure { error = it.message ?: S.noResults }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(S.search, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 28.dp))
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).widthIn(max = 720.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(S.searchHint) },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent,
                    unfocusedContainerColor = Palette.Card,
                    focusedContainerColor = Palette.Card,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            Spacer(Modifier.width(12.dp))
            val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Accent, selectedLabelColor = Color.Black)
            FilterChip(selected = !fromSpotify, onClick = { fromSpotify = false; search() }, label = { Text("YouTube Music") }, colors = chip)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = fromSpotify,
                onClick = { fromSpotify = true; search() },
                enabled = signedIn,
                label = { Text("Spotify") },
                colors = chip,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = results
            when {
                searching -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.Accent)
                error != null -> Centered(error.orEmpty())
                list == null -> Centered(S.searchEmpty)
                list.isEmpty() -> Centered(S.noResults)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(list, key = { i, t -> "$i-${t.id}" }) { index, track ->
                        TrackRow(
                            number = null,
                            track = track,
                            current = ui.queue == list && ui.index == index,
                            onClick = { player.playAll(list, index) },
                        )
                    }
                }
            }
        }
    }
}

// =================================================================================================
// LIBRARY
// =================================================================================================

@Composable
private fun LibrarySection(spotify: SpotifyLibrary, user: SpotifyUser?, open: (Collection) -> Unit) {
    if (user == null) {
        Centered(S.signInForLibrary)
        return
    }
    Loaded("playlists", { spotify.playlists() }) { playlists ->
        Column(Modifier.fillMaxSize()) {
            Text(S.library, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(184.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "liked") {
                    val liked = Collection("liked", S.playlist, S.liked, user.name ?: user.id, emptyList()) { spotify.likedSongs() }
                    Column(Modifier.width(176.dp).clickable { open(liked) }.padding(4.dp)) {
                        Box(
                            Modifier.size(168.dp).background(
                                Brush.linearGradient(listOf(Color(0xFF4B2BD6), Color(0xFF8E8EE5))),
                                RoundedCornerShape(8.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(56.dp)) }
                        Spacer(Modifier.height(8.dp))
                        Text(S.liked, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                gridItemsIndexed(playlists, key = { i, p -> "$i-${p.id}" }) { _, p ->
                    val c = Collection("pl-${p.id}", S.playlist, p.name, p.owner?.name.orEmpty(), p.images) { spotify.playlistTracks(p.id) }
                    CoverCard(p.name, listOfNotNull(p.owner?.name, p.totalTracks?.let { S.songs(it) }).joinToString(" • "), p.images) { open(c) }
                }
            }
        }
    }
}

// =================================================================================================
// ACCOUNT
// =================================================================================================

@Composable
private fun AccountSection(spotify: SpotifyLibrary, user: SpotifyUser?, onUser: (SpotifyUser?) -> Unit) {
    var cookie by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(S.account, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (user != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(user.images.best(64), user.name ?: user.id, CircleShape, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Text("${S.signedInAs} ${user.name ?: user.id}", fontWeight = FontWeight.SemiBold)
            }
            Button(onClick = {
                spotify.logout()
                onUser(null)
            }) { Text(S.signOut) }
            return@Column
        }
        Text("Spotify", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(S.signInHelp, color = Palette.Muted)
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
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { spotify.login(cookie) }
                        .onSuccess {
                            cookie = ""
                            onUser(it)
                        }
                        .onFailure { error = it.message ?: S.couldNotLoad }
                    busy = false
                }
            },
        ) { Text(if (busy) S.signingIn else S.signIn) }
    }
}

// =================================================================================================
// PLAYER
// =================================================================================================

private val repeatModes = listOf("off", "all", "one")

/** The playback position, with the slider following the pointer while it is dragged. */
@Composable
private fun Progress(ui: PlayerUi, player: QueuePlayer, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = ui.durationMs ?: (ui.current?.durationMs?.toLong() ?: 0L)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(formatMs(if (dragging) (dragValue * duration).toLong() else ui.positionMs), fontSize = 12.sp, color = Palette.Muted)
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
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.Accent),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(formatMs(duration), fontSize = 12.sp, color = Palette.Muted)
    }
}

@Composable
private fun Controls(ui: PlayerUi, player: QueuePlayer, big: Boolean) {
    var repeat by remember { mutableIntStateOf(0) }
    val playSize = if (big) 64.dp else 44.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 20.dp else 8.dp)) {
        IconButton(onClick = {
            repeat = (repeat + 1) % repeatModes.size
            player.setRepeat(repeatModes[repeat])
        }) {
            Icon(
                if (repeatModes[repeat] == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                contentDescription = "Repeat ${repeatModes[repeat]}",
                tint = if (repeat == 0) Palette.Muted else Palette.Accent,
            )
        }
        IconButton(onClick = player::previous, enabled = ui.current != null) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(if (big) 36.dp else 28.dp))
        }
        Button(
            onClick = player::togglePause,
            enabled = ui.current != null && !ui.loading,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = if (big) Palette.Accent else Color.White, contentColor = Color.Black),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(playSize),
        ) {
            if (ui.loading) {
                CircularProgressIndicator(Modifier.size(playSize / 2), color = Color.Black, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (ui.playing) "Pause" else "Play",
                    modifier = Modifier.size(playSize * 0.55f),
                )
            }
        }
        IconButton(onClick = player::next, enabled = ui.current != null) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(if (big) 36.dp else 28.dp))
        }
    }
}

@Composable
private fun Volume(ui: PlayerUi, player: QueuePlayer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Palette.Muted)
        Slider(
            value = ui.volume,
            onValueChange = player::setVolume,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.Accent),
            modifier = Modifier.width(120.dp),
        )
    }
}

/** The phone's mini player: the cover and the song on the left (they open the player), controls in the middle. */
@Composable
private fun MiniPlayer(ui: PlayerUi, player: QueuePlayer, onOpen: () -> Unit) {
    val track = ui.current
    Row(
        Modifier.fillMaxWidth().background(Palette.Bar).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clickable(enabled = track != null, onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(track?.album?.images.best(128), track?.name ?: "", RoundedCornerShape(6.dp), Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(track?.name ?: S.notPlaying, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ui.error ?: track?.artists?.joinToString(", ") { it.name }.orEmpty(),
                    fontSize = 13.sp,
                    color = if (ui.error != null) MaterialTheme.colorScheme.error else Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Controls(ui, player, big = false)
            Progress(ui, player, Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Volume(ui, player) }
    }
}

/** The full player, as the tablet shows it in landscape: the cover on one side, the song and its controls beside it, the queue under. */
@Composable
private fun NowPlaying(ui: PlayerUi, player: QueuePlayer, onClose: () -> Unit) {
    val track = ui.current
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2C3E37), Palette.Background))),
    ) {
        val coverSide = (minOf(maxWidth * 0.45f, maxHeight - 140.dp)).coerceAtLeast(200.dp)
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = S.back) }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(track?.album?.images.best(640), track?.name ?: "", RoundedCornerShape(12.dp), Modifier.size(coverSide).aspectRatio(1f))
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(track?.name ?: S.notPlaying, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track?.artists?.joinToString(", ") { it.name }.orEmpty(), fontSize = 18.sp, color = Palette.Accent, maxLines = 1)
                    track?.album?.name?.let { Text(it.uppercase(), fontSize = 14.sp, color = Palette.Muted, maxLines = 1) }
                    Spacer(Modifier.height(24.dp))
                    Progress(ui, player, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Controls(ui, player, big = true)
                        Volume(ui, player)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(S.queue, fontWeight = FontWeight.Bold, color = Palette.Muted)
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        itemsIndexed(ui.queue, key = { i, t -> "q-$i-${t.id}" }) { index, t ->
                            TrackRow(number = index + 1, track = t, current = index == ui.index, onClick = { player.jumpTo(index) })
                        }
                    }
                }
            }
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
