// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopAppShell.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.Screen
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.cancel
import com.varuna.rustify.ui.resources.desktop_screen_not_here
import com.varuna.rustify.ui.resources.desktop_settings_not_here
import com.varuna.rustify.ui.resources.desktop_sign_in_cookie
import com.varuna.rustify.ui.resources.desktop_sign_in_help
import com.varuna.rustify.ui.resources.desktop_sign_in_open_window
import com.varuna.rustify.ui.resources.desktop_sign_in_paste_instead
import com.varuna.rustify.ui.resources.desktop_sign_in_waiting
import com.varuna.rustify.ui.resources.desktop_sign_in_waiting_help
import com.varuna.rustify.ui.resources.desktop_sign_in_window_failed
import com.varuna.rustify.ui.resources.spotify_sign_in
import java.io.File
import org.jetbrains.compose.resources.stringResource

private val Background = Color(0xFF121212)
private val Accent = Color(0xFF1DB954)

/**
 * [AppShell] on the desktop. Signing in is the phone's: Spotify's login page, here in the system's
 * own webview in a window of its own (`rustify-login`, [LoginHelper]; docs serie-4.x/27, phase H),
 * and the `sp_dc` cookie it leaves. Pasting the cookie by hand stays, for when that window cannot
 * open and for whoever prefers it. The screens not moved yet say so, with a way back. [onLanguage]
 * is the window's: it puts the interface in the language Settings picked.
 */
class DesktopAppShell(private val onLanguage: (String) -> Unit = {}) : AppShell {
    @Composable
    override fun SignIn(onLogin: (String) -> Unit, onCancel: () -> Unit) {
        val helper = remember { LoginHelper.locate() }
        var inWindow by remember { mutableStateOf(helper != null) }
        // Why the window is not there, said above the pasted cookie rather than swallowed.
        var windowFailure by remember { mutableStateOf<String?>(null) }
        if (helper != null && inWindow) {
            WindowSignIn(
                helper,
                onLogin = onLogin,
                onCancel = onCancel,
                onPasteInstead = { failure ->
                    windowFailure = failure
                    inWindow = false
                },
            )
        } else {
            PasteSignIn(
                failure = windowFailure,
                onLogin = onLogin,
                onCancel = onCancel,
                onOpenWindow = if (helper == null) null else ({
                    windowFailure = null
                    inWindow = true
                }),
            )
        }
    }

    /**
     * The Spotify window is open and this one waits for it. Leaving this screen — Cancel, back, the
     * cookie instead — closes it ([LoginSession.stop]); closing it is a cancel here too.
     */
    @Composable
    private fun WindowSignIn(
        helper: File,
        onLogin: (String) -> Unit,
        onCancel: () -> Unit,
        onPasteInstead: (failure: String?) -> Unit,
    ) {
        val session = remember(helper) { LoginSession(helper) }
        // The answer can take minutes; by then the root may have recomposed with new callbacks.
        val loggedIn by rememberUpdatedState(onLogin)
        val cancelled by rememberUpdatedState(onCancel)
        val pasteInstead by rememberUpdatedState(onPasteInstead)
        DisposableEffect(session) { onDispose { session.stop() } }
        LaunchedEffect(session) {
            when (val outcome = session.await()) {
                is LoginOutcome.SignedIn -> loggedIn(outcome.spDc)
                LoginOutcome.Closed -> cancelled()
                is LoginOutcome.Failed -> pasteInstead(outcome.reason)
            }
        }
        Page(stringResource(Res.string.spotify_sign_in), onBack = onCancel) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.5.dp)
                Text(stringResource(Res.string.desktop_sign_in_waiting), color = Color.White, fontSize = 17.sp)
            }
            Text(stringResource(Res.string.desktop_sign_in_waiting_help), color = Color.LightGray)
            Button(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
            TextButton(onClick = { onPasteInstead(null) }) {
                Text(stringResource(Res.string.desktop_sign_in_paste_instead), color = Accent)
            }
        }
    }

    /** The `sp_dc` cookie pasted by hand: the way in before the window, and its fallback now. */
    @Composable
    private fun PasteSignIn(
        failure: String?,
        onLogin: (String) -> Unit,
        onCancel: () -> Unit,
        onOpenWindow: (() -> Unit)?,
    ) {
        var cookie by remember { mutableStateOf("") }
        Page(stringResource(Res.string.spotify_sign_in), onBack = onCancel) {
            failure?.let {
                Text(stringResource(Res.string.desktop_sign_in_window_failed, it), color = Color(0xFFE57373), fontSize = 13.sp)
            }
            Text(stringResource(Res.string.desktop_sign_in_help), color = Color.LightGray)
            OutlinedTextField(
                value = cookie,
                onValueChange = { cookie = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(Res.string.desktop_sign_in_cookie)) },
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                enabled = cookie.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                onClick = { onLogin(cookie) },
            ) { Text(stringResource(Res.string.spotify_sign_in)) }
            Button(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
            onOpenWindow?.let { open ->
                TextButton(onClick = open) {
                    Text(stringResource(Res.string.desktop_sign_in_open_window), color = Accent)
                }
            }
        }
    }

    // Nothing to clear: every Spotify window starts with an empty cookie jar of its own.
    override fun onLoginFailed() = Unit

    // The phone's check for an update on launch; the release page instead of an install.
    @Composable
    override fun StartupEffects(host: ShellHost) = DesktopStartupUpdateCheck(host)

    @Composable
    override fun SystemBars(landscape: Boolean) = Unit

    override suspend fun resolveMapsLink(url: String): Screen.TravelWithDestination? = null

    @Composable
    override fun PlatformScreen(screen: Screen, host: ShellHost) {
        if (screen is Screen.CustomDownload) {
            com.varuna.rustify.ui.screens.CustomDownloadScreen(LocalAppServices.current.customDownloads!!, onBack = host.back)
            return
        }
        Page("", onBack = host.back) {
            Text(stringResource(Res.string.desktop_screen_not_here), color = Color.LightGray)
        }
    }

    // What the phone has in General beside the shared sections (yt-dlp, Drive, storage) comes with
    // those features; the DJ's is shared, and the window's is the desktop's own. Advanced has the
    // phone's update check and log viewer. The other categories say they are not here yet.
    @Composable
    override fun SettingsSections(slot: SettingsSlot, host: ShellHost) {
        if (slot == SettingsSlot.GENERAL_DJ) {
            com.varuna.rustify.ui.screens.DjSettingsSection()
            return
        }
        if (slot == SettingsSlot.GENERAL) {
            DesktopWindowSection()
            com.varuna.rustify.ui.screens.CacheStorageSection()
            com.varuna.rustify.ui.screens.AdvancedDataSection()
            return
        }
        if (slot == SettingsSlot.AUDIO) return
        if (slot == SettingsSlot.ADVANCED) {
            DesktopUpdatesSection()
            com.varuna.rustify.ui.screens.DiagnosticsSection(onViewLogs = { host.navigate(Screen.LogViewer) })
            com.varuna.rustify.ui.screens.SpotifyHashInspectorSection()
        }
        // The shared sections the phone has further down Audio, in its order: local music, the
        // audio sources' order and the add-ons, how the queue moves and how it sounds, the data and
        // scrobbling sections, then Invidious, Deezer and Subsonic.
        if (slot == SettingsSlot.AUDIO_SOURCES) {
            com.varuna.rustify.ui.screens.LocalMusicSection()
            com.varuna.rustify.ui.screens.AudioSourcesOrderSection()
            com.varuna.rustify.ui.screens.AutoplayRadioSection()
            com.varuna.rustify.ui.screens.BlocklistSection()
            com.varuna.rustify.ui.screens.NormalizeVolumeSection()
            com.varuna.rustify.ui.screens.EqualizerSection()
            com.varuna.rustify.ui.screens.AutoEqSection()
            com.varuna.rustify.ui.screens.StereoToolsSection()
            com.varuna.rustify.ui.screens.FadeBetweenSongsSection()
            com.varuna.rustify.ui.screens.PreviousButtonSection()
            com.varuna.rustify.ui.screens.ShuffleSection()
            com.varuna.rustify.ui.screens.PauseFadeSection()
            com.varuna.rustify.ui.screens.SkipSilenceSection()
            com.varuna.rustify.ui.screens.DataExportSection()
            com.varuna.rustify.ui.screens.ListenBrainzSection()
            com.varuna.rustify.ui.screens.LastFmSection()
            com.varuna.rustify.ui.screens.InvidiousBackendSection()
            com.varuna.rustify.ui.screens.DeezerBackendSection()
            com.varuna.rustify.ui.screens.SubsonicBackendSection()
        }
        if (slot == SettingsSlot.DOWNLOADS) {
            com.varuna.rustify.ui.screens.DownloadFolderSection { com.varuna.rustify.bridge.DesktopMp3.Switch() }
            com.varuna.rustify.ui.screens.CustomDownloadSection(LocalAppServices.current.customDownloads!!) { host.navigate(Screen.CustomDownload) }
            return
        }
        Text(
            stringResource(Res.string.desktop_settings_not_here),
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }

    @Composable
    override fun EasterEggPage(url: String, onClose: () -> Unit) {
        val platform = LocalAppPlatform.current
        LaunchedEffect(url) {
            platform.openUrl(url)
            onClose()
        }
    }

    // The phone's refresh button in Settings' top bar: check for an update now.
    @Composable
    override fun SettingsActions(host: ShellHost) = DesktopUpdateAction()

    override fun applyLanguage(code: String) = onLanguage(code)

    @Composable
    private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Background).padding(16.dp)) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
                if (title.isNotEmpty()) Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }
}
