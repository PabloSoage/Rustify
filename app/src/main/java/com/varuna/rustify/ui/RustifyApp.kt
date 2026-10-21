package com.varuna.rustify.ui

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.varuna.rustify.bridge.TrackFavorites
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.bridge.effectiveCoverUrl
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.ui.components.SpotifyLikeButton
import com.varuna.rustify.ui.screens.AlbumScreen
import com.varuna.rustify.ui.screens.ArtistAllSongsScreen
import com.varuna.rustify.ui.screens.ArtistScreen
import com.varuna.rustify.ui.screens.DjScreen
import com.varuna.rustify.ui.screens.HomeScreen
import com.varuna.rustify.ui.screens.LibraryScreen
import com.varuna.rustify.ui.screens.LogViewerScreen
import com.varuna.rustify.ui.screens.MetricsScreen
import com.varuna.rustify.ui.screens.NewReleasesScreen
import com.varuna.rustify.ui.screens.PlaylistScreen
import com.varuna.rustify.ui.screens.RadioScreen
import com.varuna.rustify.ui.screens.SearchScreen
import com.varuna.rustify.ui.screens.SettingsScreen
import com.varuna.rustify.ui.screens.TrackScreen
import com.varuna.rustify.ui.screens.TravelScreen
import com.varuna.rustify.ui.screens.YtMusicAlbumScreen
import com.varuna.rustify.ui.screens.YtMusicArtistScreen
import com.varuna.rustify.ui.screens.YtMusicLocalPlaylistScreen
import com.varuna.rustify.ui.screens.YtMusicPlaylistScreen
import com.varuna.rustify.ui.screens.YtMusicSearchScreen
import com.varuna.rustify.ui.theme.RustifyTheme
import com.varuna.rustify.util.LinkTarget
import com.varuna.rustify.util.canonicalUrl
import com.varuna.rustify.util.deepLinkToken
import com.varuna.rustify.util.bouncingMarquee
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.varuna.rustify.R
import com.varuna.rustify.*
import com.varuna.rustify.bridge.*
import com.varuna.rustify.ui.screens.*
import com.varuna.rustify.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RustifyApp(
    modifier: Modifier = Modifier,
    initialDeepLink: String? = null,
    intentFlow: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(),
    onLanguageChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val spotifyRepo = remember { SpotifyRepository(context) }
    // Single shared YTM repository so favorites/playlists stay consistent across the library tab
    // and all first-class YTM screens.
    val ytmRepo = remember { YtMusicRepository(context.applicationContext) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val audioPlayerService = remember { AudioPlayerService.getInstance(context) }
    // Don't tear down the player on every Activity dispose (rotation/recomposition would kill
    // playback mid-song). Persist state synchronously; the foreground service + onTaskRemoved
    // handle actual teardown when the user leaves for real.
    DisposableEffect(audioPlayerService) {
        onDispose {
            audioPlayerService.saveNow()
        }
    }


    var isRunning by remember { mutableStateOf(false) }
    // Optimistic login: if a saved session already exists (sp_dc cookie), enter the app directly
    // (with cached Home/library and local music accessible) and refresh the token IN THE BACKGROUND,
    // instead of blocking the whole app behind a network validation that, if Spotify fails, would
    // leave the user stuck on the login screen unable to even open local music.
    var isLoggedIn by remember { mutableStateOf(spotifyRepo.hasSavedSession()) }
    // The saved session was revoked: Home says so in place of its sections, once, instead of the
    // whole app turning into a login screen as it did up to 3.7.
    var sessionExpired by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(false) }
    var browseSections by remember { mutableStateOf<List<BrowseSection>?>(null) }
    // What you were in the middle of. Refreshed whenever Home becomes visible, because it changes
    // while you are elsewhere in the app — playing something is what updates it.
    var continueListening by remember {
        mutableStateOf<List<com.varuna.rustify.bridge.ListeningSession>>(emptyList())
    }
    /** The row being resumed, if any — it refetches the context, which is not instant. */
    var resumingSessionId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // In-app updater: the startup check leaves the new release here (if any) and the
    // changelog+download dialog is rendered as an overlay (visible both in the app and on the
    // standby screen). The manual check from Settings reuses the same dialog.
    var pendingUpdate by remember { mutableStateOf<com.varuna.rustify.update.AppUpdate.UpdateInfo?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val navigationStack = remember { mutableStateListOf<Screen>(Screen.Home) }

    // Settings category is hoisted here so it survives pushing a sub-screen (View Logs, Metrics,
    // Match editor…) and popping back — otherwise Settings recomposes fresh at the root menu instead of
    // returning to the category you came from.
    var settingsCategory by rememberSaveable { mutableStateOf<String?>(null) }

    // Hoisted state for LibraryScreen to survive rotation and navigation pops
    // Without an account the Spotify tabs only offer to connect one, so start on the music that is here.
    var librarySelectedTab by rememberSaveable {
        mutableStateOf(
            if (spotifyRepo.hasSavedSession()) com.varuna.rustify.ui.screens.LibraryTab.PLAYLISTS
            else com.varuna.rustify.ui.screens.LibraryTab.LOCAL
        )
    }
    var librarySelectedGroup by rememberSaveable { mutableStateOf("Tracks") }

    LaunchedEffect(Unit) {
        if (initialDeepLink != null) {
            navigateDeepLink(initialDeepLink, navigationStack, audioPlayerService)
        }
    }

    LaunchedEffect(intentFlow) {
        intentFlow.collect { deepLink ->
            navigateDeepLink(deepLink, navigationStack, audioPlayerService)
        }
    }

    // Restore the experimental web-player mode so the transport controls keep driving the page across
    // restarts. The WebView itself is created lazily, when the screen is first opened.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("web_player_backend", false)) {
            audioPlayerService.setWebPlayerMode(true)
        }
    }

    // Check for updates on startup (if the toggle is enabled, which it is by default).
    // Silent: if the network fails or you're already up to date, it stays out of the way; it only
    // opens the dialog when a new version exists.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("rustify_settings", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("check_updates_on_start", true)) {
            runCatching { com.varuna.rustify.update.AppUpdate.check(context) }
                .getOrNull()?.let { pendingUpdate = it }
        }
    }

    // Silent auto-sync on app open: only if the account is linked, the "auto-sync" toggle is on,
    // and a token is available WITHOUT prompting for consent (background refresh). If it needs a
    // consent UI, it is deferred until the user taps "Sync now" in Settings. Never shows a dialog
    // here.
    LaunchedEffect(Unit) {
        val appCtx = context.applicationContext
        if (com.varuna.rustify.sync.DriveSyncPrefs.isLinked(appCtx) &&
            com.varuna.rustify.sync.DriveSyncPrefs.isAutoSync(appCtx)
        ) {
            val drive = com.varuna.rustify.sync.GoogleDriveSync(appCtx)
            val syncWith = { token: String ->
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        com.varuna.rustify.sync.DriveSyncManager(appCtx, drive, spotifyRepo, ytmRepo).syncNow(token)
                    }
                }
                Unit
            }
            if (com.varuna.rustify.sync.DriveSyncPrefs.authMethod(appCtx) == "browser") {
                // Browser — silent refresh with AppAuth; if consent is needed it is deferred to Settings.
                com.varuna.rustify.sync.AppAuthDriveAuth(appCtx).getFreshToken(
                    onToken = syncWith,
                    onNone = { /* requires UI; the user syncs from Settings */ },
                    onError = { /* silent during auto-sync */ }
                )
            } else {
                // Play Services — silent token if consent is cached.
                drive.authorize(
                    onToken = syncWith,
                    onNeedConsent = { /* defer: requires UI; the user syncs from Settings */ },
                    onError = { /* silent during auto-sync */ }
                )
            }
        }
    }

    // Physical / Gesture Back Button handling
    BackHandler(enabled = navigationStack.size > 1) {
        navigationStack.removeAt(navigationStack.lastIndex)
    }

    // Background session refresh (we're already INSIDE if a session existed, see isLoggedIn).
    // Non-blocking: Home shows its own spinner/cache and the other tabs (Search/Library/local) remain
    // navigable meanwhile. A TRANSIENT network failure keeps the user inside with cached data
    // (restoreSession does not clear credentials in that case); only a REALLY dead session
    // (credentials cleared) sends the user to the login screen.
    LaunchedEffect(Unit) {
        // Whatever Home showed last time, before anything touches the network. Without it a start
        // with no connection was a spinner and then an error, with nothing else on the screen.
        if (spotifyRepo.hasSavedSession()) {
            browseSections = spotifyRepo.cachedBrowseSections()
        }
        if (spotifyRepo.hasSavedSession()) {
            isRunning = true
            val result = spotifyRepo.restoreSession()
            if (result?.success == true) {
                try {
                    browseSections = spotifyRepo.getBrowseSections(10)
                } catch (e: Exception) {
                    errorMessage = homeError(context, e.message)
                }
            } else if (!spotifyRepo.hasSavedSession()) {
                // Dead session (really revoked/expired → restoreSession cleared the credentials).
                // The app stays usable; Home asks to sign in again where the sections were.
                isLoggedIn = false
                browseSections = null
                sessionExpired = true
            } else {
                // A saved session still exists = transient failure: stay with the cached Home.
                errorMessage = homeError(context, result?.error)
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
                        sessionExpired = false
                        // Whatever sent us to the login screen is over. Without this, the "Saved
                        // session expired" that the start-up restore left here outlived the login
                        // that answered it, and Home showed it with a retry button over a session
                        // that was working — only the retry cleared it.
                        errorMessage = null
                        try {
                            browseSections = spotifyRepo.getBrowseSections(10)
                        } catch (e: Exception) {
                            errorMessage = homeError(context, e.message)
                        }
                    } else {
                        errorMessage = "Authentication failed: ${result.error}"
                        // A stale `sp_dc` that persists in the WebView (from closing the app mid-login)
                        // can auto-trigger this login and fail repeatedly. On failure, clear the cookies
                        // so the next attempt shows a clean form.
                        runCatching { CookieManager.getInstance().removeAllCookies(null) }
                    }
                    isRunning = false
                }
            },
            onCancel = { showWebView = false }
        )
        return
    }

    // Updater overlay: shown over any screen (app or standby).
    pendingUpdate?.let { info ->
        com.varuna.rustify.update.UpdateAvailableDialog(info = info, onDismiss = { pendingUpdate = null })
    }

    // No login wall since 4.0. Up to 3.7 this was a full-screen "Login to Spotify" that stood in front
    // of everything — local music, YouTube Music, settings — none of which needs Spotify. Signing in
    // is now offered where Spotify is what is missing: Home, the Spotify tabs of the library, and
    // Settings.

    // Render screen based on current navigation stack state
    val currentScreen = navigationStack.lastOrNull() ?: Screen.Home
    val bottomNavScreens = listOf(Screen.Home, Screen.Search, Screen.Library)
    val isBottomNavScreen = bottomNavScreens.contains(currentScreen)
    val isTravelScreen = currentScreen is Screen.Travel

    val playerStateHolder = audioPlayerService.state.collectAsState()
    val playerState by playerStateHolder
    // IMPORTANT: currentTrack must be a LIVE read (derivedStateOf), not a flat snapshot. The screen
    // content is wrapped in `remember(currentScreen){ movableContentOf { … } }` (below), which
    // captures variables once per screen. A `val currentTrack = playerState.currentTrack` would
    // freeze there until the screen changes → the miniplayer and the green "now playing" highlight
    // would stay on the previous song. With derivedStateOf, reading `currentTrack` inside the
    // movableContent recomposes when the track changes.
    val currentTrack by remember { derivedStateOf { playerStateHolder.value.currentTrack } }

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
            if (isTravelScreen) return@Scaffold // Travel draws its own miniplayer over the map.
            Column(modifier = Modifier.fillMaxWidth()) {
                if (currentTrack != null && currentScreen !is Screen.TrackDetail) {
                    val queueIndex = playerState.queue.indexOfFirst { it.id == currentTrack!!.id }
// "Previous" is never disabled: on the first track it rewinds, which is what the notification has
// always done and what the core answers with (`Effect::SeekTo(0)`). Greying it out here was the UI
// disagreeing with the engine about what the button does -- and the notification button proved which
// of the two was right.
                    val hasPrevious = queueIndex >= 0
                    val hasNext = queueIndex != -1 && queueIndex < playerState.queue.lastIndex

                    MiniPlayer(
                        track = currentTrack!!,
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
                            currentTrack!!.id?.let { id ->
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
        contentWindowInsets = if (isLandscape || isTravelScreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets
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
            is Screen.ArtistAllSongs -> "ArtistAllSongs_${currentScreen.id}"
            is Screen.TrackDetail -> "TrackDetail_${currentScreen.id}"
            is Screen.RadioDetail -> "RadioDetail_${currentScreen.trackId}"
            is Screen.YtmSearch -> "YtmSearch"
            is Screen.YtmAlbumDetail -> "YtmAlbumDetail_${currentScreen.browseId}"
            is Screen.YtmArtistDetail -> "YtmArtistDetail_${currentScreen.channelId}"
            is Screen.YtmPlaylistDetail -> "YtmPlaylistDetail_${currentScreen.playlistId}"
            is Screen.YtmLocalPlaylistDetail -> "YtmLocalPlaylistDetail_${currentScreen.localId}"
            is Screen.Settings -> "Settings"
            is Screen.WebPlayer -> "WebPlayer"
            is Screen.MatchEditor -> "MatchEditor"
            is Screen.Downloads -> "Downloads"
            is Screen.CustomDownload -> "CustomDownload"
            is Screen.LogViewer -> "LogViewer"
            is Screen.NanoChat -> "NanoChat"
            is Screen.AudioLab -> "AudioLab"
            is Screen.Metrics -> "Metrics"
            is Screen.NewReleases -> "NewReleases"
            is Screen.Dj -> "Dj"
            is Screen.Travel -> "Travel"
            is Screen.TravelWithDestination -> "TravelDest_${currentScreen.lat}_${currentScreen.lon}"
        }

        val screenContent = remember(currentScreen) {
            movableContentOf {
                saveableStateHolder.SaveableStateProvider(screenKey) {
                    when (currentScreen) {
                    is Screen.Home -> {
                        // Refreshed whenever Home is shown: what you were in the middle of changes
                        // while you are somewhere else in the app, because playing is what changes it.
                        LaunchedEffect(currentScreen) {
                            continueListening = com.varuna.rustify.bridge.ContinueListening.list()
                        }
                        // Without an account Home is built from the public catalogue and your history.
                        var publicSections by remember { mutableStateOf<List<BrowseSection>>(emptyList()) }
                        LaunchedEffect(isLoggedIn) {
                            if (!isLoggedIn) publicSections = com.varuna.rustify.bridge.PublicHome.sections(context, spotifyRepo)
                        }
                        HomeScreen(
                            browseSections = browseSections,
                            publicSections = publicSections,
                            isRunning = isRunning,
                            errorMessage = errorMessage,
                            isSignedIn = isLoggedIn,
                            sessionExpired = sessionExpired,
                            onSignIn = { showWebView = true },
                            onRetry = {
                                if (!isLoggedIn) {
                                    coroutineScope.launch { publicSections = com.varuna.rustify.bridge.PublicHome.sections(context, spotifyRepo, refresh = true) }
                                    return@HomeScreen
                                }

                                coroutineScope.launch {
                                    isRunning = true
                                    errorMessage = null
                                    try {
                                        browseSections = spotifyRepo.getBrowseSections(10)
                                    } catch (e: Exception) {
                                        errorMessage = homeError(context, e.message)
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
                                }
                            },
                            onSettingsClick = {
                                navigationStack.add(Screen.Settings)
                            },
                            onDownloadsClick = {
                                navigationStack.add(Screen.Downloads)
                            },
                            onNewReleasesClick = {
                                navigationStack.add(Screen.NewReleases)
                            },
                            onMetricsClick = {
                                navigationStack.add(Screen.Metrics)
                            },
                            onDjClick = {
                                navigationStack.add(Screen.Dj)
                            },
                            onTravelClick = {
                                navigationStack.add(Screen.Travel)
                            },
                            onWebPlayerClick = {
                                navigationStack.add(Screen.WebPlayer)
                            },
                            continueListening = continueListening,
                            resumingId = resumingSessionId,
                            onResumeListening = { session ->
                                // Resuming refetches the whole context, which on a long playlist is
                                // several round trips — long enough that without a spinner the row
                                // looks like it did nothing, and long enough to be tapped again
                                // while the first one is still in flight. Guarding on the id rather
                                // than on a plain boolean so a *different* row can still be tapped.
                                if (resumingSessionId == null) {
                                    resumingSessionId = session.id
                                    coroutineScope.launch {
                                        try {
                                            resumeListening(
                                                session,
                                                spotifyRepo,
                                                audioPlayerService,
                                                // The spinner ends when the music starts, not when
                                                // the rest of the playlist finishes arriving —
                                                // which now happens afterwards, in the background.
                                                onPlaying = { resumingSessionId = null }
                                            )
                                            continueListening =
                                                com.varuna.rustify.bridge.ContinueListening.list()
                                        } finally {
                                            // Also here, because a resume that failed before playing
                                            // anything would otherwise leave the row permanently
                                            // dead. Setting it twice costs nothing.
                                            resumingSessionId = null
                                        }
                                    }
                                }
                            },
                            onForgetListening = { session ->
                                coroutineScope.launch {
                                    com.varuna.rustify.bridge.ContinueListening.forget(session.id)
                                    continueListening =
                                        com.varuna.rustify.bridge.ContinueListening.list()
                                }
                            }
                        )
                    }
                    is Screen.Search -> {
                        SearchScreen(
                            spotifyRepo = spotifyRepo,
                            onTrackClick = { track -> audioPlayerService.loadPlaylist(listOf(track), 0) },
                            // Pasting a track link opens its detail (doesn't fabricate an
                            // empty stub and try to play it).
                            onOpenTrack = { id -> navigationStack.add(Screen.TrackDetail(id)) },
                            onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                            onGoToQueue = {
                                currentTrack?.id?.let { id ->
                                    navigationStack.add(Screen.TrackDetail(id, openQueue = true))
                                }
                            },
                            onAlbumClick = { id, name, images ->
                                navigationStack.add(Screen.AlbumDetail(id, name, images))
                            },
                            onPlaylistClick = { id, name, images ->
                                navigationStack.add(Screen.PlaylistDetail(id, name, images))
                            },
                            onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                            onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                            currentTrackId = currentTrack?.id
                        )
                    }
                    is Screen.Library -> {
                        LibraryScreen(
                            spotifyRepo = spotifyRepo,
                            isSignedIn = isLoggedIn,
                            onSignIn = { showWebView = true },
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
                                    navigationStack.add(Screen.TrackDetail(id, openQueue = true))
                                }
                            },
                            onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                            onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                            onOpenSettings = { navigationStack.add(Screen.Settings) },
                            ytmRepo = ytmRepo,
                            onYtmOpenSearch = { navigationStack.add(Screen.YtmSearch) },
                            onYtmOpenAlbum = { id, title -> navigationStack.add(Screen.YtmAlbumDetail(id, title)) },
                            onYtmOpenArtist = { id, name -> navigationStack.add(Screen.YtmArtistDetail(id, name)) },
                            onYtmOpenLocalPlaylist = { id -> navigationStack.add(Screen.YtmLocalPlaylistDetail(id)) },
                            onYtmOpenPlaylist = { id, title -> navigationStack.add(Screen.YtmPlaylistDetail(id, title)) },
                            onYtmPasteLink = {
                                val pasted = try {
                                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    cb?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                } catch (e: Exception) { null }
                                if (pasted.isNullOrBlank()) {
                                    android.widget.Toast.makeText(context, R.string.paste_clipboard_empty, android.widget.Toast.LENGTH_SHORT).show()
                                } else when (val link = LinkTarget.parse(pasted)) {
                                    is LinkTarget.YtmTrack -> {
                                        val yt = FullTrack(
                                            id = "ytm:${link.id}", name = "YouTube Music",
                                            externalUri = link.canonicalUrl,
                                            explicit = false, durationMs = 0, isrc = "",
                                            artists = emptyList(), album = null
                                        )
                                        audioPlayerService.loadPlaylist(listOf(yt), 0)
                                    }
                                    is LinkTarget.YtmAlbum -> navigationStack.add(Screen.YtmAlbumDetail(link.id, ""))
                                    is LinkTarget.YtmArtist -> navigationStack.add(Screen.YtmArtistDetail(link.id, ""))
                                    is LinkTarget.YtmPlaylist -> navigationStack.add(Screen.YtmPlaylistDetail(link.id, ""))
                                    // A Spotify link pasted into the YouTube Music screen is a link,
                                    // just not one this screen can open.
                                    else -> android.widget.Toast.makeText(context, R.string.paste_no_ytm_link, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
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
                        onTrackClick = { tracks, index ->
                            audioPlayerService.loadPlaylist(
                                tracks, index,
                                context = com.varuna.rustify.player.PlaybackContext(
                                    id = "playlist:${currentScreen.id}",
                                    label = currentScreen.name,
                                    imageUrl = currentScreen.images.firstOrNull()?.url.orEmpty()
                                )
                            )
                        },
                        onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                        onGoToQueue = {
                            currentTrack?.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id, openQueue = true))
                            }
                        },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
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
                        onTrackClick = { tracks, index ->
                            audioPlayerService.loadPlaylist(
                                tracks, index,
                                context = com.varuna.rustify.player.PlaybackContext(
                                    id = "album:${currentScreen.id}",
                                    label = currentScreen.name,
                                    imageUrl = currentScreen.images.firstOrNull()?.url.orEmpty()
                                )
                            )
                        },
                        onAddToQueue = { track -> audioPlayerService.enqueue(track) },
                        onGoToQueue = {
                            currentTrack?.id?.let { id ->
                                navigationStack.add(Screen.TrackDetail(id, openQueue = true))
                            }
                        },
                        onAlbumClick = { id, name, images ->
                            navigationStack.add(Screen.AlbumDetail(id, name, images))
                        },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) },
                        currentTrackId = currentTrack?.id
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
                                navigationStack.add(Screen.TrackDetail(id, openQueue = true))
                            }
                        },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) },
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                        onShufflePlay = { audioPlayerService.shufflePlay(it) },
                        onViewAllSongs = { name -> navigationStack.add(Screen.ArtistAllSongs(currentScreen.id, name)) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.ArtistAllSongs -> {
                    ArtistAllSongsScreen(
                        artistId = currentScreen.id,
                        artistName = currentScreen.name,
                        spotifyRepo = spotifyRepo,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
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
                        onArtistClick = { id -> navigationStack.add(Screen.ArtistDetail(id)) },
                        onGoToRadio = { id, name -> navigationStack.add(Screen.RadioDetail(id, name)) },
                        ytmRepo = ytmRepo,
                        openQueueOnOpen = currentScreen.openQueue
                    )
                }
                is Screen.RadioDetail -> {
                    RadioScreen(
                        trackId = currentScreen.trackId,
                        trackName = currentScreen.trackName,
                        spotifyRepo = spotifyRepo,
                        audioPlayerService = audioPlayerService,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onOpenTrack = { id -> navigationStack.add(Screen.TrackDetail(id)) }
                    )
                }
                is Screen.NewReleases -> {
                    NewReleasesScreen(
                        spotifyRepo = spotifyRepo,
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onAlbumClick = { id, name, images -> navigationStack.add(Screen.AlbumDetail(id, name, images)) }
                    )
                }
                is Screen.YtmSearch -> {
                    YtMusicSearchScreen(
                        repo = ytmRepo,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAlbum = { id, title -> navigationStack.add(Screen.YtmAlbumDetail(id, title)) },
                        onArtist = { id, name -> navigationStack.add(Screen.YtmArtistDetail(id, name)) },
                        onPlaylist = { id, title -> navigationStack.add(Screen.YtmPlaylistDetail(id, title)) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.YtmAlbumDetail -> {
                    YtMusicAlbumScreen(
                        repo = ytmRepo,
                        browseId = currentScreen.browseId,
                        title = currentScreen.title,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.YtmArtistDetail -> {
                    YtMusicArtistScreen(
                        repo = ytmRepo,
                        channelId = currentScreen.channelId,
                        name = currentScreen.name,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        onAlbum = { id, title -> navigationStack.add(Screen.YtmAlbumDetail(id, title)) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.YtmPlaylistDetail -> {
                    YtMusicPlaylistScreen(
                        repo = ytmRepo,
                        playlistId = currentScreen.playlistId,
                        title = currentScreen.title,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.YtmLocalPlaylistDetail -> {
                    YtMusicLocalPlaylistScreen(
                        repo = ytmRepo,
                        localId = currentScreen.localId,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onTrackClick = { tracks, index -> audioPlayerService.loadPlaylist(tracks, index) },
                        currentTrackId = currentTrack?.id
                    )
                }
                is Screen.WebPlayer -> {
                    com.varuna.rustify.webplayer.WebPlayerScreen(
                        onBackClick = { navigationStack.removeAt(navigationStack.lastIndex) },
                        // Silence Rustify's own engine so two sources never overlap — but not when
                        // web mode is on, where pause() is forwarded to the page and would stop the
                        // very thing this screen is showing.
                        onEnter = {
                            if (!audioPlayerService.isWebPlayerMode) audioPlayerService.pause()
                        }
                    )
                }
                is Screen.Settings -> {
                    SettingsScreen(
                        spotifyRepository = spotifyRepo,
                        ytmRepository = ytmRepo,
                        isSignedIn = isLoggedIn,
                        onSignIn = { showWebView = true },
                        onSignedOut = {
                            isLoggedIn = false
                            browseSections = null
                            errorMessage = null
                        },
                        category = settingsCategory,
                        onCategoryChange = { settingsCategory = it },
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onNavigateLogViewer = { navigationStack.add(Screen.LogViewer) },
                        onNavigateNanoChat = { navigationStack.add(Screen.NanoChat) },
                        onNavigateAudioLab = { navigationStack.add(Screen.AudioLab) },
                        onNavigateMetrics = { navigationStack.add(Screen.Metrics) },
                        onNavigateMatchEditor = { navigationStack.add(Screen.MatchEditor) },
                        onNavigateCustomDownload = { navigationStack.add(Screen.CustomDownload) },
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
                is Screen.MatchEditor -> {
                    com.varuna.rustify.ui.screens.MatchEditorScreen(
                        spotifyRepo = spotifyRepo,
                        audioPlayerService = audioPlayerService,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.Downloads -> {
                    com.varuna.rustify.ui.screens.DownloadsScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onOpenCustom = { navigationStack.add(Screen.CustomDownload) }
                    )
                }
                is Screen.CustomDownload -> {
                    com.varuna.rustify.ui.screens.CustomDownloadScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.NanoChat -> {
                    com.varuna.rustify.ui.screens.NanoChatScreen(onBack = { navigationStack.removeAt(navigationStack.lastIndex) })
                }
                is Screen.AudioLab -> {
                    com.varuna.rustify.ui.screens.AudioLabScreen(onBack = { navigationStack.removeAt(navigationStack.lastIndex) })
                }
                is Screen.LogViewer -> {
                    LogViewerScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.Metrics -> {
                    MetricsScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.Dj -> {
                    DjScreen(
                        spotifyRepo = spotifyRepo,
                        nowPlaying = currentTrack,
                        queue = playerState.queue,
                        onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                        onEnqueueTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.enqueueAll(tracks) },
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onOpenSettings = { navigationStack.add(Screen.Settings) }
                    )
                }
                is Screen.Travel -> {
                    TravelScreen(
                        spotifyRepo = spotifyRepo,
                        onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        currentTrack = currentTrack,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        hasPrev = playerState.queue.indexOfFirst { it.id == currentTrack?.id } > 0,
                        hasNext = playerState.queue.indexOfFirst { it.id == currentTrack?.id }.let { it != -1 && it < playerState.queue.lastIndex },
                        onTogglePlayPause = { audioPlayerService.togglePlayPause() },
                        onSkipPrev = { audioPlayerService.skipToPrevious() },
                        onSkipNext = { audioPlayerService.skipToNext() },
                        onMiniPlayerClick = {
                            currentTrack?.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) }
                        }
                    )
                }
                is Screen.TravelWithDestination -> {
                    TravelScreen(
                        spotifyRepo = spotifyRepo,
                        onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        currentTrack = currentTrack,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        hasPrev = playerState.queue.indexOfFirst { it.id == currentTrack?.id } > 0,
                        hasNext = playerState.queue.indexOfFirst { it.id == currentTrack?.id }.let { it != -1 && it < playerState.queue.lastIndex },
                        onTogglePlayPause = { audioPlayerService.togglePlayPause() },
                        onSkipPrev = { audioPlayerService.skipToPrevious() },
                        onSkipNext = { audioPlayerService.skipToNext() },
                        onMiniPlayerClick = {
                            currentTrack?.id?.let { id -> navigationStack.add(Screen.TrackDetail(id)) }
                        },
                        initialDestination = com.varuna.rustify.travel.TravelRouting.Geo(
                            lat = currentScreen.lat, lon = currentScreen.lon, label = currentScreen.label ?: ""
                        )
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
                        icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) },
                        label = { Text(stringResource(R.string.nav_home)) },
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
                        icon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.nav_search)) },
                        label = { Text(stringResource(R.string.nav_search)) },
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
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(R.string.nav_library)) },
                        label = { Text(stringResource(R.string.nav_library)) },
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
