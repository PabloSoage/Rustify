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
        /**
         * The page has a media element of its own, i.e. it is the thing producing sound.
         *
         * Without this, a page showing track metadata is indistinguishable from a page *playing* it —
         * and Spotify's transport bar mirrors whatever device your account last used, so it happily
         * shows a title, an artist and artwork while this browser is only acting as a remote control
         * with nothing local to press play on. Metadata but no element means exactly that.
         */
        val hasLocalMedia: Boolean = false,
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
    private const val KEY_PLAYBACK_DESKTOP = "web_player_playback_desktop"

    /**
     * Which page the *backend* plays through, independently of the layout you browse in.
     *
     * The two behave differently and neither is strictly better, so this is a switch rather than a
     * decision baked into the code:
     *
     *  - **Phone page** (default). Starts instantly on a tap — measured. But it is the mobile site,
     *    so on an account without Premium it plays shuffled, and the backend can end up on a
     *    different track than the one it asked for.
     *  - **Desktop page.** On-demand and no shuffle restriction, but it is a Spotify Connect remote
     *    before it is a player: unless this browser is the active device, its play button asks
     *    whatever device you last used, and nothing happens here. [takeOverPlaybackDevice] is what
     *    makes that case recoverable.
     */
    @Volatile private var cachedPlaybackDesktop = false

    /** Reads and caches [KEY_PLAYBACK_DESKTOP]; the cache is what the playback path can reach. */
    fun playbackUsesDesktop(context: Context): Boolean {
        cachedPlaybackDesktop = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PLAYBACK_DESKTOP, false)
        return cachedPlaybackDesktop
    }

    /** Persists the choice. Takes effect on the next track opened; nothing is reloaded now. */
    fun setPlaybackUsesDesktop(context: Context, desktop: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PLAYBACK_DESKTOP, desktop) }
        cachedPlaybackDesktop = desktop
    }

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
        mainHandler.post {
            runCatching {
                // Compare against the agent the WebView is *actually* carrying, not against the
                // previous preference: playback switches it to the phone agent behind the screen's
                // back (see [playSpotifyUrl]), so the two can disagree.
                val want = userAgentFor(next != 0, systemUserAgent ?: wv.settings.userAgentString)
                if (wv.settings.userAgentString != want) {
                    wv.settings.userAgentString = want
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
        playbackUsesDesktop(context)   // prime the cache; the playback path has no Context
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

    /**
     * What the last attempt to press play actually did — which selector matched, or that none did.
     *
     * "Playback did not start" has several causes that look the same from outside: the button was
     * never found, it was found and pressed and the page still refused (no Premium, region block),
     * or the page was not the one we thought. The self-test reports this so they can be told apart.
     */
    @Volatile var lastPlayAttempt: String? = null
        private set

    /**
     * Result of the last attempt to claim playback for this page, or null if it was never needed
     * (which is the good case — it means the page had a player of its own).
     */
    @Volatile var lastDeviceTakeover: String? = null
        private set

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
     *
     * **Playback picks its own page**, independently of the layout the screen is browsing in — see
     * [cachedPlaybackDesktop]. Nothing is reloaded for it, because opening a track is a navigation
     * anyway.
     *
     * The desktop page was the obvious choice — it is the one with a real library — but measured, it
     * does not play here. The self-test reached the point of reporting
     * `clicked [data-testid="action-bar-row"] [data-testid="play-button"]` on the correct track
     * address, with WebView, session and Widevine all passing, and nothing ever started. The phone
     * page, by contrast, starts instantly on a plain tap, so it is the default.
     */
    fun playSpotifyUrl(url: String) {
        val wv = webView ?: return
        // Clear the mirrored state first. It still describes the *previous* track, and until the new
        // page loads it would read as "already playing" — which is exactly what awaitPlaybackStart
        // is looking for, so the watchdog would declare success before anything had happened.
        stateGeneration++
        _state.value = WebState()
        mainHandler.post {
            runCatching {
                val ua = userAgentFor(cachedPlaybackDesktop, systemUserAgent ?: wv.settings.userAgentString)
                if (wv.settings.userAgentString != ua) wv.settings.userAgentString = ua
            }
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
                            hasLocalMedia = o.optBoolean("local", false),
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

    // -------------------------------------------------------------------------------------------
    // Spotify Connect: making this page the device
    // -------------------------------------------------------------------------------------------

    /**
     * Tells the page to play **here** instead of wherever the account last played.
     *
     * Spotify's transport bar is a Connect remote first and a player second: it shows the state of
     * your last active device, and its play button asks *that* device to resume. Reported exactly
     * that way — "I see my last Spotify playback in the miniplayer, but it won't let me interact with
     * play" — and it matches a self-test that finds the button, clicks it, and never hears anything.
     * The click was not being ignored; it was being sent somewhere else.
     *
     * The fix is the same one a person would do: open the device picker and choose this browser. Two
     * evaluations with a pause between them, because the list is rendered when the picker opens.
     *
     * Returns a short description of what happened, for the self-test.
     */
    suspend fun takeOverPlaybackDevice(): String {
        val opened = eval(JS_OPEN_DEVICE_PICKER) ?: "no answer"
        if (!opened.startsWith("opened")) return opened
        delay(900)
        return eval(JS_PICK_THIS_BROWSER) ?: "no answer"
    }

    private const val JS_OPEN_DEVICE_PICKER = """
        (function(){
          try {
            var sels = [
              '[data-testid="control-button-connect-to-device"]',
              '[data-testid="connect-device-picker-trigger"]',
              'button[aria-label*="onnect"]',
              'button[aria-label*="onectar"]',
              'button[aria-label*="ispositiv"]'
            ];
            for (var i = 0; i < sels.length; i++) {
              var b = document.querySelector(sels[i]);
              if (b) { b.click(); return 'opened via ' + sels[i]; }
            }
            return 'no device picker';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Picks the entry that *is* this browser. Spotify labels it differently per platform and
     * language ("Web Player (Chrome)", "This web browser", "Este navegador web"), so the list is
     * matched on text rather than on a position or an id.
     */
    private const val JS_PICK_THIS_BROWSER = """
        (function(){
          try {
            var items = document.querySelectorAll(
              '[data-testid="device-picker-item"], [data-testid="connect-device-list-item"], ' +
              '[data-testid="connect-device-list-item-current"], li button, [role="menuitem"]'
            );
            var wanted = /(web ?player|this (web )?browser|este navegador|ce navigateur|dieser browser)/i;
            for (var i = 0; i < items.length; i++) {
              var t = (items[i].textContent || '').trim();
              if (wanted.test(t)) {
                items[i].click();
                return 'switched to "' + t.slice(0, 28) + '"';
              }
            }
            return 'this browser not in device list (' + items.length + ' entries)';
          } catch (e) { return 'error'; }
        })();
    """

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
        var tookOver = false
        lastPlayAttempt = null
        lastDeviceTakeover = null
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            refreshState()
            polls++
            val s = _state.value
            // The first polls are ignored: a navigation that has not committed yet can still be
            // answered by the outgoing page, which reads as "already playing".
            if (polls > 2 && s.available && s.isPlaying && s.positionMs > 0) return true
            // Every ~1.6s: the transport may only just have rendered. Note this must NOT be gated on
            // `available`, which is "there is a media element or media-session metadata" — both false
            // on a page that has never played, which is precisely when play needs pressing. Gating on
            // it meant the nudges never fired for a freshly opened track.
            // Metadata with no media element of its own: the page is showing another device's
            // playback and every click is being forwarded there. Pressing play harder will not help
            // — claim the playback first, once, and then go back to nudging.
            if (!tookOver && polls > 6 && !s.hasLocalMedia && s.title.isNotBlank()) {
                tookOver = true
                lastDeviceTakeover = takeOverPlaybackDevice()
                continue
            }
            // Read the result rather than firing and forgetting: it is the only evidence of whether
            // the button was even there, and it is what the self-test reports on failure.
            if (polls % 4 == 0) lastPlayAttempt = eval(JS_AUTOPLAY, timeoutMs = 2_000) ?: "no answer"
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

    /**
     * Presses play on a page that has not played anything yet.
     *
     * The previous version only acted `if (m && m.paused)` — that is, only when a media element
     * already existed. But a freshly loaded Spotify page has **no** `<video>`/`<audio>` at all; the
     * player creates it when playback starts. So on exactly the page this is meant for, `m` was null
     * and the button was never clicked. The track opened, sat there, and the watchdog timed out.
     *
     * Order matters: the big button on an entity page starts *that* entity, whereas the transport
     * bar's button resumes whatever the session last had.
     */
    private const val JS_AUTOPLAY = """
        (function(){
          try {
            var m = document.querySelector('video,audio');
            if (m && !m.paused) return 'already playing';
            // A bare .click() is not what a tap looks like. The phone page — the one that actually
            // plays — is built for touch, and a control that waits for pointerdown/pointerup sees
            // nothing in a lone click event. Press the way a finger does, then click.
            function press(b){
              var r = b.getBoundingClientRect();
              var x = r.left + r.width / 2, y = r.top + r.height / 2;
              var opts = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y };
              try {
                var pd = Object.assign({ pointerId: 1, pointerType: 'touch', isPrimary: true }, opts);
                b.dispatchEvent(new PointerEvent('pointerdown', pd));
                b.dispatchEvent(new MouseEvent('mousedown', opts));
                b.dispatchEvent(new PointerEvent('pointerup', pd));
                b.dispatchEvent(new MouseEvent('mouseup', opts));
              } catch (e) {}
              b.click();
            }
            var sels = [
              '[data-testid="action-bar-row"] [data-testid="play-button"]',
              '[data-testid="play-button"]',
              '[data-testid="control-button-playpause"]'
            ];
            for (var i = 0; i < sels.length; i++) {
              var b = document.querySelector(sels[i]);
              if (b) { press(b); return 'clicked ' + sels[i]; }
            }
            // Spotify has renamed these test ids before. The accessible name is the more durable
            // handle, and it is localised, so match the start of it in the languages the app ships.
            var btns = document.querySelectorAll('button[aria-label]');
            for (var j = 0; j < btns.length; j++) {
              var label = (btns[j].getAttribute('aria-label') || '').toLowerCase();
              if (/^(play|reproducir|reproduzir|lire|abspielen|riprod)/.test(label) ||
                  label.indexOf('再生') === 0) {
                press(btns[j]);
                return 'clicked aria "' + label.slice(0, 24) + '"';
              }
            }
            if (m) { m.play().catch(function(){}); return 'element play()'; }
            return 'no play control on page';
          } catch (e) { return 'error: ' + ((e && e.message) || 'unknown'); }
        })();
    """

    /** Re-applies the layout width and the tap/pointer bridges. Cheap and idempotent. */
    private fun applyPageTweaks(view: WebView?) {
        val wv = view ?: return
        runCatching {
            // Only widen a page that was actually served as the desktop one. Playback switches the
            // agent to the phone (see [playSpotifyUrl]), so the stored width can outlive the layout
            // it belongs to — and laying the phone page out at 1024px just shrinks it to nothing.
            val desktopPage = wv.settings.userAgentString.contains("Windows")
            if (cachedLayoutWidth != 0 && desktopPage) wv.evaluateJavascript(viewportJs(cachedLayoutWidth), null)
            wv.evaluateJavascript(JS_TAP_TO_PLAY, null)
            wv.evaluateJavascript(JS_POINTER_BRIDGE, null)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Virtual mouse
    // -------------------------------------------------------------------------------------------

    /**
     * Moves the virtual cursor to ([fx], [fy]) — fractions of the visible page, 0..1 — and sends the
     * hover events that go with it. Spotify's desktop layout hides the per-row play button until the
     * row is hovered, which a touchscreen never does.
     */
    fun pointerMove(fx: Float, fy: Float) = run(pointerJs(fx, fy, "move"))

    /** Clicks at ([fx], [fy]). [double] sends the double-click that plays a track row. */
    fun pointerClick(fx: Float, fy: Float, double: Boolean) =
        run(pointerJs(fx, fy, if (double) "dblclick" else "click"))

    /** Scrolls the scrollable element under ([fx], [fy]) by [dx]/[dy] CSS pixels. */
    fun pointerScroll(fx: Float, fy: Float, dx: Float, dy: Float) =
        run(pointerJs(fx, fy, "scroll", dx, dy))

    private fun pointerJs(fx: Float, fy: Float, action: String, dx: Float = 0f, dy: Float = 0f) =
        "window.__rustifyPointer&&window.__rustifyPointer($fx,$fy,'$action',$dx,$dy);"

    /**
     * A synthetic mouse for a page that was written for one.
     *
     * Spotify's web player is a desktop application: rows play on double-click, controls appear on
     * hover, and at the zoom needed to fit a 1024px layout on a phone its buttons are a few device
     * pixels across — so a finger can neither hit them nor express what they expect. The screen
     * drives a cursor over the page and calls in here to replay it as mouse events.
     *
     * Coordinates arrive as fractions of the *visual* viewport and are converted through
     * `visualViewport`, so they stay correct when the page is zoomed or panned. `elementFromPoint`
     * then resolves the target the same way a real pointer would.
     *
     * Hover is tracked so that leaving an element emits `mouseout`/`mouseleave` — React components
     * that show a control on enter would otherwise never hide it again. Note that CSS `:hover` does
     * not respond to synthetic events at all (they are untrusted); anything driven purely by CSS
     * stays hidden, which is why double-click is still the way to play a row.
     */
    private const val JS_POINTER_BRIDGE = """
        (function(){
          try {
            if (window.__rustifyPointer) return;
            var hovered = null;
            function at(fx, fy){
              var vv = window.visualViewport;
              var x = (vv ? vv.offsetLeft : 0) + fx * (vv ? vv.width : window.innerWidth);
              var y = (vv ? vv.offsetTop : 0) + fy * (vv ? vv.height : window.innerHeight);
              return { x: x, y: y, el: document.elementFromPoint(x, y) };
            }
            function fire(el, type, x, y, extra){
              if (!el) return;
              var init = { bubbles: true, cancelable: true, view: window,
                           clientX: x, clientY: y, button: 0, buttons: 0 };
              if (extra) { for (var k in extra) init[k] = extra[k]; }
              el.dispatchEvent(new MouseEvent(type, init));
            }
            function scroller(el){
              var n = el;
              while (n && n !== document.body && n !== document.documentElement) {
                var s = window.getComputedStyle(n);
                if (n.scrollHeight > n.clientHeight + 1 && /(auto|scroll|overlay)/.test(s.overflowY)) return n;
                n = n.parentElement;
              }
              return null;
            }
            window.__rustifyPointer = function(fx, fy, action, dx, dy){
              try {
                var p = at(fx, fy);
                if (action === 'move') {
                  if (hovered !== p.el) {
                    if (hovered) {
                      fire(hovered, 'mouseout', p.x, p.y, { relatedTarget: p.el });
                      fire(hovered, 'mouseleave', p.x, p.y, { bubbles: false });
                    }
                    if (p.el) {
                      fire(p.el, 'mouseover', p.x, p.y, { relatedTarget: hovered });
                      fire(p.el, 'mouseenter', p.x, p.y, { bubbles: false });
                    }
                    hovered = p.el;
                  }
                  fire(p.el, 'mousemove', p.x, p.y);
                  return 'move';
                }
                if (action === 'click' || action === 'dblclick') {
                  if (hovered !== p.el) {
                    fire(p.el, 'mouseover', p.x, p.y, { relatedTarget: hovered });
                    hovered = p.el;
                  }
                  fire(p.el, 'mousemove', p.x, p.y);
                  fire(p.el, 'mousedown', p.x, p.y, { buttons: 1 });
                  fire(p.el, 'mouseup', p.x, p.y);
                  fire(p.el, 'click', p.x, p.y, { detail: 1 });
                  if (action === 'dblclick') {
                    fire(p.el, 'mousedown', p.x, p.y, { buttons: 1, detail: 2 });
                    fire(p.el, 'mouseup', p.x, p.y, { detail: 2 });
                    fire(p.el, 'click', p.x, p.y, { detail: 2 });
                    fire(p.el, 'dblclick', p.x, p.y, { detail: 2 });
                  }
                  return action;
                }
                if (action === 'scroll') {
                  var n = scroller(p.el);
                  if (n) { n.scrollBy(dx, dy); } else { window.scrollBy(dx, dy); }
                  return 'scroll';
                }
                return 'unknown';
              } catch (e) { return 'error'; }
            };
          } catch (e) {}
        })();
    """

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
              local: !!m,
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
