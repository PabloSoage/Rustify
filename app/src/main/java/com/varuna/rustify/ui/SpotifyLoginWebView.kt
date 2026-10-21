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

/**
 * The Spotify login page, and the capture of `sp_dc` from its cookies.
 *
 * ## Why this now says something when it fails
 *
 * Reported on a Mi Pad 6S Pro: tapping login showed **nothing** — no Spotify page, no error, no way
 * to tell whether the request had failed, been refused, or rendered blank. The web player's WebView
 * on the same account was fine, and that asymmetry is what makes the difference worth naming: that
 * one enables cookies explicitly, carries a [android.webkit.WebChromeClient] and reports what the
 * page says. This one did none of the three, so every possible failure looked identical — an empty
 * rectangle.
 *
 * The handlers below do not fix a cause; from here, none of them can. They turn a blank screen into
 * a sentence, which is the difference between a bug that can be worked on and one that can only be
 * reported again. The same detail goes to `adb logcat -s SpotifyLogin:W`.
 *
 * Two settings *were* simply missing, and either is a plausible cause on its own:
 *
 *  - **third-party cookies**, off by default since Lollipop. Login crosses `accounts.spotify.com`
 *    to `open.spotify.com`, and `sp_dc` is the whole reason this screen exists.
 *  - **multiple windows**, which the challenge step can open. Without support for it that tap does
 *    nothing whatsoever, and the page reads as frozen rather than as failed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginWebView(onLoginSuccess: (String) -> Unit, onCancel: () -> Unit) {
    var failure by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }

        failure?.let { reason ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3A1F1F))
                    .padding(12.dp)
            ) {
                Text(
                    "The Spotify login page did not load.",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(reason, color = Color(0xFFDDBBBB), fontSize = 12.sp)
                TextButton(onClick = {
                    failure = null
                    webView?.loadUrl(SPOTIFY_LOGIN_URL)
                }) { Text(stringResource(R.string.general_retry), color = Color(0xFF1DB954)) }
            }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    // Compose's AndroidView attaches its child with WRAP_CONTENT layout params and
                    // sizes it through the modifier instead. WebView does not go along with that:
                    // AwLayoutSizer reads the params, not the measure spec, and a WRAP_CONTENT
                    // height turns on `force_zero_layout_height` — Blink then lays the page out in a
                    // viewport zero pixels tall. `innerHeight` still reads right, so the damage is
                    // invisible until a page sizes itself off the viewport: Spotify's login screen
                    // roots at `position:absolute; inset:0`, which collapses to nothing but its own
                    // padding, and the screen goes blank with no error anywhere.
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // The challenge step can open a second window; without this that tap is inert
                    // and the page looks frozen rather than failed.
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    // `sp_dc` is the entire point of this screen, and the cross-site hop to
                    // open.spotify.com is where it is set. Third-party cookies are off by default.
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(
                            message: android.webkit.ConsoleMessage?
                        ): Boolean {
                            val m = message ?: return false
                            if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                android.util.Log.w(
                                    "SpotifyLogin",
                                    "page error: ${m.message()} (${m.sourceId()}:${m.lineNumber()})"
                                )
                            }
                            return false
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        private var loginAlreadyDone = false

                        private fun report(reason: String) {
                            android.util.Log.w("SpotifyLogin", reason)
                            failure = reason
                        }

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

                        // Main frame only: a blocked tracker inside the page is not a failed login,
                        // and reporting one would cry wolf on a page that loaded perfectly.
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame != true) return
                            report("${error?.description ?: "network error"} (${request.url})")
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (request?.isForMainFrame != true) return
                            report("Spotify answered HTTP ${errorResponse?.statusCode} for ${request.url}")
                        }

                        // Reported rather than cancelled silently: a TLS error here is usually a
                        // wrong device clock or a network that intercepts TLS, and both are things
                        // the user can act on once told. Never proceeded with — this page is about
                        // to carry a session cookie.
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            handler?.cancel()
                            report("TLS refused (${error?.primaryError}). Check the device date and time.")
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?
                        ): Boolean {
                            report("The system WebView crashed. Updating Android System WebView usually fixes it.")
                            return true // handled: do not take the app down with it
                        }
                    }

                    loadUrl(SPOTIFY_LOGIN_URL)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
