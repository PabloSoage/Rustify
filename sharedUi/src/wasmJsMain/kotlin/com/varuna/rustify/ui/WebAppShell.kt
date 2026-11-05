// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/WebAppShell.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.varuna.rustify.Screen
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.cancel
import com.varuna.rustify.ui.resources.desktop_sign_in_cookie
import com.varuna.rustify.ui.resources.desktop_sign_in_help
import com.varuna.rustify.ui.resources.feature_not_on_platform
import com.varuna.rustify.ui.resources.web_downloads_no_folder
import com.varuna.rustify.ui.resources.web_downloads_on_server
import com.varuna.rustify.ui.resources.spotify_sign_in
import com.varuna.rustify.ui.resources.web_sign_in_check_again
import com.varuna.rustify.ui.resources.web_sign_in_from_app
import com.varuna.rustify.ui.resources.web_sign_in_or_paste
import com.varuna.rustify.ui.resources.web_sign_in_other_ways
import com.varuna.rustify.ui.resources.web_sign_in_waiting
import com.varuna.rustify.ui.resources.web_sign_in_window
import com.varuna.rustify.ui.resources.web_sign_in_window_failed
import com.varuna.rustify.ui.resources.web_sign_in_window_hint
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private fun pageOrigin(): String = js("window.location.origin")

/** Whether the server can open the Spotify window (`GET /api/spotify-window`). */
private fun windowAvailable(): Promise<JsBoolean> = js(
    """fetch('api/spotify-window').then(function(r) { return r.ok ? r.json() : {}; })
        .then(function(a) { return a.available === true; }, function() { return false; })"""
)

/**
 * The server opens Spotify's sign-in on its screen and answers the cookie (`POST /api/spotify-window`):
 * the cookie, `""` when the window was closed, `"!"` when it could not open.
 */
private fun openWindow(lang: String): Promise<JsString> = js(
    """fetch('api/spotify-window?lang=' + encodeURIComponent(lang), { method: 'POST' })
        .then(function(r) {
            if (r.status === 200) { return r.json().then(function(a) { return a.sp_dc || '!'; }); }
            return r.status === 204 ? '' : '!';
        }, function() { return '!'; })"""
)

private fun reloadPage() {
    js("window.location.reload()")
}

private val Background = Color(0xFF121212)
private val Accent = Color(0xFF1DB954)

/**
 * [AppShell] in a browser. The Spotify cookie cannot be read from a page of another site (docs
 * serie-4.x/28 §5 bis), so signing in is the server's Spotify window when it has a screen, else the
 * session from the app (ServerLink) or the pasted cookie; the screens no browser can have say so.
 */
class WebAppShell(private val onLanguage: (String) -> Unit) : AppShell {
    @Composable
    override fun SignIn(onLogin: (String) -> Unit, onCancel: () -> Unit) {
        var cookie by remember { mutableStateOf("") }
        // null while asking the server; then whether it can open the Spotify window.
        var window by remember { mutableStateOf<Boolean?>(null) }
        var waiting by remember { mutableStateOf(false) }
        var windowFailed by remember { mutableStateOf(false) }
        var otherWays by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            window = runCatching { windowAvailable().await<JsBoolean>().toBoolean() }.getOrDefault(false)
        }
        Column(
            Modifier.fillMaxSize().background(Background).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The way the phone and the desktop do it: Spotify's own page, in a window the server opens
            // (rustify-login), since no page can frame Spotify's login or read its cookie.
            if (window == true && !windowFailed) {
                Text(stringResource(Res.string.web_sign_in_window_hint), color = Color.LightGray)
                Button(
                    enabled = !waiting,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    onClick = {
                        waiting = true
                        scope.launch {
                            val answer = runCatching { openWindow(WebLocale.tag).await<JsString>().toString() }.getOrDefault("!")
                            waiting = false
                            when (answer) {
                                "" -> Unit
                                "!" -> windowFailed = true
                                else -> onLogin(answer)
                            }
                        }
                    },
                ) { Text(stringResource(Res.string.web_sign_in_window)) }
                if (waiting) Text(stringResource(Res.string.web_sign_in_waiting), color = Color.Gray)
                if (!otherWays) {
                    TextButton(onClick = { otherWays = true }) {
                        Text(stringResource(Res.string.web_sign_in_other_ways), color = Accent)
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel), color = Accent) }
                    return@Column
                }
            }
            if (windowFailed) Text(stringResource(Res.string.web_sign_in_window_failed), color = Color(0xFFFFB74D))
            // Without a screen on the server: the phone or desktop app hands this server its session
            // (ServerLink), and the page only has to look again.
            Text(stringResource(Res.string.web_sign_in_from_app, pageOrigin()), color = Color.White)
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                onClick = { reloadPage() },
            ) { Text(stringResource(Res.string.web_sign_in_check_again)) }
            Text(stringResource(Res.string.web_sign_in_or_paste), color = Color.Gray)
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
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel), color = Accent) }
        }
    }

    override fun onLoginFailed() = Unit

    @Composable
    override fun StartupEffects(host: ShellHost) = Unit

    @Composable
    override fun SystemBars(landscape: Boolean) = Unit

    override suspend fun resolveMapsLink(url: String): Screen.TravelWithDestination? = null

    @Composable
    override fun PlatformScreen(screen: Screen, host: ShellHost) {
        Column(
            Modifier.fillMaxSize().background(Background).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.feature_not_on_platform), color = Color.LightGray)
            TextButton(onClick = host.back) { Text(stringResource(Res.string.cancel), color = Accent) }
        }
    }

    // What a page can act on: effects run in the player, which here is the browser's.
    @Composable
    override fun SettingsSections(slot: SettingsSlot, host: ShellHost) {
        when (slot) {
            SettingsSlot.GENERAL -> {
                com.varuna.rustify.ui.screens.CacheStorageSection()
                com.varuna.rustify.ui.screens.AdvancedDataSection()
                com.varuna.rustify.ui.screens.DriveSyncSection(host.spotifyRepo, host.ytmRepo)
            }
            SettingsSlot.AUDIO -> Unit
            SettingsSlot.GENERAL_DJ -> com.varuna.rustify.ui.screens.DjSettingsSection()
            SettingsSlot.AUDIO_SOURCES -> {
                com.varuna.rustify.ui.screens.AudioSourcesOrderSection()
                com.varuna.rustify.ui.screens.AutoplayRadioSection()
                com.varuna.rustify.ui.screens.BlocklistSection()
                // What Web Audio can do in the page (WebAudioGraph); not normalisation, ReplayGain
                // or skipping silence, which need the track measured or read first.
                com.varuna.rustify.ui.screens.EqualizerSection()
                com.varuna.rustify.ui.screens.AutoEqSection()
                com.varuna.rustify.ui.screens.StereoToolsSection()
                com.varuna.rustify.ui.screens.FadeBetweenSongsSection()
                com.varuna.rustify.ui.screens.PreviousButtonSection()
                com.varuna.rustify.ui.screens.ShuffleSection()
                com.varuna.rustify.ui.screens.PauseFadeSection()
                com.varuna.rustify.ui.screens.DataExportSection()
                com.varuna.rustify.ui.screens.ListenBrainzSection()
                com.varuna.rustify.ui.screens.LastFmSection()
                com.varuna.rustify.ui.screens.InvidiousBackendSection()
                com.varuna.rustify.ui.screens.DeezerBackendSection()
                com.varuna.rustify.ui.screens.SubsonicBackendSection()
            }
            SettingsSlot.DOWNLOADS -> Text(
                stringResource(
                    if (LocalAppServices.current.can(AppServices.Feature.DOWNLOADS)) Res.string.web_downloads_on_server
                    else Res.string.web_downloads_no_folder
                ),
                color = Color.Gray
            )
            SettingsSlot.INTEGRATIONS ->
                Text(stringResource(Res.string.feature_not_on_platform), color = Color.Gray)
            SettingsSlot.ADVANCED -> {
                com.varuna.rustify.ui.screens.DiagnosticsSection(onViewLogs = { host.navigate(Screen.LogViewer) })
                com.varuna.rustify.ui.screens.SpotifyHashInspectorSection()
            }
        }
    }

    @Composable
    override fun EasterEggPage(url: String, onClose: () -> Unit) {
        val platform = LocalAppPlatform.current
        LaunchedEffect(url) {
            platform.openUrl(url)
            onClose()
        }
    }

    @Composable
    override fun SettingsActions(host: ShellHost) = Unit

    override fun applyLanguage(code: String) = onLanguage(code)
}
