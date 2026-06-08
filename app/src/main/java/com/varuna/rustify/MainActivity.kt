// app/src/main/java/com/varuna/rustify/MainActivity.kt
package com.varuna.rustify

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.ui.theme.RustifyTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import com.varuna.rustify.bridge.BrowseSection
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.bridge.SpotifyImage
import androidx.activity.compose.BackHandler
import com.varuna.rustify.ui.screens.HomeScreen
import com.varuna.rustify.ui.screens.SearchScreen
import com.varuna.rustify.ui.screens.LibraryScreen
import com.varuna.rustify.ui.screens.SettingsScreen
import com.varuna.rustify.ui.screens.AlbumScreen
import com.varuna.rustify.ui.screens.PlaylistScreen
import com.varuna.rustify.ui.screens.ArtistScreen
import com.varuna.rustify.ui.screens.TrackScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.player.AudioPlayerService
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background


sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Library : Screen()
    data class PlaylistDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class AlbumDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class ArtistDetail(val id: String) : Screen()
    data class TrackDetail(val id: String) : Screen()
    object Settings : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize YoutubeDL
        try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().init(application)
            android.util.Log.d("YoutubeDL", "YoutubeDL initialized successfully.")
            // Auto-update in background
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(application, com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.STABLE)
                    android.util.Log.d("YoutubeDL", "YoutubeDL updated successfully.")
                } catch (e: Exception) {
                    android.util.Log.e("YoutubeDL", "Failed to update YoutubeDL", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("YoutubeDL", "Failed to initialize YoutubeDL", e)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        // Configure Coil image loading with a persistent disk cache (512MB)
        val imageLoader = coil.ImageLoader.Builder(this)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
        coil.Coil.setImageLoader(imageLoader)

        enableEdgeToEdge()
        setContent {
            RustifyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    EngineTester()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineTester(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val spotifyRepo = remember { SpotifyRepository(context) }

    val audioPlayerService = remember { AudioPlayerService(context) }
    DisposableEffect(audioPlayerService) {
        onDispose {
            audioPlayerService.release()
        }
    }


    var isRunning by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(false) }
    var browseSections by remember { mutableStateOf<List<BrowseSection>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val navigationStack = remember { mutableStateListOf<Screen>(Screen.Home) }

    // Physical / Gesture Back Button handling
    BackHandler(enabled = navigationStack.size > 1) {
        navigationStack.removeAt(navigationStack.lastIndex)
    }

    // Auto-restore session on first launch
    LaunchedEffect(Unit) {
        if (spotifyRepo.hasSavedSession()) {
            isRunning = true
            val result = spotifyRepo.restoreSession()
            if (result?.success == true) {
                isLoggedIn = true
                try {
                    browseSections = spotifyRepo.getBrowseSections(10)
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            } else {
                errorMessage = "Saved session expired. Please log in again."
            }
            isRunning = false
        }
    }

    if (showWebView) {
        SpotifyLoginWebView(
            onLoginSuccess = { codeOrCookie ->
                showWebView = false
                isRunning = true

                coroutineScope.launch {
                    val result = spotifyRepo.login(codeOrCookie)
                    if (result.success) {
                        isLoggedIn = true
                        try {
                            browseSections = spotifyRepo.getBrowseSections(10)
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    } else {
                        errorMessage = "Authentication failed: ${result.error}"
                    }
                    isRunning = false
                }
            },
            onCancel = { showWebView = false }
        )
        return
    }

    if (!isLoggedIn) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Text("Spotify Engine Standby", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showWebView = true }, enabled = !isRunning) {
                    Text("Login to Spotify")
                }
                if (isRunning) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                }

                // Removed Developer Settings UI
            }
        }
        return
    }

    // Render screen based on current navigation stack state
    val currentScreen = navigationStack.lastOrNull() ?: Screen.Home
    val bottomNavScreens = listOf(Screen.Home, Screen.Search, Screen.Library)
    val isBottomNavScreen = bottomNavScreens.contains(currentScreen)

    val playerState by audioPlayerService.state.collectAsState()
    val currentTrack = playerState.currentTrack

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (currentTrack != null && currentScreen !is Screen.TrackDetail) {
                    MiniPlayer(
                        track = currentTrack,
                        isPlaying = playerState.isPlaying,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        onTogglePlayPause = { audioPlayerService.togglePlayPause() },
                        onClick = {
                            currentTrack.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id))
                            }
                        }
                    )
                }
                if (isBottomNavScreen && !isLandscape) {
                    NavigationBar(
                        containerColor = Color(0xFF121212),
                        contentColor = Color(0xFF1DB954)
                    ) {
                        NavigationBarItem(
                            selected = currentScreen == Screen.Home,
                            onClick = {
                                if (currentScreen != Screen.Home) {
                                    navigationStack.removeAll { bottomNavScreens.contains(it) }
                                    navigationStack.add(Screen.Home)
                                }
                            },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF1DB954),
                                selectedTextColor = Color(0xFF1DB954),
                                unselectedIconColor = Color.LightGray,
                                unselectedTextColor = Color.LightGray,
                                indicatorColor = Color(0xFF2A2A2A)
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.Search,
                            onClick = {
                                if (currentScreen != Screen.Search) {
                                    navigationStack.removeAll { bottomNavScreens.contains(it) }
                                    navigationStack.add(Screen.Search)
                                }
                            },
                            icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                            label = { Text("Search") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF1DB954),
                                selectedTextColor = Color(0xFF1DB954),
                                unselectedIconColor = Color.LightGray,
                                unselectedTextColor = Color.LightGray,
                                indicatorColor = Color(0xFF2A2A2A)
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.Library,
                            onClick = {
                                if (currentScreen != Screen.Library) {
                                    navigationStack.removeAll { bottomNavScreens.contains(it) }
                                    navigationStack.add(Screen.Library)
                                }
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                            label = { Text("Library") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF1DB954),
                                selectedTextColor = Color(0xFF1DB954),
                                unselectedIconColor = Color.LightGray,
                                unselectedTextColor = Color.LightGray,
                                indicatorColor = Color(0xFF2A2A2A)
                            )
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        val contentModifier = if (isLandscape) {
            modifier.fillMaxSize()
        } else {
            modifier.fillMaxSize().padding(paddingValues)
        }
        val screenContent = @Composable {
            when (currentScreen) {
                is Screen.Home -> {
                    HomeScreen(
                        browseSections = browseSections,
                        isRunning = isRunning,
                        errorMessage = errorMessage,
                        onRetry = {
                            coroutineScope.launch {
                                isRunning = true
                                errorMessage = null
                                try {
                                    browseSections = spotifyRepo.getBrowseSections(10)
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                }
                                isRunning = false
                            }
                        },
                        onItemClick = { item ->
                            when (item) {
                                is BrowseSectionItem.PlaylistItem -> {
                                    navigationStack.add(Screen.PlaylistDetail(item.playlist.id, item.playlist.name, item.playlist.images))
                                }
                                is BrowseSectionItem.AlbumItem -> {
                                    navigationStack.add(Screen.AlbumDetail(item.album.id, item.album.name, item.album.images))
                                }
                                is BrowseSectionItem.ArtistItem -> {
                                    navigationStack.add(Screen.ArtistDetail(item.artist.id))
                                }
                            }
                        },
                        onSettingsClick = {
                            navigationStack.add(Screen.Settings)
                        }
                    )
                }
                is Screen.Search -> {
                    SearchScreen(
                        spotifyRepo = spotifyRepo,
                        onTrackClick = { track -> audioPlayerService.loadAndPlay(track) },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onPlaylistClick = { id, name, images ->
                            navigationStack.add(Screen.PlaylistDetail(id, name, images))
                        },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) }
                    )
                }
                is Screen.Library -> {
                    LibraryScreen(
                        spotifyRepo = spotifyRepo,
                        onPlaylistClick = { id, name, images ->
                            navigationStack.add(Screen.PlaylistDetail(id, name, images))
                        },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) }
                    )
                }
                is Screen.PlaylistDetail -> {
                    PlaylistScreen(
                        playlistId = currentScreen.id,
                        playlistName = currentScreen.name,
                        playlistImages = currentScreen.images,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) }
                    )
                }
                is Screen.AlbumDetail -> {
                    AlbumScreen(
                        albumId = currentScreen.id,
                        albumName = currentScreen.name,
                        albumImages = currentScreen.images,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) }
                    )
                }
                is Screen.ArtistDetail -> {
                    ArtistScreen(
                        artistId = currentScreen.id,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) }
                    )
                }
                is Screen.TrackDetail -> {
                    TrackScreen(
                        trackId = currentScreen.id,
                        spotifyRepo = spotifyRepo,
                        audioPlayerService = audioPlayerService,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) }
                    )
                }
                is Screen.Settings -> {
                    SettingsScreen(
                        spotifyRepository = spotifyRepo,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
            }
        }

        if (isLandscape && isBottomNavScreen) {
            Row(modifier = contentModifier) {
                NavigationRail(
                    containerColor = Color(0xFF121212),
                    contentColor = Color(0xFF1DB954),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    NavigationRailItem(
                        selected = currentScreen == Screen.Home,
                        onClick = {
                            if (currentScreen != Screen.Home) {
                                navigationStack.removeAll { bottomNavScreens.contains(it) }
                                navigationStack.add(Screen.Home)
                            }
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color(0xFF1DB954),
                            selectedTextColor = Color(0xFF1DB954),
                            unselectedIconColor = Color.LightGray,
                            unselectedTextColor = Color.LightGray,
                            indicatorColor = Color(0xFF2A2A2A)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = currentScreen == Screen.Search,
                        onClick = {
                            if (currentScreen != Screen.Search) {
                                navigationStack.removeAll { bottomNavScreens.contains(it) }
                                navigationStack.add(Screen.Search)
                            }
                        },
                        icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        label = { Text("Search") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color(0xFF1DB954),
                            selectedTextColor = Color(0xFF1DB954),
                            unselectedIconColor = Color.LightGray,
                            unselectedTextColor = Color.LightGray,
                            indicatorColor = Color(0xFF2A2A2A)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = currentScreen == Screen.Library,
                        onClick = {
                            if (currentScreen != Screen.Library) {
                                navigationStack.removeAll { bottomNavScreens.contains(it) }
                                navigationStack.add(Screen.Library)
                            }
                        },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                        label = { Text("Library") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color(0xFF1DB954),
                            selectedTextColor = Color(0xFF1DB954),
                            unselectedIconColor = Color.LightGray,
                            unselectedTextColor = Color.LightGray,
                            indicatorColor = Color(0xFF2A2A2A)
                        )
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    screenContent()
                }
            }
        } else {
            Box(modifier = contentModifier) {
                screenContent()
            }
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginWebView(onLoginSuccess: (String) -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    webViewClient = object : WebViewClient() {
                        private var loginAlreadyDone = false

                        private fun checkLoginAndFinish(url: String?) {
                            if (loginAlreadyDone) return

                            if (url != null && url.contains("spotify.com")) {
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies != null && cookies.contains("sp_dc=")) {
                                    val spDc = cookies.split(";")
                                        .map { it.trim() }
                                        .find { it.startsWith("sp_dc=") }
                                        ?.substringAfter("sp_dc=")

                                    if (!spDc.isNullOrEmpty()) {
                                        loginAlreadyDone = true
                                        onLoginSuccess(spDc)
                                    }
                                }
                            }
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            checkLoginAndFinish(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            checkLoginAndFinish(url)
                        }
                    }

                    loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun MiniPlayer(
    track: FullTrack,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyGreen = Color(0xFF1DB954)
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
    val imgUrl = track.album?.images?.minByOrNull { it.width ?: 9999 }?.url

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2A2A))
            .clickable { onClick() }
    ) {
        // Thin progress indicator at the top
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = spotifyGreen,
            trackColor = Color.Gray.copy(alpha = 0.3f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mini Cover Art
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color.DarkGray
            ) {
                if (!imgUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = imgUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.LightGray,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title and Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artists.joinToString(", ") { it.name },
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Play/Pause button
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}