package com.varuna.rustify.webplayer

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.varuna.rustify.R
import com.varuna.rustify.bridge.NativeEngine
import java.io.ByteArrayInputStream

private const val SPOTIFY_WEB = "https://open.spotify.com/"

/** Empty 200 served in place of a blocked request; an error would make pages retry or break. */
private fun blockedResponse() =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/** Maps Android's request hints onto adblock-rust's resource-type vocabulary. */
private fun resourceTypeOf(request: WebResourceRequest): String {
    if (request.isForMainFrame) return "document"
    val accept = request.requestHeaders?.get("Accept").orEmpty()
    val path = request.url.path.orEmpty().lowercase()
    return when {
        accept.contains("text/css") || path.endsWith(".css") -> "stylesheet"
        accept.contains("image/") || Regex("\\.(png|jpe?g|gif|webp|svg|ico)$").containsMatchIn(path) -> "image"
        path.endsWith(".js") || accept.contains("javascript") -> "script"
        Regex("\\.(mp3|mp4|m4a|ogg|webm|aac)$").containsMatchIn(path) -> "media"
        request.requestHeaders?.containsKey("X-Requested-With") == true -> "xmlhttprequest"
        else -> "xmlhttprequest"
    }
}

/**
 * Spotify Web Player embedded in a WebView.
 *
 * Playback happens inside the WebView itself — nothing is captured or re-streamed, and the app's own
 * player is paused while this screen is open so the two never fight over audio.
 *
 * Two things make this work where a plain WebView fails:
 *  - **DRM.** Spotify's web player uses Widevine via EME. A WebView only grants that after the host
 *    app answers `onPermissionRequest` with `RESOURCE_PROTECTED_MEDIA_ID`; without it playback dies
 *    with "No supported keysystem was found".
 *  - **Filtering.** WebView has no extension support, so uBlock Origin can't be installed. Requests
 *    are matched instead against uBO's own filter lists via the Rust engine (see [AdblockFilters]).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayerScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onEnter: () -> Unit = {}
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var filtersReady by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Pause Rustify's own playback: two audio sources at once is never what the user wants.
    LaunchedEffect(Unit) { onEnter() }

    // Compile the filter lists before the page loads so the very first requests are already covered.
    LaunchedEffect(Unit) {
        filtersReady = runCatching { AdblockFilters.ensureLoaded(context) }.getOrDefault(false)
        webViewRef?.let { if (it.url == null) it.loadUrl(SPOTIFY_WEB) }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching { wv.evaluateJavascript("document.querySelectorAll('video,audio').forEach(m=>m.pause());", null) }
                wv.stopLoading()
                wv.destroy()
            }
            AdblockFilters.release()
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onBackClick()
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF121212))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back), tint = Color.White)
            }
            Text(
                text = stringResource(R.string.web_player_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.web_player_reload), tint = Color.White)
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1DB954),
                trackColor = Color(0xFF333333)
            )
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            // The web player needs a desktop-class UA to expose full controls.
                            userAgentString = settings.userAgentString
                                .replace("; wv", "")
                                .replace("Version/4.0 ", "")
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            // Grants Widevine/EME. Without this the player cannot decrypt anything.
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                val resources = request?.resources ?: return
                                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
                                    request.grant(resources)
                                    return
                                }
                                super.onPermissionRequest(request)
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val req = request ?: return null
                                if (req.isForMainFrame) return null
                                val url = req.url?.toString() ?: return null
                                if (!url.startsWith("http")) return null
                                val blocked = runCatching {
                                    NativeEngine.adblockMatchesNative(url, SPOTIFY_WEB, resourceTypeOf(req))
                                }.getOrDefault(false)
                                return if (blocked) blockedResponse() else null
                            }
                        }

                        webViewRef = this
                        // Only load once the filters are compiled (the LaunchedEffect above triggers
                        // the load when it finishes first).
                        if (filtersReady) loadUrl(SPOTIFY_WEB)
                    }
                }
            )
        }
    }
}
