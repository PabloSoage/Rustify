// app/src/main/java/com/varuna/rustify/MainActivity.kt

package com.varuna.rustify

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.varuna.rustify.bridge.BrowseSection
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.effectiveCoverUrl
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.util.SpotifyLink
import com.varuna.rustify.util.SpotifyLinkParser
import com.varuna.rustify.util.bouncingMarquee
import com.varuna.rustify.ui.screens.LogViewerScreen
import com.varuna.rustify.ui.screens.AlbumScreen
import com.varuna.rustify.ui.screens.ArtistScreen
import com.varuna.rustify.ui.screens.HomeScreen
import com.varuna.rustify.ui.screens.LibraryScreen
import com.varuna.rustify.ui.screens.PlaylistScreen
import com.varuna.rustify.ui.screens.SearchScreen
import com.varuna.rustify.ui.screens.SettingsScreen
import com.varuna.rustify.ui.screens.TrackScreen
import com.varuna.rustify.ui.theme.RustifyTheme
import kotlinx.coroutines.launch


sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Library : Screen()
    data class PlaylistDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class AlbumDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class ArtistDetail(val id: String) : Screen()
    data class TrackDetail(val id: String) : Screen()
    object Settings : Screen()
    object Downloads : Screen()
    object LogViewer : Screen()
}

/**
 * Route a deep-link token ("track:ID" / "album:ID" / "playlist:ID" / "artist:ID" /
 * "NOW_PLAYING", or a legacy bare track id) onto the navigation stack (E20).
 */
private fun navigateDeepLink(
    deepLink: String,
    navigationStack: androidx.compose.runtime.snapshots.SnapshotStateList<Screen>,
    audioPlayerService: AudioPlayerService
) {
    if (deepLink == "NOW_PLAYING") {
        val currentTrackId = audioPlayerService.state.value.currentTrack?.id
        if (currentTrackId != null) {
            val last = navigationStack.lastOrNull()
            if (last !is Screen.TrackDetail || last.id != currentTrackId) {
                navigationStack.add(Screen.TrackDetail(currentTrackId))
            }
        }
        return
    }
    val parts = deepLink.split(":", limit = 2)
    if (parts.size != 2) {
        navigationStack.add(Screen.TrackDetail(deepLink))
        return
    }
    when (parts[0]) {
        "track" -> navigationStack.add(Screen.TrackDetail(parts[1]))
        "album" -> navigationStack.add(Screen.AlbumDetail(parts[1], "", emptyList()))
        "playlist" -> navigationStack.add(Screen.PlaylistDetail(parts[1], "", emptyList()))
        "artist" -> navigationStack.add(Screen.ArtistDetail(parts[1]))
        else -> navigationStack.add(Screen.TrackDetail(deepLink))
    }
}

class MainActivity : ComponentActivity() {
    private var initialDeepLink: String? = null

    /**
     * Extract a Spotify link from a shared URL — unified parser (E20).
     * Returns a tagged string like "track:ID", "album:ID", "playlist:ID", "artist:ID",
     * or null if no Spotify link is found.
     */
    private fun extractSpotifyLink(text: String): String? {
        return when (val link = SpotifyLinkParser.parse(text)) {
            is SpotifyLink.Track -> "track:${link.id}"
            is SpotifyLink.Album -> "album:${link.id}"
            is SpotifyLink.Playlist -> "playlist:${link.id}"
            is SpotifyLink.Artist -> "artist:${link.id}"
            null -> null
        }
    }

    @SuppressLint("AppBundleLocaleChanges")
    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("rustify_settings", MODE_PRIVATE)
        val appLang = prefs.getString("app_language", "system") ?: "system"
        if (appLang != "system" && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val locale = java.util.Locale.forLanguageTag(appLang)
            java.util.Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            val newContext = newBase.createConfigurationContext(config)
            super.attachBaseContext(newContext)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean ->
        // Si se deniega, la notificación del reproductor no se verá en Android 13+
    }

    private val intentFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    private fun extractDeepLink(intent: android.content.Intent?): String? {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val uri = intent.data
            // F1.A: wrapper Rustify autoverificable (host propio del usuario, §4.A.2).
            // El host se guarda en prefs para GENERAR el link; los hosts VERIFICADOS van fijados
            // en el manifest a build-time. Aquí desenvolvemos tanto el host de prefs como los
            // baked-in definidos en AppLinksHosts.verifiedHosts.
            val prefs = getSharedPreferences("rustify_settings", MODE_PRIVATE)
            val wrapperHost = prefs.getString("rustify_wrapper_host", null)
            val knownHosts = listOfNotNull(wrapperHost) + com.varuna.rustify.util.AppLinksHosts.verifiedHosts
            if (uri?.scheme == "https" && uri.host in knownHosts
                && uri.pathSegments.firstOrNull() == "r"
            ) {
                val payload = uri.getQueryParameter("s")                 // formato A: ?s=open.spotify.com/track/ID
                    ?: uri.pathSegments.drop(1).joinToString("/")        // formato B: /r/track/ID
                return extractSpotifyLink(payload)                       // reutiliza SpotifyLinkParser
            }
            if (uri?.host == "open.spotify.com") {
                // pathSegments ignores the intl-xx prefix automatically: look for the entity type.
                val segs = uri.pathSegments
                val i = segs.indexOfFirst { it in setOf("track", "album", "playlist", "artist") }
                if (i != -1 && i + 1 < segs.size) {
                    return "${segs[i]}:${segs[i + 1]}"
                }
            } else if (uri?.scheme == "rustify" && uri.host in setOf("track", "album", "playlist", "artist")) {
                val pathSegments = uri.pathSegments
                if (pathSegments.isNotEmpty()) {
                    return "${uri.host}:${pathSegments[0]}"
                }
            } else if (uri?.host == "music.youtube.com" || uri?.host == "www.youtube.com") {
                val v = uri.getQueryParameter("v")
                if (v != null) {
                    android.util.Log.d("MainActivity", "Received YouTube Deep Link: $v")
                }
            } else if (uri?.host == "youtu.be") {
                val v = uri.lastPathSegment
                if (v != null) {
                    android.util.Log.d("MainActivity", "Received YouTube Short Deep Link: $v")
                }
            }
        } else if (intent?.action == android.content.Intent.ACTION_SEND) {
            if (intent.type == "text/plain") {
                val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                if (sharedText != null) {
                    val link = extractSpotifyLink(sharedText)
                    if (link != null) {
                        android.util.Log.d("MainActivity", "Extracted Spotify link from shared text: $link")
                        return link
                    }
                }
            }
        } else if (intent?.action == "com.varuna.rustify.action.VIEW_NOW_PLAYING") {
            return "NOW_PLAYING"
        }
        return null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLink(intent)?.let { intentFlow.tryEmit(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        initialDeepLink = extractDeepLink(intent)

        
        val prefs = getSharedPreferences("rustify_settings", MODE_PRIVATE)
        val appLang = prefs.getString("app_language", "system") ?: "system"
        val langCode = if (appLang == "system") java.util.Locale.getDefault().language else appLang
        com.varuna.rustify.bridge.NativeEngine.setLanguageNative(langCode)

        // LogCapture: init file path + auto-resume if toggle was on (survives crash).
        com.varuna.rustify.util.LogCapture.init(this)
        if (prefs.getBoolean("logging_capture_enabled", false)) {
            com.varuna.rustify.util.LogCapture.start(clearFirst = false)
        }
        
        // Initialize YoutubeDL
        try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().init(application)
            com.yausername.ffmpeg.FFmpeg.getInstance().init(application)
            android.util.Log.d("YoutubeDL", "YoutubeDL and FFmpeg initialized successfully.")
            // Auto-update in background
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val prefs = application.getSharedPreferences("rustify_settings", MODE_PRIVATE)
                    val channelStr = prefs.getString("ytdlp_channel", "NIGHTLY")
                    val channel = if (channelStr == "STABLE") com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.STABLE else com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.NIGHTLY
                    
                    com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(application, channel)
                    android.util.Log.d("YoutubeDL", "YoutubeDL updated successfully.")
                } catch (e: Exception) {
                    android.util.Log.e("YoutubeDL", "Failed to update YoutubeDL", e)
                }

                com.varuna.rustify.bridge.DownloadManager.initDeferred.complete(Unit)
            }
        } catch (e: Exception) {
            android.util.Log.e("YoutubeDL", "Failed to initialize YoutubeDL", e)
            com.varuna.rustify.bridge.DownloadManager.initDeferred.complete(Unit)
        }

        window.attributes.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        
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
            var appLanguage by remember { mutableStateOf(prefs.getString("app_language", "system") ?: "system") }
            val context = LocalContext.current
            val currentConfig = LocalConfiguration.current
            val localizedContext = remember(context, appLanguage, currentConfig) {
                val newConfig = Configuration(currentConfig)
                val locale = if (appLanguage == "system") java.util.Locale.getDefault() else java.util.Locale.forLanguageTag(appLanguage)
                newConfig.setLocale(locale)
                context.createConfigurationContext(newConfig)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContext provides localizedContext,
                // NOT overriding LocalConfiguration — let it flow from the framework
                // so orientation changes are tracked correctly by Compose.
                androidx.activity.compose.LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                RustifyTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF121212)
                    ) {
                        EngineTester(
                            initialDeepLink = initialDeepLink,
                            intentFlow = intentFlow,
                            onLanguageChanged = { newLang -> appLanguage = newLang }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineTester(
    modifier: Modifier = Modifier,
    initialDeepLink: String? = null,
    intentFlow: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(),
    onLanguageChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val spotifyRepo = remember { SpotifyRepository(context) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val audioPlayerService = remember { AudioPlayerService.getInstance(context) }
    // E12 D5: don't tear down the player on every Activity dispose (rotation/recomposition
    // previously killed playback mid-song). Persist state synchronously; the foreground
    // service + onTaskRemoved handle actual teardown when the user leaves for real.
    DisposableEffect(audioPlayerService) {
        onDispose {
            audioPlayerService.saveNow()
        }
    }


    var isRunning by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(false) }
    var browseSections by remember { mutableStateOf<List<BrowseSection>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val navigationStack = remember { mutableStateListOf<Screen>(Screen.Home) }

    // Hoisted state for LibraryScreen to survive rotation and navigation pops
    var librarySelectedTab by rememberSaveable { mutableStateOf(com.varuna.rustify.ui.screens.LibraryTab.PLAYLISTS) }
    var librarySelectedGroup by rememberSaveable { mutableStateOf("Tracks") }

    LaunchedEffect(Unit) {
        if (initialDeepLink != null) {
            navigateDeepLink(initialDeepLink!!, navigationStack, audioPlayerService)
        }
    }

    LaunchedEffect(intentFlow) {
        intentFlow.collect { deepLink ->
            navigateDeepLink(deepLink, navigationStack, audioPlayerService)
        }
    }

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

    LaunchedEffect(currentTrack?.id) {
        val current = currentTrack?.id
        if (current != null && currentScreen is Screen.TrackDetail) {
            if (currentScreen.id != current) {
                navigationStack[navigationStack.lastIndex] = Screen.TrackDetail(current)
            }
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current
    
    DisposableEffect(isLandscape) {
        val window = (view.context as? ComponentActivity)?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        if (insetsController != null) {
            if (isLandscape) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (currentTrack != null && currentScreen !is Screen.TrackDetail) {
                    val queueIndex = playerState.queue.indexOfFirst { it.id == currentTrack.id }
                    val hasPrevious = queueIndex > 0
                    val hasNext = queueIndex != -1 && queueIndex < playerState.queue.lastIndex

                    MiniPlayer(
                        track = currentTrack,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onTogglePlayPause = { audioPlayerService.togglePlayPause() },
                        onSkipPrevious = { audioPlayerService.skipToPrevious() },
                        onSkipNext = { audioPlayerService.skipToNext() },
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
                            icon = { 
                                val activeDownloads by com.varuna.rustify.bridge.DownloadManager.activeDownloadCount.collectAsState()
                                if (activeDownloads > 0 && currentScreen != Screen.Home) {
                                    androidx.compose.material3.BadgedBox(
                                        badge = {
                                            androidx.compose.material3.Badge(containerColor = Color.Red) {
                                                Text(activeDownloads.toString(), color = Color.White)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home))
                                    }
                                } else {
                                    Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home))
                                }
                            },
                            label = { Text(stringResource(R.string.nav_home)) },
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
                            icon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.nav_search)) },
                            label = { Text(stringResource(R.string.nav_search)) },
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
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(R.string.nav_library)) },
                            label = { Text(stringResource(R.string.nav_library)) },
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
        containerColor = Color(0xFF121212),
        contentWindowInsets = if (isLandscape) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets
    ) { paddingValues ->
        val contentModifier = if (isLandscape) {
            modifier.fillMaxSize()
        } else {
            modifier.fillMaxSize().padding(paddingValues)
        }
        val screenKey = when (currentScreen) {
            is Screen.Home -> "Home"
            is Screen.Search -> "Search"
            is Screen.Library -> "Library"
            is Screen.PlaylistDetail -> "PlaylistDetail_${currentScreen.id}"
            is Screen.AlbumDetail -> "AlbumDetail_${currentScreen.id}"
            is Screen.ArtistDetail -> "ArtistDetail_${currentScreen.id}"
            is Screen.TrackDetail -> "TrackDetail_${currentScreen.id}"
            is Screen.Settings -> "Settings"
            is Screen.Downloads -> "Downloads"
            is Screen.LogViewer -> "LogViewer"
        }

        val screenContent = remember(currentScreen) {
            movableContentOf {
                saveableStateHolder.SaveableStateProvider(screenKey) {
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
                            },
                            onDownloadsClick = {
                                navigationStack.add(Screen.Downloads)
                            }
                        )
                    }
                    is Screen.Search -> {
                        SearchScreen(
                            spotifyRepo = spotifyRepo,
                            onTrackClick = { track -> audioPlayerService.loadPlaylist(listOf(track), 0) },
                            // E20: pasting a track link opens its detail (doesn't fabricate an
                            // empty stub and try to reproduce it).
                            onOpenTrack = { id -> navigationStack.add(Screen.TrackDetail(id)) },
                            onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                            onGoToQueue = {
                                currentTrack?.id?.let { id ->
                                    navigationStack.add(Screen.TrackDetail(id))
                                }
                            },
                            onAlbumClick = { id, name, images ->
                                navigationStack.add(Screen.AlbumDetail(id, name, images))
                            },
                            onPlaylistClick = { id, name, images ->
                                navigationStack.add(Screen.PlaylistDetail(id, name, images))
                            },
                            onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                            currentTrackId = currentTrack?.id
                        )
                    }
                    is Screen.Library -> {
                        LibraryScreen(
                            spotifyRepo = spotifyRepo,
                            selectedTab = librarySelectedTab,
                            onTabSelected = { librarySelectedTab = it },
                            selectedGroup = librarySelectedGroup,
                            onGroupSelected = { librarySelectedGroup = it },
                            onPlaylistClick = { id, name, images ->
                                navigationStack.add(Screen.PlaylistDetail(id, name, images))
                            },
                            onAlbumClick = { id, name, images ->
                                navigationStack.add(Screen.AlbumDetail(id, name, images))
                            },
                            onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                            onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                            onGoToQueue = {
                                currentTrack?.id?.let { id ->
                                    navigationStack.add(Screen.TrackDetail(id))
                                }
                            },
                            onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                            onOpenSettings = { navigationStack.add(Screen.Settings) },
                            currentTrackId = currentTrack?.id
                        )
                    }
                is Screen.PlaylistDetail -> {
                    PlaylistScreen(
                        playlistId = currentScreen.id,
                        playlistName = currentScreen.name,
                        playlistImages = currentScreen.images,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                        onGoToQueue = {
                            currentTrack?.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id))
                            }
                        },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.AlbumDetail -> {
                    AlbumScreen(
                        albumId = currentScreen.id,
                        albumName = currentScreen.name,
                        albumImages = currentScreen.images,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                        onGoToQueue = {
                            currentTrack?.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id))
                            }
                        },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) }
                    )
                }
                is Screen.ArtistDetail -> {
                    ArtistScreen(
                        artistId = currentScreen.id,
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                        onGoToQueue = {
                            currentTrack?.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id))
                            }
                        },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) },
                        currentTrackId = currentTrack?.id
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
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onNavigateLogViewer = { navigationStack.add(Screen.LogViewer) },
                        onLocaleChanged = { newCode ->
                            onLanguageChanged(newCode)
                            coroutineScope.launch {
                                try {
                                    browseSections = spotifyRepo.getBrowseSections(10)
                                } catch (e: Exception) {
                                    android.util.Log.w("MainActivity", "Failed to refresh", e)
                                }
                            }
                        }
                    )
                }
                is Screen.Downloads -> {
                    com.varuna.rustify.ui.screens.DownloadsScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.LogViewer -> {
                    LogViewerScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
            }
                }
            }
        }

        if (isLandscape && isBottomNavScreen) {
            Row(modifier = contentModifier) {
                NavigationRail(
                    containerColor = Color(0xFF121212),
                    contentColor = Color(0xFF1DB954),
                    modifier = Modifier.fillMaxHeight(),
                    windowInsets = WindowInsets(0, 0, 0, 0)
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
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyGreen = Color(0xFF1DB954)
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
    val imgUrl = track.effectiveCoverUrl()

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
                    modifier = Modifier.bouncingMarquee()
                )
                Text(
                    text = track.artists.joinToString(", ") { it.name },
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.bouncingMarquee()
                )
            }

            // Previous Button
            IconButton(
                onClick = onSkipPrevious,
                enabled = hasPrevious
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (hasPrevious) Color.White else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Play/Pause button
            IconButton(onClick = onTogglePlayPause) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = spotifyGreen,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Next Button
            IconButton(
                onClick = onSkipNext,
                enabled = hasNext
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = if (hasNext) Color.White else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

