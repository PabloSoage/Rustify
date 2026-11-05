// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/IosAppShell.kt
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.varuna.rustify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.varuna.rustify.Screen
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.cancel
import com.varuna.rustify.ui.resources.desktop_sign_in_cookie
import com.varuna.rustify.ui.resources.desktop_sign_in_help
import com.varuna.rustify.ui.resources.desktop_sign_in_paste_instead
import com.varuna.rustify.ui.resources.feature_not_on_platform
import com.varuna.rustify.ui.resources.spotify_sign_in
import kotlinx.cinterop.cValue
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore

private val Background = Color(0xFF121212)
private val Accent = Color(0xFF1DB954)

/**
 * [AppShell] on iOS. Signing in is the phone's: Spotify's login page, here in a WKWebView over a
 * cookie jar of its own, and the `sp_dc` cookie it leaves. Pasting the cookie stays, for whoever
 * prefers it. The screens not moved yet say so, with a way back.
 */
class IosAppShell(private val onLanguage: (String) -> Unit) : AppShell {
    @Composable
    override fun SignIn(onLogin: (String) -> Unit, onCancel: () -> Unit) {
        var pasting by remember { mutableStateOf(false) }
        if (pasting) {
            PasteSignIn(onLogin, onCancel)
        } else {
            WebSignIn(onLogin, onCancel, onPaste = { pasting = true })
        }
    }

    @Composable
    private fun WebSignIn(onLogin: (String) -> Unit, onCancel: () -> Unit, onPaste: () -> Unit) {
        val loggedIn by rememberUpdatedState(onLogin)
        // A jar of its own that dies with the view: every sign-in starts clean, and the cookie goes
        // nowhere but the Keychain.
        val web = remember {
            val config = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore() }
            WKWebView(frame = cValue { CGRectZero }, configuration = config).apply {
                val url = "https://accounts.spotify.com/${IosLocale.languageCode}/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
                loadRequest(NSURLRequest(uRL = NSURL(string = url)))
            }
        }
        // The cookie store has no "changed" a Kotlin caller can rely on; this looks every half second
        // while the page is up, and the answer is one cookie.
        LaunchedEffect(web) {
            var done = false
            while (!done) {
                web.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
                    val spDc = cookies?.mapNotNull { it as? NSHTTPCookie }?.firstOrNull { it.name == "sp_dc" }?.value
                    if (!done && !spDc.isNullOrBlank()) {
                        done = true
                        loggedIn(spDc)
                    }
                }
                delay(500)
            }
        }
        DisposableEffect(web) { onDispose { web.stopLoading() } }
        Column(Modifier.fillMaxSize().background(Background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel), color = Accent) }
                TextButton(onClick = onPaste) { Text(stringResource(Res.string.desktop_sign_in_paste_instead), color = Accent) }
            }
            UIKitView(factory = { web }, modifier = Modifier.fillMaxSize())
        }
    }

    /** The `sp_dc` cookie pasted by hand. */
    @Composable
    private fun PasteSignIn(onLogin: (String) -> Unit, onCancel: () -> Unit) {
        var cookie by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxSize().background(Background).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

    // Each sign-in's jar dies with its view: nothing to clear.
    override fun onLoginFailed() = Unit

    @Composable
    override fun StartupEffects(host: ShellHost) = com.varuna.rustify.ui.screens.DriveAutoSync(host.spotifyRepo, host.ytmRepo)

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

    // The sections iOS has, in the phone's order; what is not here is said at the end of the category.
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
            SettingsSlot.DOWNLOADS -> com.varuna.rustify.ui.screens.DownloadFolderSection()
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
