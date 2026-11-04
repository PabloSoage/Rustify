// app/src/main/java/com/varuna/rustify/AndroidAppShell.kt
package com.varuna.rustify

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.varuna.rustify.player.AudioPlayerService
import com.varuna.rustify.ui.AppShell
import com.varuna.rustify.ui.ShellHost
import com.varuna.rustify.ui.SpotifyLoginWebView
import com.varuna.rustify.ui.screens.DjScreen
import com.varuna.rustify.ui.screens.LogViewerScreen
import com.varuna.rustify.ui.SettingsSlot
import com.varuna.rustify.ui.screens.AndroidSettingsActions
import com.varuna.rustify.ui.screens.AndroidSettingsSections
import com.varuna.rustify.ui.screens.TravelScreen
import kotlinx.coroutines.launch

/**
 * [AppShell] on the phone: the WebView sign-in, the start-up chores (updates, Drive, the web player
 * mode), full screen in landscape, and the screens that have not moved to :sharedUi yet, drawn here
 * exactly as RustifyApp drew them.
 */
class AndroidAppShell(context: Context) : AppShell {
    private val app = context.applicationContext

    @Composable
    override fun SignIn(onLogin: (String) -> Unit, onCancel: () -> Unit) =
        SpotifyLoginWebView(onLoginSuccess = onLogin, onCancel = onCancel)

    // A stale `sp_dc` that persists in the WebView (from closing the app mid-login) can auto-trigger
    // the login and fail repeatedly; cleared, the next attempt shows a clean form.
    override fun onLoginFailed() {
        runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
    }

    override suspend fun resolveMapsLink(url: String): Screen.TravelWithDestination? =
        resolveMapsRedirect(url)?.let { Screen.TravelWithDestination(it.lat, it.lon, it.label.ifBlank { null }) }

    @Composable
    override fun SystemBars(landscape: Boolean) {
        val view = LocalView.current
        DisposableEffect(landscape) {
            val window = (view.context as? ComponentActivity)?.window
            val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
            if (insetsController != null) {
                if (landscape) {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
    }

    @Composable
    override fun StartupEffects(host: ShellHost) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val audioPlayerService = remember { AudioPlayerService.getInstance(context) }
        val spotifyRepo = host.spotifyRepo
        val ytmRepo = host.ytmRepo
        // In-app updater: the startup check leaves the new release here (if any) and the
        // changelog+download dialog is rendered as an overlay (visible both in the app and on the
        // standby screen). The manual check from Settings reuses the same dialog.
        var pendingUpdate by remember { mutableStateOf<com.varuna.rustify.update.AppUpdate.UpdateInfo?>(null) }
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

        // Updater overlay: shown over any screen (app or standby).
        pendingUpdate?.let { info ->
            com.varuna.rustify.update.UpdateAvailableDialog(info = info, onDismiss = { pendingUpdate = null })
        }
    }

    @Composable
    override fun SettingsSections(slot: SettingsSlot, host: ShellHost) = AndroidSettingsSections(slot, host)

    @Composable
    override fun SettingsActions(host: ShellHost) = AndroidSettingsActions()

    // The core answers Spotify's titles in this language; AppCompat recreates the activity in it.
    override fun applyLanguage(code: String) {
        val langCode = if (code == "system") android.content.res.Resources.getSystem().configuration.locales[0].language else code
        com.varuna.rustify.bridge.NativeEngine.setLanguageNative(langCode)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            if (code == "system") androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            else androidx.core.os.LocaleListCompat.forLanguageTags(code)
        )
    }

    @Composable
    override fun PlatformScreen(screen: Screen, host: ShellHost) {
        val context = LocalContext.current
        val audioPlayerService = remember { AudioPlayerService.getInstance(context) }
        val playerState by audioPlayerService.state.collectAsState()
        val currentTrack = playerState.currentTrack
        when (screen) {
            is Screen.WebPlayer -> {
                com.varuna.rustify.webplayer.WebPlayerScreen(
                    onBackClick = { host.back() },
                    // Silence Rustify's own engine so two sources never overlap — but not when
                    // web mode is on, where pause() is forwarded to the page and would stop the
                    // very thing this screen is showing.
                    onEnter = {
                        if (!audioPlayerService.isWebPlayerMode) audioPlayerService.pause()
                    }
                )
            }
            is Screen.CustomDownload -> {
                com.varuna.rustify.ui.screens.CustomDownloadScreen(
                    onBack = { host.back() }
                )
            }
            is Screen.NanoChat -> {
                com.varuna.rustify.ui.screens.NanoChatScreen(
                    onBack = { host.back() },
                    spotifyRepo = host.spotifyRepo,
                    nowPlaying = currentTrack,
                    queue = playerState.queue,
                    onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                    onEnqueueTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.enqueueAll(tracks) },
                    onControl = { action ->
                        when (action) {
                            com.varuna.rustify.dj.ChatAction.Pause -> audioPlayerService.pause()
                            com.varuna.rustify.dj.ChatAction.Resume -> audioPlayerService.play()
                            com.varuna.rustify.dj.ChatAction.Next -> audioPlayerService.skipToNext()
                            com.varuna.rustify.dj.ChatAction.Previous -> audioPlayerService.skipToPrevious()
                            else -> {}
                        }
                    }
                )
            }
            is Screen.AudioLab -> {
                com.varuna.rustify.ui.screens.AudioLabScreen(onBack = { host.back() })
            }
            is Screen.Dj -> {
                DjScreen(
                    spotifyRepo = host.spotifyRepo,
                    nowPlaying = currentTrack,
                    queue = playerState.queue,
                    onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                    onEnqueueTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.enqueueAll(tracks) },
                    onBack = { host.back() },
                    onOpenSettings = { host.navigate(Screen.Settings) }
                )
            }
            is Screen.Travel -> {
                TravelScreen(
                    spotifyRepo = host.spotifyRepo,
                    onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                    onBack = { host.back() },
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
                        currentTrack?.id?.let { id -> host.navigate(Screen.TrackDetail(id)) }
                    }
                )
            }
            is Screen.TravelWithDestination -> {
                TravelScreen(
                    spotifyRepo = host.spotifyRepo,
                    onPlayTracks = { tracks -> if (tracks.isNotEmpty()) audioPlayerService.loadPlaylist(tracks, 0) },
                    onBack = { host.back() },
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
                        currentTrack?.id?.let { id -> host.navigate(Screen.TrackDetail(id)) }
                    },
                    initialDestination = com.varuna.rustify.travel.TravelRouting.Geo(
                        lat = screen.lat, lon = screen.lon, label = screen.label ?: ""
                    )
                )
            }
            else -> Unit
        }
    }
}
