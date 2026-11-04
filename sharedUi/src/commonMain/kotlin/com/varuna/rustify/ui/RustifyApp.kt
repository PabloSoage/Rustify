package com.varuna.rustify.ui

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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.varuna.rustify.bridge.BrowseSection
import com.varuna.rustify.bridge.BrowseSectionItem
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.TrackFavorites
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.bridge.effectiveCoverUrl
import com.varuna.rustify.ui.components.SpotifyLikeButton
import com.varuna.rustify.ui.screens.AlbumScreen
import com.varuna.rustify.ui.screens.ArtistAllSongsScreen
import com.varuna.rustify.ui.screens.ArtistScreen
import com.varuna.rustify.ui.screens.HomeScreen
import com.varuna.rustify.ui.screens.LibraryScreen
import com.varuna.rustify.ui.screens.MetricsScreen
import com.varuna.rustify.ui.screens.NewReleasesScreen
import com.varuna.rustify.ui.screens.PlaylistScreen
import com.varuna.rustify.ui.screens.RadioScreen
import com.varuna.rustify.ui.screens.SearchScreen
import com.varuna.rustify.ui.screens.TrackScreen
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
import kotlinx.coroutines.launch
import com.varuna.rustify.Screen
import com.varuna.rustify.homeError
import com.varuna.rustify.navigateDeepLink
import com.varuna.rustify.resumeListening
import com.varuna.rustify.ui.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RustifyApp(
    spotifyRepo: SpotifyRepository,
    shell: AppShell,
    modifier: Modifier = Modifier,
    initialDeepLink: String? = null,
    intentFlow: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(),
    onLanguageChanged: (String) -> Unit = {}
) {
    val platform = LocalAppPlatform.current
    val services = LocalAppServices.current
    // Single shared YTM repository so favorites/playlists stay consistent across the library tab
    // and all first-class YTM screens.
    val ytmRepo = remember { YtMusicRepository(platform.filesDir) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val audioPlayerService = com.varuna.rustify.player.LocalPlayer.current
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
            navigateDeepLink(initialDeepLink, navigationStack, audioPlayerService, shell::resolveMapsLink)
        }
    }

    LaunchedEffect(intentFlow) {
        intentFlow.collect { deepLink ->
            navigateDeepLink(deepLink, navigationStack, audioPlayerService, shell::resolveMapsLink)
        }
    }

    // What a platform screen reaches in the root.
    val host = ShellHost(
        spotifyRepo = spotifyRepo,
        ytmRepo = ytmRepo,
        player = audioPlayerService,
        navigate = { navigationStack.add(it) },
        back = { if (navigationStack.size > 1) navigationStack.removeAt(navigationStack.lastIndex) },
        isSignedInNow = { isLoggedIn },
        signIn = { showWebView = true },
        isSigningInNow = { showWebView },
        signedOut = {
            isLoggedIn = false
            browseSections = null
            errorMessage = null
        },
        settingsCategoryNow = { settingsCategory },
        onSettingsCategory = { settingsCategory = it },
        onLanguageChanged = { newCode ->
            onLanguageChanged(newCode)
            coroutineScope.launch {
                try {
                    browseSections = spotifyRepo.getBrowseSections(10)
                } catch (e: Exception) {
                    com.varuna.rustify.platform.Log.w("RustifyApp", "Failed to refresh", e)
                }
            }
        },
    )
    // Updates, Drive sync, the web player mode (Android's).
    shell.StartupEffects(host)

    // Physical / Gesture Back Button handling
    PlatformBackHandler(enabled = navigationStack.size > 1) {
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
                    errorMessage = homeError(platform, e.message)
                }
            } else if (!spotifyRepo.hasSavedSession()) {
                // Dead session (really revoked/expired → restoreSession cleared the credentials).
                // The app stays usable; Home asks to sign in again where the sections were.
                isLoggedIn = false
                browseSections = null
                sessionExpired = true
            } else {
                // A saved session still exists = transient failure: stay with the cached Home.
                errorMessage = homeError(platform, result?.error)
            }
            isRunning = false
        }
    }

    if (showWebView) {
        shell.SignIn(
            onLogin = { codeOrCookie ->
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
                            errorMessage = homeError(platform, e.message)
                        }
                    } else {
                        errorMessage = "Authentication failed: ${result.error}"
                        // A stale `sp_dc` that persists in the WebView (from closing the app mid-login)
                        // can auto-trigger this login and fail repeatedly. On failure, clear the cookies
                        // so the next attempt shows a clean form.
                        shell.onLoginFailed()
                    }
                    isRunning = false
                }
            },
            onCancel = { showWebView = false }
        )
        return
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

    val isLandscape = isLandscape()
    shell.SystemBars(isLandscape)


    Scaffold(
        bottomBar = {
            if (isTravelScreen) return@Scaffold // Travel draws its own miniplayer over the map.
            Column(modifier = Modifier.fillMaxWidth()) {
                ResumedNotice(audioPlayerService)
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
                                val activeDownloads by services.activeDownloadCount.collectAsState()
                                if (activeDownloads > 0 && currentScreen != Screen.Home) {
                                    androidx.compose.material3.BadgedBox(
                                        badge = {
                                            androidx.compose.material3.Badge(containerColor = Color.Red) {
                                                Text(activeDownloads.toString(), color = Color.White)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Home, contentDescription = stringResource(Res.string.nav_home))
                                    }
                                } else {
                                    Icon(Icons.Default.Home, contentDescription = stringResource(Res.string.nav_home))
                                }
                            },
                            label = { Text(stringResource(Res.string.nav_home)) },
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
                            icon = { Icon(Icons.Default.Search, contentDescription = stringResource(Res.string.nav_search)) },
                            label = { Text(stringResource(Res.string.nav_search)) },
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
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(Res.string.nav_library)) },
                            label = { Text(stringResource(Res.string.nav_library)) },
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
                            if (!isLoggedIn) publicSections = com.varuna.rustify.bridge.PublicHome.sections(platform, spotifyRepo)
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
                                    coroutineScope.launch { publicSections = com.varuna.rustify.bridge.PublicHome.sections(platform, spotifyRepo, refresh = true) }
                                    return@HomeScreen
                                }

                                coroutineScope.launch {
                                    isRunning = true
                                    errorMessage = null
                                    try {
                                        browseSections = spotifyRepo.getBrowseSections(10)
                                    } catch (e: Exception) {
                                        errorMessage = homeError(platform, e.message)
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
                                val pasted = platform.readClipboard()
                                if (pasted.isNullOrBlank()) {
                                    platform.toast(Res.string.paste_clipboard_empty)
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
                                    else -> platform.toast(Res.string.paste_no_ytm_link)
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
                is Screen.Downloads -> {
                    com.varuna.rustify.ui.screens.DownloadsScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) },
                        onOpenCustom = { navigationStack.add(Screen.CustomDownload) }
                    )
                }
                // Not moved yet (docs serie-4.x/27): the platform draws them.
                is Screen.WebPlayer, is Screen.CustomDownload,
                is Screen.NanoChat, is Screen.AudioLab, is Screen.Dj, is Screen.Travel,
                is Screen.TravelWithDestination -> shell.PlatformScreen(currentScreen, host)
                is Screen.Settings -> com.varuna.rustify.ui.screens.SettingsScreen(shell, host)
                is Screen.MatchEditor -> {
                    com.varuna.rustify.ui.screens.MatchEditorScreen(
                        spotifyRepo = spotifyRepo,
                        audioPlayerService = audioPlayerService,
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.LogViewer -> {
                    com.varuna.rustify.ui.screens.LogViewerScreen(
                        onBack = { navigationStack.removeAt(navigationStack.lastIndex) }
                    )
                }
                is Screen.Metrics -> {
                    MetricsScreen(
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
                        icon = { Icon(Icons.Default.Home, contentDescription = stringResource(Res.string.nav_home)) },
                        label = { Text(stringResource(Res.string.nav_home)) },
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
                        icon = { Icon(Icons.Default.Search, contentDescription = stringResource(Res.string.nav_search)) },
                        label = { Text(stringResource(Res.string.nav_search)) },
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
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(Res.string.nav_library)) },
                        label = { Text(stringResource(Res.string.nav_library)) },
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

/**
 * "Resumed at 34:12 · Start over" (4.9), while a long track has just picked up where it was left.
 * It goes away by itself after a few seconds; the place is kept unless "start over" is pressed.
 */
@Composable
private fun ResumedNotice(audioPlayerService: com.varuna.rustify.player.PlayerController) {
    val resumed by audioPlayerService.resumedAt.collectAsState()
    val at = resumed ?: return
    LaunchedEffect(at) {
        kotlinx.coroutines.delay(8_000)
        audioPlayerService.dismissResumed()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        Text(
            stringResource(Res.string.resumed_at, formatPlace(at.second)),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        androidx.compose.material3.TextButton(onClick = { audioPlayerService.startOver() }) {
            Text(stringResource(Res.string.start_over), color = Color(0xFF1DB954))
        }
    }
}

/** 34:12, or 1:34:12 past the hour. */
private fun formatPlace(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
