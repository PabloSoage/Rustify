package com.varuna.rustify.webplayer

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Drives Spotify's web player inside a WebView so Rustify's own controls can operate it.
 *
 * This is the "experimental backend" mode: audio is produced by the page (nothing is captured or
 * re-streamed) while the app supplies the UI. Two things make it work:
 *
 *  - **Reading state.** Rather than scraping the DOM for labels, it reads what the page already
 *    publishes: `navigator.mediaSession.metadata` (title / artist / artwork) and the media element's
 *    `currentTime` / `duration` / `paused`. Those are stable APIs, unlike Spotify's markup.
 *  - **Sending commands.** The page's own transport buttons are clicked by `data-testid`, and seeking
 *    writes straight to the media element. Button ids are the one part that Spotify can break, so each
 *    lookup falls back through several selectors.
 *
 * The WebView is held here, created with the application context, so leaving the screen does not stop
 * playback — the screen only attaches and detaches the same instance.
 */
object WebPlayerController {

    const val SPOTIFY_WEB = "https://open.spotify.com/"

    data class WebState(
        val available: Boolean = false,
        val isPlaying: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val artworkUrl: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    )

    private val _state = MutableStateFlow(WebState())
    val state: StateFlow<WebState> = _state

    /**
     * Invoked on the main thread whenever the polled page state actually changes. The MediaSession
     * facade uses it to refresh the notification; nothing else observes it.
     */
    @Volatile var onStateChanged: (() -> Unit)? = null

    @Volatile private var webView: WebView? = null

    /** True once a WebView exists, i.e. the web player can accept commands. */
    val isReady: Boolean get() = webView != null

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    /**
     * Returns the shared WebView, creating it on first use. Uses the application context on purpose:
     * the instance outlives the screen so audio keeps playing after navigating away.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(context: Context): WebView {
        webView?.let { return it }
        val wv = WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // The web player only exposes its full transport UI to a desktop-class UA.
                userAgentString = settings.userAgentString
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                // Widevine/EME. Without this the player dies with "No supported keysystem was found".
                override fun onPermissionRequest(request: PermissionRequest?) {
                    val resources = request?.resources ?: return
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
                        request.grant(resources)
                        return
                    }
                    super.onPermissionRequest(request)
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
        }
        webView = wv
        return wv
    }

    /** Detaches the WebView from its parent without destroying it (playback continues). */
    fun detachFromParent() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
    }

    /** Stops playback and tears the WebView down. Call when leaving web-player mode for good. */
    fun destroy() {
        val wv = webView ?: return
        runCatching { wv.evaluateJavascript(JS_PAUSE, null) }
        detachFromParent()
        runCatching { wv.stopLoading() }
        runCatching { wv.destroy() }
        webView = null
        _state.value = WebState()
        // Nothing left to filter; free the compiled rule set.
        AdblockFilters.release()
    }

    fun loadHomeIfNeeded() {
        val wv = webView ?: return
        if (wv.url == null) wv.loadUrl(SPOTIFY_WEB)
    }

    // -------------------------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------------------------

    private fun run(js: String) {
        val wv = webView ?: return
        wv.post { runCatching { wv.evaluateJavascript(js, null) } }
    }

    fun play() = run(JS_PLAY)
    fun pause() = run(JS_PAUSE)
    fun togglePlayPause() = run(click("control-button-playpause"))
    fun next() = run(click("control-button-skip-forward"))
    fun previous() = run(click("control-button-skip-back"))

    /** Seeks the underlying media element directly — far more reliable than dragging its slider. */
    fun seekTo(positionMs: Long) = run(
        "(function(){var m=document.querySelector('video,audio'); if(m){m.currentTime=${positionMs / 1000.0};}})();"
    )

    /**
     * Opens a Spotify entity in the page and starts it. [uri] is a plain https Spotify URL; the page
     * is a SPA, so this navigates and then autoplays once the transport button appears.
     */
    fun playSpotifyUrl(url: String) {
        val wv = webView ?: return
        wv.post {
            runCatching { wv.loadUrl(url) }
            // The SPA needs a moment to render its transport before the click lands.
            wv.postDelayed({ runCatching { wv.evaluateJavascript(JS_AUTOPLAY, null) } }, 2500)
        }
    }

    // -------------------------------------------------------------------------------------------
    // State polling
    // -------------------------------------------------------------------------------------------

    /**
     * Reads the page's current playback state. Called on a timer by the player service; results feed
     * the app's own UI. Silently keeps the last known state if the page isn't ready.
     */
    fun refreshState() {
        val wv = webView ?: return
        wv.post {
            runCatching {
                wv.evaluateJavascript(JS_READ_STATE) { raw ->
                    val json = unquote(raw) ?: return@evaluateJavascript
                    runCatching {
                        val o = JSONObject(json)
                        val previous = _state.value
                        _state.value = WebState(
                            available = o.optBoolean("available", false),
                            isPlaying = o.optBoolean("playing", false),
                            title = o.optString("title"),
                            artist = o.optString("artist"),
                            artworkUrl = o.optString("artwork"),
                            positionMs = (o.optDouble("position", 0.0) * 1000).toLong(),
                            durationMs = (o.optDouble("duration", 0.0) * 1000).toLong()
                        )
                        // Only nudge the session when something it displays actually moved; the
                        // position changes on every poll, so compare the rest separately.
                        val now = _state.value
                        val meaningful = previous.isPlaying != now.isPlaying ||
                            previous.title != now.title ||
                            previous.available != now.available ||
                            kotlin.math.abs(previous.positionMs - now.positionMs) > 900
                        if (meaningful) onStateChanged?.invoke()
                    }
                }
            }
        }
    }

    /** `evaluateJavascript` hands back a JSON-encoded string; unwrap it to the raw payload. */
    private fun unquote(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            if (raw.startsWith("\"")) JSONObject("{\"v\":$raw}").getString("v") else raw
        }.getOrNull()
    }

    private fun click(testId: String) =
        "(function(){var b=document.querySelector('[data-testid=\"$testId\"]'); if(b){b.click();}})();"

    private const val JS_PLAY =
        "(function(){var m=document.querySelector('video,audio'); if(m&&m.paused){m.play().catch(function(){});}" +
        "else{var b=document.querySelector('[data-testid=\"control-button-playpause\"]'); if(b){b.click();}}})();"

    private const val JS_PAUSE =
        "(function(){var m=document.querySelector('video,audio'); if(m&&!m.paused){m.pause();}})();"

    private const val JS_AUTOPLAY =
        "(function(){var m=document.querySelector('video,audio');" +
        "if(m&&m.paused){var b=document.querySelector('[data-testid=\"control-button-playpause\"]'); if(b){b.click();}}})();"

    /**
     * Prefers `navigator.mediaSession.metadata`, which the page maintains itself, over reading its
     * markup. Falls back to the media element for timing.
     */
    private const val JS_READ_STATE = """
        (function(){
          try {
            var m = document.querySelector('video,audio');
            var md = (navigator.mediaSession && navigator.mediaSession.metadata) || null;
            var art = '';
            if (md && md.artwork && md.artwork.length) { art = md.artwork[md.artwork.length-1].src || ''; }
            return JSON.stringify({
              available: !!m || !!md,
              playing: !!m && !m.paused,
              title: md ? (md.title || '') : '',
              artist: md ? (md.artist || '') : '',
              artwork: art,
              position: m ? (m.currentTime || 0) : 0,
              duration: (m && isFinite(m.duration)) ? m.duration : 0
            });
          } catch (e) { return JSON.stringify({available:false}); }
        })();
    """

    // Shared with WebPlayerScreen.
    internal fun blockedResponse() =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    internal fun resourceTypeOf(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"
        val accept = request.requestHeaders?.get("Accept").orEmpty()
        val path = request.url.path.orEmpty().lowercase()
        return when {
            accept.contains("text/css") || path.endsWith(".css") -> "stylesheet"
            accept.contains("image/") ||
                Regex("\\.(png|jpe?g|gif|webp|svg|ico)$").containsMatchIn(path) -> "image"
            path.endsWith(".js") || accept.contains("javascript") -> "script"
            Regex("\\.(mp3|mp4|m4a|ogg|webm|aac)$").containsMatchIn(path) -> "media"
            else -> "xmlhttprequest"
        }
    }
}
