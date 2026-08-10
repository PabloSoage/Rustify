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
import androidx.core.content.edit
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

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

    /** Bumped on every navigation so in-flight state reads from the outgoing page are discarded. */
    @Volatile private var stateGeneration = 0

    /**
     * Invoked on the main thread whenever the polled page state actually changes. The MediaSession
     * facade uses it to refresh the notification; nothing else observes it.
     */
    @Volatile var onStateChanged: (() -> Unit)? = null

    @Volatile private var webView: WebView? = null

    /**
     * Every call into the WebView goes through here, and **never** through `WebView.post`.
     *
     * A `View` that is not attached to a window has no handler of its own, so `View.post` does not
     * run the action — it parks it in a queue that is only drained when the view is next attached:
     *
     * ```java
     * public boolean post(Runnable action) {
     *   final AttachInfo attachInfo = mAttachInfo;
     *   if (attachInfo != null) return attachInfo.mHandler.post(action);
     *   getRunQueue().post(action);   // deferred until attach
     * }
     * ```
     *
     * This WebView is deliberately detached whenever its screen closes, so that audio survives
     * navigating away — which meant every command sent while the screen was closed (play, pause,
     * seek, state polling, opening a track, the Settings self-test) was silently queued and never
     * executed. Posting to the main looper instead runs regardless of attachment, and the main
     * thread is where the WebView was created, which is the thread it actually requires.
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** True once a WebView exists, i.e. the web player can accept commands. */
    val isReady: Boolean get() = webView != null

    // -------------------------------------------------------------------------------------------
    // Desktop vs mobile page
    // -------------------------------------------------------------------------------------------

    private const val PREFS = "rustify_settings"
    private const val KEY_LAYOUT_WIDTH = "web_player_layout_width"

    /**
     * Layout widths the page can be laid out at, in CSS pixels, cycled from the screen's top bar —
     * the equivalent of Chrome DevTools' device emulation. `0` means "don't pretend": mobile user
     * agent and the phone's own width, which gets Spotify's mobile page (no player, but browsable).
     *
     * A narrower desktop width is the usable default: the player is responsive, so at 1024 it still
     * shows everything while being scaled down far less than at 1280.
     */
    val LAYOUT_WIDTHS = listOf(1024, 1280, 800, 0)

    private const val DEFAULT_LAYOUT_WIDTH = 1024

    @Volatile private var cachedLayoutWidth = DEFAULT_LAYOUT_WIDTH

    /** The WebView's own user agent, kept so the Chrome version survives our overrides. */
    @Volatile private var systemUserAgent: String? = null

    /** Current layout width, read from prefs and cached. `0` = mobile page. */
    fun layoutWidth(context: Context): Int {
        cachedLayoutWidth = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAYOUT_WIDTH, DEFAULT_LAYOUT_WIDTH)
        return cachedLayoutWidth
    }

    /**
     * Moves to the next width and applies it, returning the new value. The user agent is read when a
     * request is made and the page branches on it at load time, so switching to or from the mobile
     * page reloads; a pure width change only needs the viewport re-injected.
     */
    fun cycleLayoutWidth(context: Context): Int {
        val current = layoutWidth(context)
        val next = LAYOUT_WIDTHS[(LAYOUT_WIDTHS.indexOf(current).coerceAtLeast(0) + 1) % LAYOUT_WIDTHS.size]
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putInt(KEY_LAYOUT_WIDTH, next) }
        cachedLayoutWidth = next

        val wv = webView ?: return next
        val uaChanged = (current == 0) != (next == 0)
        mainHandler.post {
            runCatching {
                if (uaChanged) {
                    wv.settings.userAgentString =
                        userAgentFor(next != 0, systemUserAgent ?: wv.settings.userAgentString)
                    wv.reload()
                } else {
                    wv.evaluateJavascript(viewportJs(next), null)
                }
            }
        }
        return next
    }

    /**
     * Spotify serves a cut-down page to phone user agents: the library turns into a "get the app"
     * pitch instead of a browsable library, which is what made the embed useless on a phone. Driving
     * it with a desktop UA gets the real player. The Chrome major version is taken from the system
     * WebView's own UA so it never goes stale as the device updates.
     */
    private fun userAgentFor(desktop: Boolean, systemUa: String): String {
        if (!desktop) {
            // Plain Chrome-for-Android: drop the markers that identify an embedded WebView.
            return systemUa.replace("; wv", "").replace("Version/4.0 ", "")
        }
        val chromeMajor = Regex("Chrome/(\\d+)").find(systemUa)?.groupValues?.get(1) ?: "124"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36"
    }

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
        val width = layoutWidth(context)
        val wv = WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                val systemUa = userAgentString
                systemUserAgent = systemUa
                userAgentString = userAgentFor(width != 0, systemUa)
                // Honour the viewport meta that [viewportJs] writes; without this the desktop markup
                // is laid out in a phone-width viewport and its columns collapse into vertical
                // strips of text. The fit-to-screen zoom comes from the `initial-scale` in that meta
                // rather than from loadWithOverviewMode, which only applies at load time. Zoom stays
                // available for whatever ends up too small.
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
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
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyPageTweaks(view)
                }

                // The player is a single-page app: after the first load it swaps the DOM without
                // ever finishing another page, so the tweaks have to be re-applied here too.
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    applyPageTweaks(view)
                }

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

    /**
     * Loads the player unless a real page is already open. `about:blank` counts as "no page": a fresh
     * WebView sits on it with a non-null url, so testing for null alone left the blank document in
     * place and everything that needs a secure context failed against it.
     */
    fun loadHomeIfNeeded() {
        val wv = webView ?: return
        val url = wv.url
        if (url.isNullOrBlank() || url == "about:blank") {
            mainHandler.post { runCatching { wv.loadUrl(SPOTIFY_WEB) } }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------------------------

    private fun run(js: String) {
        val wv = webView ?: return
        mainHandler.post { runCatching { wv.evaluateJavascript(js, null) } }
    }

    fun play() = run(JS_PLAY)
    fun pause() = run(JS_PAUSE)

    /**
     * Presses play only if nothing is playing yet. Used while waiting for a freshly opened track to
     * start: the SPA renders its transport asynchronously, so the first click can land before the
     * button exists. Safe to repeat — it is a no-op once the media element is running.
     */
    fun nudgePlay() = run(JS_AUTOPLAY)

    fun togglePlayPause() = run(click("control-button-playpause"))
    // No next/previous here on purpose: opening a track URL leaves Spotify's own context holding
    // that single song, so its skip buttons have nowhere to go. Queue navigation belongs to
    // AudioPlayerService, which then opens the next track in the page with playSpotifyUrl.

    /** Seeks the underlying media element directly — far more reliable than dragging its slider. */
    fun seekTo(positionMs: Long) = run(
        "(function(){var m=document.querySelector('video,audio'); if(m){m.currentTime=${positionMs / 1000.0};}})();"
    )

    /**
     * Opens a Spotify entity in the page and starts it. [url] is a plain https Spotify URL; the page
     * is a SPA, so this navigates and then autoplays once the transport button appears.
     */
    fun playSpotifyUrl(url: String) {
        val wv = webView ?: return
        // Clear the mirrored state first. It still describes the *previous* track, and until the new
        // page loads it would read as "already playing" — which is exactly what awaitPlaybackStart
        // is looking for, so the watchdog would declare success before anything had happened.
        stateGeneration++
        _state.value = WebState()
        mainHandler.post {
            runCatching { wv.loadUrl(url) }
            // The SPA needs a moment to render its transport before the click lands.
            mainHandler.postDelayed(
                { runCatching { wv.evaluateJavascript(JS_AUTOPLAY, null) } }, 2500
            )
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
        // `evaluateJavascript` answers asynchronously, so a reply issued before a navigation can land
        // after it and resurrect the previous track's state. Stamp each read and drop stale replies.
        val generation = stateGeneration
        mainHandler.post {
            runCatching {
                wv.evaluateJavascript(JS_READ_STATE) { raw ->
                    if (generation != stateGeneration) return@evaluateJavascript
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

    /**
     * Runs [js] and returns its result, or null if the page never answered within [timeoutMs] (no
     * WebView, navigation in flight, script threw). Suspending counterpart of [run], used by the
     * playback watchdog and by [WebPlayerDiagnostics].
     */
    suspend fun eval(js: String, timeoutMs: Long = 5_000): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<String?> { cont ->
            val wv = webView
            if (wv == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            mainHandler.post {
                runCatching {
                    wv.evaluateJavascript(js) { raw ->
                        if (cont.isActive) cont.resume(unquote(raw))
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /**
     * Waits until a **real, secure** document has been parsed.
     *
     * `loadUrl` is asynchronous, so evaluating JavaScript straight after asking for a page runs
     * against no document and comes back `null` — which reads as "JavaScript is broken" when it only
     * means "too early". Anything that inspects the page has to go through here first.
     *
     * Readiness alone is not enough, and getting that wrong was a real bug: `about:blank` reports
     * `readyState === "complete"` immediately, so waiting only for that succeeded against the empty
     * document the WebView starts on. Scripts run there, so a self-test would tick "JavaScript works"
     * and then fail everything that needs the page — `about:blank` is **not a secure context**, so
     * `requestMediaKeySystemAccess` (Widevine) is simply not available on it. Hence the secure-context
     * requirement here.
     */
    suspend fun awaitPageReady(timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val parts = eval(JS_PAGE_STATE, timeoutMs = 2_000)?.split('|')
            val ready = parts?.getOrNull(0) == "interactive" || parts?.getOrNull(0) == "complete"
            val secure = parts?.getOrNull(1) == "1"
            if (ready && secure) return true
            delay(300)
        }
        return false
    }

    /** Current page address, for diagnostics. Null when the page cannot be asked. */
    suspend fun currentUrl(): String? =
        eval(JS_PAGE_STATE, timeoutMs = 2_000)?.split('|')?.getOrNull(2)

    private const val JS_PAGE_STATE =
        "(function(){try{return document.readyState+'|'+(window.isSecureContext?'1':'0')+'|'+location.href;}" +
        "catch(e){return 'error|0|';}})();"

    /**
     * Waits until the page is actually producing audio, re-pressing play periodically while it is
     * not. Returns false on timeout, which is what tells the caller the web player could not serve
     * this track and the normal engine should take over.
     *
     * Both conditions matter: `isPlaying` alone can be true for a beat before the stream really
     * starts, so the position has to have moved off zero as well.
     */
    suspend fun awaitPlaybackStart(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            refreshState()
            polls++
            val s = _state.value
            // The first polls are ignored: a navigation that has not committed yet can still be
            // answered by the outgoing page, which reads as "already playing".
            if (polls > 2 && s.available && s.isPlaying && s.positionMs > 0) return true
            // Every ~1.6s: the transport may only just have rendered.
            if (s.available && !s.isPlaying && polls % 4 == 0) nudgePlay()
        }
        return false
    }

    private const val POLL_INTERVAL_MS = 400L

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

    /** Re-applies the layout width and the tap-to-play bridge. Cheap and idempotent. */
    private fun applyPageTweaks(view: WebView?) {
        val wv = view ?: return
        runCatching {
            if (cachedLayoutWidth != 0) wv.evaluateJavascript(viewportJs(cachedLayoutWidth), null)
            wv.evaluateJavascript(JS_TAP_TO_PLAY, null)
        }
    }

    /**
     * Lays the page out at [width] CSS pixels and scales it so that width fills the screen.
     *
     * The explicit `initial-scale` is the part that matters. `loadWithOverviewMode` computes its
     * fit-to-screen zoom when the page loads, and the viewport meta is rewritten *after* that, so
     * the page was re-laid out wide but never re-zoomed — which is why it needed horizontal
     * scrolling. Computing the scale here re-applies it every time the width changes.
     */
    private fun viewportJs(width: Int) = """
        (function(){
          try {
            var w = $width;
            var m = document.querySelector('meta[name="viewport"]');
            if (!m) {
              m = document.createElement('meta');
              m.setAttribute('name','viewport');
              (document.head || document.documentElement).appendChild(m);
            }
            var sw = window.screen.width || document.documentElement.clientWidth || w;
            var scale = Math.round((sw / w) * 1000) / 1000;
            var want = 'width=' + w + ', initial-scale=' + scale + ', user-scalable=yes';
            if (m.getAttribute('content') !== want) m.setAttribute('content', want);
          } catch (e) {}
        })();
    """

    /**
     * Plays a track row on a **two-finger tap**.
     *
     * Spotify's desktop player plays a row on double-click; a single click only selects it, and the
     * per-row play button only appears on hover, which a touchscreen never produces. So on a phone
     * tapping a song appears to do nothing at all.
     *
     * A two-finger tap is used rather than forwarding every single tap, which made ordinary
     * navigation launch songs by accident, and rather than a double tap, which the WebView already
     * owns for zoom. Two fingers down, neither moving, both released quickly: the row under the
     * first finger gets a synthetic `dblclick`.
     *
     * Guarded by a flag on `window` so the SPA re-applying it never stacks listeners, and attached to
     * `document` so it survives the SPA replacing the DOM.
     */
    private const val JS_TAP_TO_PLAY = """
        (function(){
          try {
            if (window.__rustifyTwoFingerPlay) return;
            window.__rustifyTwoFingerPlay = true;
            var MOVE_SLOP = 14, MAX_MS = 600;
            var pending = null;
            document.addEventListener('touchstart', function(e){
              if (e.touches.length === 2) {
                var t = e.touches[0];
                pending = { x: t.clientX, y: t.clientY, at: Date.now(), moved: false };
              } else if (e.touches.length > 2) {
                pending = null;
              }
            }, true);
            document.addEventListener('touchmove', function(e){
              if (!pending) return;
              var t = e.touches[0];
              if (!t) return;
              if (Math.abs(t.clientX - pending.x) > MOVE_SLOP ||
                  Math.abs(t.clientY - pending.y) > MOVE_SLOP) pending.moved = true;
            }, true);
            document.addEventListener('touchend', function(e){
              if (!pending) return;
              if (e.touches.length > 0) return;            // wait for both fingers to lift
              var p = pending; pending = null;
              if (p.moved || Date.now() - p.at > MAX_MS) return;
              var el = document.elementFromPoint(p.x, p.y);
              if (!el || !el.closest) return;
              var row = el.closest('[data-testid="tracklist-row"], [data-testid="track-list-row"], [data-testid="tracklist-row-container"]');
              if (!row) return;
              row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));
            }, true);
            document.addEventListener('touchcancel', function(){ pending = null; }, true);
          } catch (e) {}
        })();
    """

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
