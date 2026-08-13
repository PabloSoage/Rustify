package com.varuna.rustify.webplayer

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
    private const val KEY_ADBLOCK = "web_player_adblock"

    /**
     * Hosts the player is *built out of*. Never filtered, whatever the lists say.
     *
     * The filter lists are uBlock Origin's, and uBO ships privacy rules that match Spotify's own
     * telemetry and session endpoints — which is fine in a browser where the page still works
     * without them, and not fine here, where the page *is* the audio backend. Worse, the source
     * URL handed to the matcher is `open.spotify.com`, so everything on `scdn.co` /
     * `spotifycdn.com` counts as third-party and is exposed to every `$third-party` rule in
     * EasyPrivacy. Nothing is gained by filtering the site we are logged into, so it is exempt.
     */
    private val FIRST_PARTY_HOSTS = listOf(
        "spotify.com", "spotifycdn.com", "spotifycdn.net", "scdn.co", "pscdn.co",
        "spoti.fi", "byspotify.com", "spotify.net"
    )

    private fun isFirstParty(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return FIRST_PARTY_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Third parties that are answered with an empty resource instead of being allowed to **fail**.
     *
     * This is not filtering, and it is deliberately not behind the ad-blocker switch. It exists
     * because of one measured chain, end to end:
     *
     * 1. `cdn.cookielaw.org/scripttemplates/otSDKStub.js` does not load. Measured, in every log since
     *    the first console was ever captured.
     * 2. Its `<script>` fires `error`, the player's loader rejects a promise with `undefined`, and the
     *    startup ends there — `reject(undefined) @ … < HTMLScriptElement.onError`, with the
     *    MediaSource built and never attached.
     * 3. And **we were not the ones blocking it**: the same run reported `blocked 1 hosts, 0 scripts:
     *    o22381.ingest.sentry.io`. The request went to the network and failed on its own, somewhere
     *    between the device's DNS and the CDN.
     *
     * Which finally explains Firefox. uBlock Origin *does* carry cookie-consent lists, so there the
     * script is blocked — and uBO neutralises a script by serving an **empty script**, which fires
     * `load`. Same page, same phone, same failing third party: it plays because the load succeeds
     * emptily instead of failing. Our lists do not carry that host, so the request went out and died.
     *
     * So it is answered here, before the network, with an empty script. Kept to consent managers,
     * which are the ones a player has no business waiting on, and short: a list like this is a
     * liability the moment it starts collecting hosts nobody measured.
     */
    private val NEUTRALISED_HOSTS = listOf(
        "cookielaw.org", "onetrust.com", "optanon.blob.core.windows.net"
    )

    private fun isNeutralised(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return NEUTRALISED_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Whether third-party requests are filtered at all. On by default; a kill switch for testing. */
    @Volatile private var cachedAdblock = true

    fun adblockEnabled(context: Context): Boolean {
        cachedAdblock = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADBLOCK, true)
        return cachedAdblock
    }

    /** Persists the choice and applies it immediately — the interceptor reads the cache per request. */
    fun setAdblockEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ADBLOCK, on) }
        cachedAdblock = on
    }

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
        adblockEnabled(context)
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

                // The page's own errors, which until now went nowhere. "No media element" says the
                // player was never built; it cannot say why. Whatever threw on the way to building it
                // said so here, and the self-test can now quote it instead of guessing.
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val m = consoleMessage ?: return false
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        val source = m.sourceId().orEmpty().substringAfterLast('/').take(28)
                        // Full text to the log — the self-test's copy is cut to fit on screen, and the
                        // part that names the failing API is often past the cut.
                        android.util.Log.w(
                            "WebPlayerController",
                            "page error: ${m.message()} (${m.sourceId()}:${m.lineNumber()})"
                        )
                        // 200, not 120: the message that matters now carries a stack, and a stack cut
                        // at 120 characters is one frame — which is the frame inside the shim.
                        recordPageError("${m.message().take(200)} ($source:${m.lineNumber()})")
                    }
                    return false
                }
            }

            webViewClient = object : WebViewClient() {
                // The media hook has to be in place before the page's own code calls play(), so it
                // goes in as early as the WebView will let us rather than waiting for the load to
                // finish. Idempotent, so re-running it later costs nothing.
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    runCatching {
                        // The shim has to beat the bundle's own feature detection, so it goes first
                        // and at the earliest hook there is.
                        if (view?.settings?.userAgentString?.contains("Windows") == true) {
                            view?.evaluateJavascript(JS_DESKTOP_SHIM, null)
                            view?.evaluateJavascript(JS_BUILD_PROBE, null)
                            view?.evaluateJavascript(JS_VISIBLE_SHIM, null)
                            view?.evaluateJavascript(JS_NET_HOOK, null)
                            view?.evaluateJavascript(JS_DISMISS_CONSENT, null)
                            view?.evaluateJavascript(JS_PROMISE_TRACE, null)
                        }
                        view?.evaluateJavascript(JS_ERROR_HOOK, null)
                        view?.evaluateJavascript(JS_EME_RELAX, null)
                        view?.evaluateJavascript(JS_MEDIA_HOOK, null)
                    }
                }

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
                    // Before the switch on purpose: this is a stub for a resource that fails, not a
                    // filter, and turning filtering off must not put the page back into the state
                    // where a dead consent script stops playback. See [NEUTRALISED_HOSTS].
                    if (isNeutralised(req.url?.host)) {
                        val t = resourceTypeOf(req)
                        android.util.Log.d("WebPlayerController", "stubbed $t ${req.url}")
                        recordBlocked(t, req.url?.host)
                        return blockedResponse(blockedMimeFor(t))
                    }
                    if (!cachedAdblock) return null
                    // Spotify's own infrastructure is never filtered — see [FIRST_PARTY_HOSTS].
                    if (isFirstParty(req.url?.host)) return null
                    val url = req.url?.toString() ?: return null
                    if (!url.startsWith("http")) return null
                    val type = resourceTypeOf(req)
                    val blocked = runCatching {
                        NativeEngine.adblockMatchesNative(url, SPOTIFY_WEB, type)
                    }.getOrDefault(false)
                    if (!blocked) return null
                    // Logged, because a blocked request that breaks the page is indistinguishable
                    // from one that does not until something breaks — and one of these cost fourteen
                    // rounds. Settings → Logs.
                    android.util.Log.d("WebPlayerController", "blocked $type $url")
                    recordBlocked(type, req.url?.host)
                    return blockedResponse(blockedMimeFor(type))
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

    /**
     * The last few distinct JavaScript errors the page reported, newest last.
     *
     * A page that throws on the way to building its player looks, from here, exactly like a page that
     * simply has not built it yet — both answer "no media element". Keeping the console errors turns
     * that dead end into a name and a line number. Distinct only: a broken bundle repeats the same
     * error hundreds of times and the interesting one is rarely the last.
     */
    private val pageErrorLog = ArrayDeque<String>()

    /**
     * Errors that have been chased down and are not it, kept out of the handful the self-test can
     * show. The reCAPTCHA frame belongs to a login widget that is not on the path to audio, and it
     * was taking one of the few slots.
     *
     * **`cookielaw.org` is no longer on this list**, and its removal is the correction worth keeping.
     * It was dismissed on the reasoning that Firefox blocks the same script and plays anyway — true,
     * and irrelevant: uBlock Origin also removes the *element*, and our filtering is network-level
     * only, so here the banner rendered with no script left to dismiss it and sat on top of the page.
     * The device picker eventually found it: the only labelled control on the page was "Configuración
     * de cookies". A filter list for "errors already explained" is exactly where a wrong explanation
     * goes to hide, so this one stays short.
     *
     * Nothing is lost either way — every console error still goes to the log in full
     * (Settings → Logs).
     */
    private val KNOWN_NOISE = listOf("requestStorageAccess")

    private fun recordPageError(message: String) {
        if (KNOWN_NOISE.any { it in message }) return
        synchronized(pageErrorLog) {
            if (message in pageErrorLog) return
            pageErrorLog.addLast(message)
            while (pageErrorLog.size > 8) pageErrorLog.removeFirst()
        }
    }

    fun pageErrors(): List<String> = synchronized(pageErrorLog) { pageErrorLog.toList() }

    fun clearPageErrors() {
        synchronized(pageErrorLog) { pageErrorLog.clear() }
        synchronized(blockedLog) { blockedLog.clear(); blockedScripts = 0 }
    }

    /**
     * What the filter engine actually stopped during the last attempt.
     *
     * The MIME fix for blocked scripts changed nothing, and there are two very different reasons it
     * might not have: either we never blocked that script and it failed on the network on its own, or
     * we did and an empty script is not enough. The self-test cannot tell them apart from the page's
     * side — a request that was refused and one that was answered with nothing both end at the same
     * `resource failed` line — but this side knows exactly which requests it stopped.
     *
     * Hosts rather than URLs, deduplicated: the interesting question is *which third parties*, and a
     * page load blocks the same tracker a dozen times.
     */
    private val blockedLog = LinkedHashSet<String>()
    @Volatile private var blockedScripts = 0

    private fun recordBlocked(type: String, host: String?) {
        val h = host ?: return
        if (type == "script") blockedScripts++
        synchronized(blockedLog) {
            if (blockedLog.size < 10) blockedLog.add(h)
        }
    }

    fun blockedSummary(): String = synchronized(blockedLog) {
        if (blockedLog.isEmpty()) return "none blocked"
        "${blockedLog.size} hosts, $blockedScripts scripts: ${blockedLog.joinToString(" ")}"
    }

    fun togglePlayPause() = run(click("control-button-playpause"))
    // No next/previous here on purpose: opening a track URL leaves Spotify's own context holding
    // that single song, so its skip buttons have nowhere to go. Queue navigation belongs to
    // AudioPlayerService, which then opens the next track in the page with playSpotifyUrl.

    /** Seeks the underlying media element directly — far more reliable than dragging its slider. */
    fun seekTo(positionMs: Long) = run(
        "(function(){$JS_MEDIA_FN var m=__rmMedia(); if(m){m.currentTime=${positionMs / 1000.0};}})();"
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
     * Returns a short description of what happened, for the self-test — **including which selector
     * opened the picker**. The first measurement that reached this step answered "this browser not in
     * device list (1 entries, 0 skipped)", and one entry is not a device list; it is far more likely
     * that the button that got clicked was not the device picker at all. Without naming the selector
     * there is no way to tell those apart, and the fallbacks match on a fragment of a label
     * ("onnect", "onectar") that other buttons can carry too.
     *
     * Tried repeatedly, because it **is** a race and that is measured, not assumed: two consecutive
     * runs of the same build, on the same page, answered `switched to "Este navegador web"` and then
     * `not in list of 3` with only the cookie-settings control in it. The device list is not rendered
     * with the picker — it is fetched, and until it arrives the popover is an empty dialog. Two
     * attempts were not enough; five over four seconds cost nothing when the alternative is a
     * diagnostic that reports a different thing each time it is run.
     */
    suspend fun takeOverPlaybackDevice(): String {
        val opened = eval(JS_OPEN_DEVICE_PICKER) ?: "no answer"
        if (!opened.startsWith("opened")) return opened
        val via = opened.removePrefix("opened via ").take(28)
        var picked = "no answer"
        repeat(5) { attempt ->
            delay(if (attempt == 0) 700 else 850)
            picked = eval(JS_PICK_THIS_BROWSER) ?: picked
            if (!picked.startsWith("not in")) return "$via → $picked"
        }
        return "$via → $picked"
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
              if (!b) continue;
              b.click();
              // The last three match a fragment of an accessible label, which other buttons can carry
              // too. Naming the one that was actually clicked is what tells a picker that opened onto
              // the wrong list from a picker that was never opened.
              var lbl = (b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 20);
              return 'opened via ' + (i < 2 ? sels[i].slice(0, 24) : 'label "' + lbl + '"');
            }
            return 'no device picker';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Picks the entry that *is* this browser. Spotify labels it differently per platform and
     * language ("Web Player (Chrome)", "This web browser", "Este navegador web"), so the list is
     * matched on text rather than on a position or an id.
     *
     * Two entries can match that description at once, which broke the first version of this: open
     * the phone page, let it register, then switch to desktop and the picker lists a **"Mobile Web
     * Player"** alongside this one. Clicking the first "web player" in the list then transfers
     * playback to the device we are trying to move away from — reported as "I click this browser
     * and it doesn't change". So phone entries are excluded, and so is whichever entry is already
     * the active device, since selecting that is by definition a no-op.
     *
     * When nothing matches it now **quotes what it did find**. "1 entries, 0 skipped" was a dead end:
     * a count cannot say whether the list was the wrong list, whether Spotify renamed the entry, or
     * whether the picker had not rendered yet. The labels can, and they are the only thing here that
     * Spotify is free to change without warning.
     */
    private const val JS_PICK_THIS_BROWSER = """
        (function(){
          try {
            var items = document.querySelectorAll(
              '[data-testid="device-picker-item"], [data-testid="connect-device-list-item"], ' +
              '[data-testid="connect-device-list-item-current"], [data-testid*="device"], ' +
              'li button, [role="menuitem"], [role="menuitemradio"], [role="option"]'
            );
            var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"]').length;
            var wanted = /(web ?player|this (web )?browser|este navegador|ce navigateur|dieser browser)/i;
            var notHere = /(mobile|m[oó]vil|phone|tel[eé]fono|android|iphone|ipad|tablet|tv|speaker|altavoz)/i;
            var skipped = 0, seen = [];
            for (var i = 0; i < items.length; i++) {
              var el = items[i];
              var t = (el.textContent || '').trim().replace(/\s+/g, ' ');
              // Empty entries told us nothing last time and there were two of them. Only labelled
              // ones are worth the space on the report.
              if (t && seen.length < 4) seen.push('"' + t.slice(0, 22) + '"');
              if (!wanted.test(t)) continue;
              if (notHere.test(t)) { skipped++; continue; }
              // Already the active device: Spotify marks it, and clicking it changes nothing.
              var active = el.getAttribute('aria-checked') === 'true' ||
                           el.getAttribute('aria-current') === 'true' ||
                           (el.getAttribute('data-testid') || '').indexOf('current') !== -1;
              if (active) { skipped++; continue; }
              el.click();
              return 'switched to "' + t.slice(0, 28) + '"';
            }
            return 'not in list of ' + items.length + ' (' + dialogs + ' dialogs, ' +
                   skipped + ' skipped): ' +
                   (seen.length ? seen.join(' ') : 'nothing labelled');
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

    /**
     * One line describing the media elements the page holds, for the self-test.
     *
     * "Playback started" is now decided from an element that may be detached, may be one of several,
     * and may be a silent placeholder — so when the test passes, this is the evidence that it passed
     * on something real, and when it fails, it says whether there was anything to look at.
     */
    suspend fun mediaReport(): String = eval(JS_MEDIA_REPORT, timeoutMs = 2_000) ?: "no answer"

    /**
     * What the page is telling the user, if anything.
     *
     * Eleven rounds have gone into reading the page's machinery — its requests, its counters, its
     * console — and none into reading the page. A web player that cannot play usually says so, in a
     * sentence, in the language of the account: a toast, an inline notice, a dialog. If Spotify is
     * refusing this for a reason it has a name for, that name is on screen and nowhere else, because
     * a refusal the product expects is not an error the console reports.
     */
    suspend fun pageNotice(): String = eval(JS_PAGE_NOTICE, timeoutMs = 2_000).orEmpty()

    private const val JS_PAGE_NOTICE = """
        (function(){
          try {
            // Ordered: the things that exist only to say something wrong first, and open dialogs
            // last — a dialog is worth reporting (the first measurement found two open and nobody
            // knew what they were) but it is the one most likely to be something ordinary.
            var sels = ['[role="alert"]', '[role="alertdialog"]', '[aria-live="assertive"]',
                        '[data-testid*="error"]', '[data-testid*="notification"]',
                        '[data-testid*="toast"]', '[data-testid*="banner"]',
                        '[role="dialog"]', '[aria-modal="true"]'];
            // Only what is actually on screen. The first measurement reported Spotify's language
            // chooser and a country list, which are in the markup of every page and were never shown
            // to anyone — an invisible dialog is not the page telling the user something, and quoting
            // it as if it were is how a diagnostic starts inventing leads.
            function visible(el){
              try {
                if (!el.offsetParent && getComputedStyle(el).position !== 'fixed') return false;
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              } catch (x) { return false; }
            }
            var out = [];
            for (var i = 0; i < sels.length && out.length < 3; i++) {
              var els = document.querySelectorAll(sels[i]);
              for (var j = 0; j < els.length && out.length < 3; j++) {
                if (!visible(els[j])) continue;
                var t = (els[j].textContent || '').trim().replace(/\s+/g, ' ');
                if (t && out.indexOf(t) === -1) out.push(t.slice(0, 70));
              }
            }
            return out.join(' | ');
          } catch (e) { return ''; }
        })();
    """

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
            //
            // Only on the desktop page. The phone page registers itself as a Connect device and
            // plays locally, so a takeover there can only move playback *away* — which it did,
            // audibly, while the media element was going undetected (see [JS_MEDIA_FN]).
            //
            // The title requirement is gone. It was there to identify the "showing another device"
            // case specifically, and it meant the takeover never ran once: the desktop page under
            // test publishes no media-session metadata at all, so the title is blank and the
            // condition could not be met on the very page it was written for — no `takeover:` has
            // appeared in any measurement. Claiming playback for a page that simply has not rendered
            // yet costs nothing: [JS_PICK_THIS_BROWSER] skips the entry that is already active and
            // every phone entry, and it runs once.
            if (!tookOver && cachedPlaybackDesktop && polls > 6 && !s.hasLocalMedia) {
                tookOver = true
                lastDeviceTakeover = takeOverPlaybackDevice()
                // Press it again right away when the takeover actually landed. Until now the next
                // nudge was up to four polls away, and the click before it went to a different device
                // by definition — that is what the takeover was for. Waiting after succeeding wastes
                // the only part of the attempt that changed anything.
                if (lastDeviceTakeover?.contains("switched to") == true) {
                    lastPlayAttempt = eval(JS_AUTOPLAY, timeoutMs = 2_000) ?: "no answer"
                }
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

    /**
     * Finds the element producing sound, which is **not** always in the document.
     *
     * `document.querySelector('video,audio')` was the whole detection, and on the phone page it
     * finds nothing while the song is audibly playing: the element is constructed in script and fed
     * by MSE without ever being appended. Everything downstream then went wrong at once — the
     * self-test reported "playback starts" as failed on a track it could hear, and
     * [awaitPlaybackStart] read "metadata but no player of its own" and fired a Spotify Connect
     * takeover at a page that *was* the player, which is what made the song stop and restart.
     *
     * So collect from three places — the document, the element the hook below caught calling
     * `play()`, and same-origin frames — and then **choose**, rather than taking the first hit. A
     * page can hold more than one: web players routinely keep a silent, muted element alive purely
     * to own the media session, and picking that one turns a false negative into the worse failure,
     * a self-test that passes while nothing is audible. The score prefers, in order, an element that
     * is running, one that can be heard, and one whose duration looks like a song.
     */
    private const val JS_MEDIA_FN = """
        function __rmMedia(){
          var seen = [];
          function add(e){ if (e && seen.indexOf(e) === -1) seen.push(e); }
          var nodes = document.querySelectorAll('video,audio');
          for (var i = 0; i < nodes.length; i++) add(nodes[i]);
          add(window.__rustifyMedia);
          var fs = document.getElementsByTagName('iframe');
          for (var j = 0; j < fs.length; j++) {
            try {
              var d = fs[j].contentDocument;
              if (!d) continue;
              var inner = d.querySelectorAll('video,audio');
              for (var k = 0; k < inner.length; k++) add(inner[k]);
            } catch (x) {}
          }
          if (!seen.length) return null;
          function score(e){
            var s = 0;
            if (!e.paused) s += 8;
            if (!e.muted && e.volume > 0) s += 4;
            if (isFinite(e.duration) && e.duration > 1) s += 2;
            if (e.currentTime > 0) s += 1;
            return s;
          }
          var best = seen[0], bestScore = score(best);
          for (var n = 1; n < seen.length; n++) {
            var s2 = score(seen[n]);
            if (s2 > bestScore) { best = seen[n]; bestScore = s2; }
          }
          return best;
        }
    """

    /**
     * Records whichever media element the page plays, attached to the document or not.
     *
     * Patching the prototype catches it at the only moment it is guaranteed to be reachable — the
     * call that starts it — and the capturing listeners catch elements handed to a source that
     * starts on its own. Installed on every navigation; the `window` flag keeps it to one layer.
     */
    private const val JS_MEDIA_HOOK = """
        (function(){
          try {
            if (window.__rustifyMediaHook) return 'already';
            window.__rustifyMediaHook = true;
            var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
            if (proto && proto.play) {
              var play = proto.play;
              proto.play = function(){
                try { window.__rustifyMedia = this; } catch (e) {}
                return play.apply(this, arguments);
              };
            }
            ['playing', 'loadedmetadata', 'timeupdate'].forEach(function(t){
              document.addEventListener(t, function(e){
                var el = e && e.target;
                if (el && typeof el.currentTime === 'number') window.__rustifyMedia = el;
              }, true);
            });
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Takes out a consent dialog that can no longer be answered — **if there is one, and there was
     * not.**
     *
     * Written because the device picker came back with "Configuración de cookies" as the only labelled
     * control on the page, which read as OneTrust's banner sitting over everything: the filter lists
     * block `cdn.cookielaw.org/otSDKStub.js`, so the markup would render with no script left to
     * dismiss it. The next measurement reported no `consent:` at all — this removed nothing, because
     * with its script blocked OneTrust never builds a banner in the first place. That string was one
     * of Spotify's own page controls, and what actually fixed the picker was widening the selectors
     * that look for device entries; it now answers `switched to "Este navegador web"`.
     *
     * Kept, because it costs a guard and two id lookups per mutation batch and it removes a real
     * variable: if the filter lists ever let the SDK through half-way, or Spotify changes provider, a
     * modal consent dialog over the player is exactly the kind of thing that would look like yet
     * another unexplained failure. When there is nothing to remove it says nothing, which is the
     * behaviour a diagnostic should have.
     *
     * The overlay would be removed rather than accepted. Consent cannot be recorded anyway with the
     * SDK blocked, and clicking "accept" on somebody's behalf to unblock a page is not ours to do.
     */
    private const val JS_DISMISS_CONSENT = """
        (function(){
          try {
            if (window.__rustifyConsent) return 'already';
            window.__rustifyConsent = true;
            window.__rustifyConsentRemoved = 0;
            var SELS = ['#onetrust-consent-sdk', '#onetrust-banner-sdk', '#onetrust-pc-sdk',
                        '.onetrust-pc-dark-filter', '.ot-fade-in'];
            function present(){
              return document.getElementById('onetrust-consent-sdk') ||
                     document.getElementById('onetrust-banner-sdk');
            }
            function sweep(){
              var removed = 0;
              for (var i = 0; i < SELS.length; i++) {
                var els = document.querySelectorAll(SELS[i]);
                for (var j = 0; j < els.length; j++) { els[j].remove(); removed++; }
              }
              if (removed) {
                try {
                  document.documentElement.style.overflow = '';
                  if (document.body) {
                    document.body.style.overflow = '';
                    document.body.classList.remove('ot-overflow-hidden');
                  }
                } catch (e) {}
                window.__rustifyConsentRemoved += removed;
              }
              return removed;
            }
            sweep();
            var mo = new MutationObserver(function(){ if (present()) sweep(); });
            mo.observe(document.documentElement, { childList: true, subtree: true });
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Gives the rejection a stack, by catching it where it is *created* instead of where it lands.
     *
     * One line has survived twelve rounds of narrowing: `rejected: undefined`, from the page under
     * test, and everything else on that page is explained. `unhandledrejection` cannot help — a value
     * rejected with `undefined` carries no stack, because nothing was ever thrown. But the *call* that
     * rejected it has one, and that is reachable if we are holding the `reject` function when it is
     * called.
     *
     * So `Promise` is wrapped: the executor's `reject`, and the static `Promise.reject`. When either
     * is called with `undefined` or `null` — deliberate abandonment, not an error — three frames of
     * the stack go to the console. Minified frames still name the chunk and the function, which is
     * three chunks more than "undefined".
     *
     * **This one has a real cost and it is worth stating.** `RP.prototype = P.prototype` keeps
     * `instanceof` working, but `Promise.prototype.constructor` still points at the original, so code
     * comparing `x.constructor === Promise` now sees false. And only promises the page builds with
     * `new Promise` or `Promise.reject` pass through here at all — `async`/`await` and `.then` chains
     * use the intrinsic constructor and are invisible to this. It is desktop-only, like the rest, so
     * the phone page that actually plays is untouched.
     *
     * How to tell if it broke something rather than measured it: the build counters. If `made`, `mse`
     * and `eme` come back as anything other than `1, 1, 7`, the wrapper changed the page's behaviour
     * and its output cannot be trusted.
     */
    private const val JS_PROMISE_TRACE = """
        (function(){
          try {
            if (window.__rustifyPromise) return 'already';
            if (!window.Promise) return 'unsupported';
            window.__rustifyPromise = true;
            var P = window.Promise;
            function frames(){
              try {
                return String(new Error().stack || '').split('\n').slice(2, 5)
                       .map(function(s){ return s.trim().replace(/^at\s+/, ''); })
                       .join(' < ').slice(0, 180);
              } catch (x) { return '?'; }
            }
            // Stamped like every other message, but inline: this hook is declared before the shared
            // pg() helper and a const cannot reach forward.
            function note(where, v){
              try {
                var seg = (location.pathname.split('/').filter(Boolean).pop() || 'root').slice(0, 8);
                console.error('[rustify:' + seg + '] ' + where + '(' +
                              (v === null ? 'null' : 'undefined') + ') @ ' + frames());
              } catch (x) {}
            }
            var RP = function(executor){
              return new P(function(res, rej){
                executor(res, function(v){
                  if (v === undefined || v === null) note('reject', v);
                  rej(v);
                });
              });
            };
            RP.prototype = P.prototype;
            RP.resolve = P.resolve.bind(P);
            RP.all = P.all.bind(P);
            RP.race = P.race.bind(P);
            if (P.allSettled) RP.allSettled = P.allSettled.bind(P);
            if (P.any) RP.any = P.any.bind(P);
            RP.reject = function(v){
              if (v === undefined || v === null) note('Promise.reject', v);
              return P.reject(v);
            };
            window.Promise = RP;
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * `pg()` — the page a console message came from, as its last path segment.
     *
     * Shared by the hooks below, because without it the error list cannot be trusted. Opening a track
     * is a navigation, and the page being navigated *away* from has requests in flight: those abort at
     * commit, and an aborted `fetch` rejects with the same `TypeError: Failed to fetch` as one that
     * never left. Three runs produced three different sets of failing hosts, which is what that noise
     * looks like. `intl-es` is the home page the test starts on; the track id is the page under test.
     */
    private const val JS_PAGE_TAG = """
        function pg(){
          try {
            var p = location.pathname.split('/').filter(Boolean);
            return (p[p.length - 1] || 'root').slice(0, 8);
          } catch (x) { return '?'; }
        }
    """

    /**
     * Asks again, without whatever this CDM refuses, when an EME request is turned down.
     *
     * Written for a hypothesis the next measurement killed. The split probe reports `pstate:ok
     * plicense:NotSupportedError`: persistent *state* is supported after all, and the only refusal is
     * `persistent-license` — the session type offline downloads need and streaming never asks for. So
     * Widevine is not what stops the desktop page. Four of five configurations pass and each one
     * instantiates a CDM.
     *
     * It stays because it costs nothing and can only run after the page's own request has already
     * failed — the worst case is the failure that was going to happen anyway — and because the
     * counter it keeps is now the more useful half: **whether the page asks for EME at all**. A page
     * that never asks has died before the part where DRM could matter.
     *
     * It says so in the console when it fires, because a shim that silently changes what a page asked
     * for is worse than no shim.
     */
    private const val JS_EME_RELAX = """
        (function(){
          try {
            if (window.__rustifyEme) return 'already';
            if (!navigator.requestMediaKeySystemAccess) return 'unsupported';
            window.__rustifyEme = true;
            $JS_PAGE_TAG
            var orig = navigator.requestMediaKeySystemAccess.bind(navigator);
            // Kept reachable so the self-test can still measure what the CDM really refuses. Asking
            // through the shim would answer "supported" for the one configuration being investigated.
            window.__rustifyEmeOrig = orig;
            navigator.requestMediaKeySystemAccess = function(keySystem, configs){
              try { window.__rustifyEmeCalls = (window.__rustifyEmeCalls || 0) + 1; } catch (e) {}
              return orig(keySystem, configs).catch(function(err){
                var relaxed = [], changed = false;
                try {
                  (configs || []).forEach(function(c){
                    var copy = {};
                    for (var k in c) copy[k] = c[k];
                    if (copy.persistentState === 'required') { copy.persistentState = 'optional'; changed = true; }
                    if (copy.sessionTypes && copy.sessionTypes.join(',') !== 'temporary') {
                      copy.sessionTypes = ['temporary'];
                      changed = true;
                    }
                    relaxed.push(copy);
                  });
                } catch (e) { throw err; }
                if (!changed) throw err;
                console.error('[rustify:' + pg() + '] EME retried without persistent state (was ' +
                              ((err && err.name) || '?') + ')');
                return orig(keySystem, relaxed);
              });
            };
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Routes what `console.error` never sees into the same pipe.
     *
     * A bundled application catches most of its own failures, and an unhandled promise rejection is
     * not guaranteed to reach [WebChromeClient.onConsoleMessage] on every WebView build. Both are
     * re-emitted as console errors here, tagged, so the one channel that is being read carries them.
     *
     * Naming what failed is the whole job, and the first version did not. Two things get thrown that
     * are not `Error`s and reduce to nothing useful when concatenated:
     *
     *  - A capture-phase `error` listener on `window` also receives the **resource** load failures
     *    that bubble up from elements. Those carry no message at all — the previous version printed
     *    them as the bare word "error" — but they do carry the URL that would not load, which is the
     *    only part worth having.
     *  - A promise rejected with `undefined`, or with a plain object, printed as "unknown". Reported
     *    by shape now, so at least its type and fields survive.
     *
     * Every message carries the page it came from — see [JS_PAGE_TAG].
     */
    private const val JS_ERROR_HOOK = """
        (function(){
          try {
            if (window.__rustifyErrHook) return 'already';
            window.__rustifyErrHook = true;
            function cut(v, n){ try { return String(v).slice(0, n); } catch (x) { return '?'; } }
            $JS_PAGE_TAG
            window.addEventListener('error', function(e){
              try {
                var t = e && e.target;
                if (t && t !== window && (t.src || t.href)) {
                  console.error('[rustify:' + pg() + '] resource failed: ' + (t.tagName || '?') + ' ' +
                                cut(t.src || t.href, 120));
                  return;
                }
                var m = (e && (e.message || (e.error && e.error.message))) || 'no message';
                console.error('[rustify:' + pg() + '] uncaught: ' + cut(m, 160) +
                              (e && e.filename ? ' @ ' + cut(e.filename, 60) : ''));
              } catch (x) {}
            }, true);
            window.addEventListener('unhandledrejection', function(e){
              try {
                var r = e ? e.reason : undefined;
                var d;
                if (r === undefined) d = 'undefined';
                else if (r === null) d = 'null';
                else if (r.message) d = (r.name ? r.name + ': ' : '') + cut(r.message, 160);
                else if (typeof r === 'object') {
                  try { d = cut(JSON.stringify(r), 160); }
                  catch (y) { d = Object.prototype.toString.call(r); }
                } else d = cut(r, 160);
                var at = (r && r.stack) ? cut(String(r.stack).split('\n')[1], 80) : '';
                console.error('[rustify:' + pg() + '] rejected: ' + d + (at ? ' @' + at : ''));
              } catch (x) {}
            });
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Makes the rest of the browser agree with the user agent string, for the desktop page only.
     *
     * `WebSettings.userAgentString` rewrites one header and nothing else. Everything else a page can
     * ask still answers Android: `navigator.userAgentData.mobile` is true, its platform is "Android",
     * `navigator.platform` is a Linux string and `maxTouchPoints` is not zero. A page that trusts the
     * header gets the desktop bundle; a page that then asks the client hints — which is what a modern
     * player does — gets told it is on a phone after the desktop code has already been chosen. The
     * halves disagree, and the half that builds the player is the one that loses.
     *
     * This is the difference between our WebView and the Firefox that works on the same device with
     * "request desktop site": Gecko does not implement client hints at all, so there is nothing there
     * to contradict the header.
     *
     * Only the low-entropy surface is covered — it is what feature detection reads, and the request
     * headers (`Sec-CH-UA-Mobile`, `Sec-CH-UA-Platform`) cannot be reached from here at all.
     */
    private const val JS_DESKTOP_SHIM = """
        (function(){
          try {
            if (window.__rustifyDesktopShim) return 'already';
            window.__rustifyDesktopShim = true;
            function def(obj, name, value){
              try {
                Object.defineProperty(obj, name, { get: function(){ return value; }, configurable: true });
              } catch (e) {}
            }
            def(navigator, 'platform', 'Win32');
            def(navigator, 'maxTouchPoints', 0);
            def(navigator, 'vendor', 'Google Inc.');
            var brands = (navigator.userAgentData && navigator.userAgentData.brands) || [];
            def(navigator, 'userAgentData', {
              brands: brands,
              mobile: false,
              platform: 'Windows',
              getHighEntropyValues: function(){
                return Promise.resolve({
                  architecture: 'x86', bitness: '64', brands: brands, fullVersionList: brands,
                  mobile: false, model: '', platform: 'Windows', platformVersion: '10.0.0'
                });
              },
              toJSON: function(){ return { brands: brands, mobile: false, platform: 'Windows' }; }
            });
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Counts the three things a page must do before it can make a sound, so that "no media element"
     * can say *how far it got*.
     *
     * Every diagnosis so far has run out of road at the same place: every check passes, no player is
     * built, and the only unexplained line is a promise rejected with `undefined` from a minified
     * vendor chunk. That message names nothing. These counters do — a page that never constructed a
     * media element, never opened a `MediaSource` and never asked for a key system failed somewhere
     * well before playback, and one that did all three and still has no element failed at the end.
     * Opposite investigations, and until now nothing separated them.
     *
     * `Audio` and `MediaSource` are wrapped as well as `document.createElement`, because `new Audio()`
     * does not go through the document, and constructing a `MediaSource` is the step that actually
     * commits to streaming (unlike `isTypeSupported`, which anyone can ask). The wrappers keep the
     * original prototypes and statics, so `instanceof` and feature detection are unaffected.
     *
     * Desktop only. The phone page works, and instrumenting a working path with this much monkey
     * patching risks breaking the one mode that plays.
     */
    private const val JS_BUILD_PROBE = """
        (function(){
          try {
            if (window.__rustifyBuildProbe) return 'already';
            window.__rustifyBuildProbe = true;
            window.__rustifyMade = 0;
            window.__rustifyMse = 0;
            // The elements themselves, so the report can say what state they were left in. Kept out
            // of __rmMedia deliberately: a created, silent element must never be mistaken for
            // playback, which is the failure that reads as success.
            window.__rustifyMadeEls = [];
            function keep(el){
              try { if (window.__rustifyMadeEls.length < 3) window.__rustifyMadeEls.push(el); } catch (e) {}
            }
            // Never reset: JS_EME_RELAX may have counted before this ran.
            window.__rustifyEmeCalls = window.__rustifyEmeCalls || 0;
            var create = document.createElement;
            document.createElement = function(tag){
              var el = create.apply(document, arguments);
              try {
                var t = String(tag).toLowerCase();
                if (t === 'audio' || t === 'video') { window.__rustifyMade++; keep(el); }
              } catch (e) {}
              return el;
            };
            var A = window.Audio;
            if (A) {
              var W = function(src){
                var el = src === undefined ? new A() : new A(src);
                try { window.__rustifyMade++; keep(el); } catch (e) {}
                return el;
              };
              W.prototype = A.prototype;
              window.Audio = W;
            }
            var MS = window.MediaSource;
            if (MS) {
              var M = function(){
                var ms = new MS();
                // Kept, not just counted. Its own readyState is the difference between a source that
                // was never attached to the element and one that was and got no data.
                try { window.__rustifyMse++; window.__rustifyMsObj = ms; } catch (e) {}
                return ms;
              };
              M.prototype = MS.prototype;
              M.isTypeSupported = function(t){ return MS.isTypeSupported(t); };
              window.MediaSource = M;
            }
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Tells the page it is on screen, and gives it frames, because it is neither.
     *
     * This WebView is **deliberately never attached to a window** — that is what lets audio survive
     * leaving the screen (see [mainHandler]) — and a document with nowhere to draw is a hidden
     * document. Two consequences, and the second one is not cosmetic:
     *
     *  - `document.visibilityState` reads `hidden`, and a player that waits to be seen before doing
     *    anything waits forever.
     *  - **`requestAnimationFrame` never fires.** Frames are driven by the compositor, and a hidden
     *    document has no frames. Anything a page sequences through rAF simply stops — not with an
     *    error, not slowly, just never — which is precisely the shape of this failure: a player built
     *    completely (element, media source, seven EME negotiations) and then nothing.
     *
     * Lying about the property does not bring the frames back; those come from the compositor, which
     * is not reading our getter. So rAF is re-implemented on a timer, and only when the document
     * really was hidden when this ran — the real value is recorded first, and reported by the
     * self-test, so this can be told apart from a page that was visible and stalled anyway.
     *
     * **Measured, and it was not this**: `vis:visible raf:yes`. A detached WebView still reports a
     * visible document and still gets frames, so neither branch of the argument above applies here and
     * the timer path never installs. Kept for the measurement, which is the part that earned its place
     * — it is now one line on the report instead of a plausible story nobody had checked.
     *
     * Negative ids for the timer-backed callbacks, so `cancelAnimationFrame` can route each id back to
     * whichever mechanism issued it and the two can never collide.
     *
     * Desktop only, for the same reason as [JS_BUILD_PROBE]: the phone page plays, and it plays
     * detached, so it does not need this and must not be risked by it.
     */
    private const val JS_VISIBLE_SHIM = """
        (function(){
          try {
            if (window.__rustifyVisShim) return 'already';
            window.__rustifyVisShim = true;
            // Recorded before anything is changed - it is the measurement this whole shim rests on.
            window.__rustifyVis = document.visibilityState;
            window.__rustifyRaf = 'pending';
            try {
              window.requestAnimationFrame(function(){
                if (window.__rustifyRaf === 'pending') window.__rustifyRaf = 'yes';
              });
              setTimeout(function(){
                if (window.__rustifyRaf === 'pending') window.__rustifyRaf = 'no';
              }, 600);
            } catch (e) {}
            function def(name, value){
              try {
                Object.defineProperty(document, name, {
                  get: function(){ return value; }, configurable: true
                });
              } catch (e) {}
            }
            def('visibilityState', 'visible');
            def('hidden', false);
            def('webkitVisibilityState', 'visible');
            def('webkitHidden', false);
            var stalled = window.__rustifyVis !== 'visible';
            if (stalled && window.requestAnimationFrame) {
              var raf = window.requestAnimationFrame.bind(window);
              var caf = window.cancelAnimationFrame ? window.cancelAnimationFrame.bind(window) : null;
              var timers = {}, next = 1;
              window.requestAnimationFrame = function(cb){
                var id = next++;
                timers[id] = setTimeout(function(){
                  delete timers[id];
                  try { cb(performance.now()); } catch (e) {}
                }, 16);
                return -id;
              };
              window.cancelAnimationFrame = function(id){
                if (id < 0) { clearTimeout(timers[-id]); delete timers[-id]; return; }
                if (caf) return caf(id);
              };
            }
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /**
     * Names the request that dies, for a page whose own error reporting says only `undefined`.
     *
     * The player is a network application before it is an audio one: it fetches a token, resolves the
     * hosts it should talk to, opens a websocket to be reachable as a device, and registers itself as
     * one. Any of those failing leaves it sitting there with nothing to play through — which is what
     * we see — and none of them announce themselves. One already showed up by accident, *Failed to
     * resolve endpoints via x-resolve. Using fallbacks!*, and there was no way to tell whether the
     * fallbacks then worked.
     *
     * Only Spotify's own hosts are reported, and only failures. Everything else on that page is
     * advertising, consent and telemetry, and it drowns the interesting line — the self-test shows a
     * handful of errors, so what goes in them has to be worth the space. Websockets are reported
     * whatever the host, since there are only a couple and a closed one is never routine.
     */
    private const val JS_NET_HOOK = """
        (function(){
          try {
            if (window.__rustifyNetHook) return 'already';
            window.__rustifyNetHook = true;
            function cut(v, n){ try { return String(v).slice(0, n); } catch (x) { return '?'; } }
            $JS_PAGE_TAG
            function loc(u){
              try { var a = document.createElement('a'); a.href = u; return a; } catch (x) { return null; }
            }
            function tag(u){
              var a = loc(u);
              return a ? a.hostname + cut(a.pathname, 36) : cut(u, 56);
            }
            function ours(u){
              var a = loc(u);
              return !a || /spotify|scdn\.co|pscdn\.co/.test(a.hostname);
            }
            // Bound to window on purpose: a module calling bare `fetch()` in strict mode passes an
            // undefined receiver, and native fetch answers that with "Illegal invocation" — which
            // would break every request on the page instead of measuring them.
            var of = window.fetch && window.fetch.bind(window);
            if (of) {
              // Kept reachable so the reachability probe does not report itself as page traffic.
              window.__rustifyFetchOrig = of;
              window.fetch = function(input){
                var u = (input && input.url) || input;
                return of.apply(null, arguments).then(function(r){
                  try {
                    if (r && !r.ok && ours(u)) console.error('[rustify:' + pg() + '] http ' + r.status + ' ' + tag(u));
                  } catch (x) {}
                  return r;
                }, function(e){
                  try {
                    if (ours(u)) console.error('[rustify:' + pg() + '] fetch failed: ' + tag(u) +
                                               ' - ' + cut((e && e.message) || e, 60));
                  } catch (x) {}
                  throw e;
                });
              };
            }
            var XP = window.XMLHttpRequest && XMLHttpRequest.prototype;
            if (XP && XP.open && XP.send) {
              var open = XP.open, send = XP.send;
              XP.open = function(method, url){
                try { this.__rustifyUrl = url; } catch (e) {}
                return open.apply(this, arguments);
              };
              XP.send = function(){
                var self = this;
                try {
                  self.addEventListener('error', function(){
                    if (ours(self.__rustifyUrl)) console.error('[rustify:' + pg() + '] xhr failed: ' + tag(self.__rustifyUrl));
                  });
                  self.addEventListener('load', function(){
                    if (self.status >= 400 && ours(self.__rustifyUrl)) {
                      console.error('[rustify:' + pg() + '] xhr ' + self.status + ' ' + tag(self.__rustifyUrl));
                    }
                  });
                } catch (e) {}
                return send.apply(this, arguments);
              };
            }
            var OW = window.WebSocket;
            if (OW) {
              var S = function(url, protocols){
                var ws = protocols === undefined ? new OW(url) : new OW(url, protocols);
                try {
                  var opened = false, msgs = 0, at = Date.now();
                  ws.addEventListener('open', function(){ opened = true; at = Date.now(); });
                  ws.addEventListener('message', function(){ msgs++; });
                  ws.addEventListener('error', function(){
                    console.error('[rustify:' + pg() + '] ws error: ' + tag(url));
                  });
                  ws.addEventListener('close', function(e){
                    // 1000 is a normal close. Anything else, on a socket the player needs to stay
                    // reachable through, is the failure rather than the cleanup.
                    //
                    // The code alone was not enough: a 4000 is application-chosen, which already says
                    // the handshake succeeded and the server hung up on purpose. Whether it ever
                    // opened, how many messages arrived first and how long it lasted are what say
                    // *why* - a socket closed before its first message never delivered the connection
                    // id the player needs, and one that dies after 30 idle seconds is a timeout.
                    if (!e || e.code !== 1000) {
                      console.error('[rustify:' + pg() + '] ws closed ' + ((e && e.code) || '?') + ' ' + tag(url) +
                                    (opened ? ' opened' : ' never opened') +
                                    ' msgs ' + msgs + ' after ' + (Date.now() - at) + 'ms' +
                                    (e && e.reason ? ' reason ' + cut(e.reason, 40) : ''));
                    }
                  });
                } catch (x) {}
                return ws;
              };
              S.prototype = OW.prototype;
              ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function(k){ S[k] = OW[k]; });
              window.WebSocket = S;
            }
            return 'installed';
          } catch (e) { return 'error'; }
        })();
    """

    /** Backs [mediaReport]. Declared here because it interpolates [JS_MEDIA_FN]. */
    private val JS_MEDIA_REPORT = """
        (function(){
          try {
            $JS_MEDIA_FN
            var inDom = document.querySelectorAll('video,audio').length;
            var m = __rmMedia();
            // How far it got, not just that it did not arrive — see [JS_BUILD_PROBE].
            if (!m) {
              var made = window.__rustifyMade === undefined ? '?' : window.__rustifyMade;
              var line = 'no media element (made ' + made +
                         ', mse ' + (window.__rustifyMse === undefined ? '?' : window.__rustifyMse) +
                         ', eme ' + (window.__rustifyEmeCalls || 0) + ')';
              // What the elements it *did* build were left holding.
              //
              // The previous version reported `nosrc` from `e.src` alone, and that was wrong in a way
              // that inverted the conclusion: MSE is attached either through `URL.createObjectURL` —
              // which does set `src` — or by assigning the MediaSource straight to `srcObject`, which
              // does not. An element fed the modern way reads as having no source at all.
              //
              // So: how it was attached, and then the four things that say where it stopped.
              // `networkState` separates "no source" (0) from "fetching" (2) and "nothing to fetch"
              // (3); `buffered.length` says whether any audio ever arrived; the MediaSource's own
              // readyState says whether it was ever attached (`open`) or is still `closed`; and the
              // source buffer count says whether the player got as far as asking for a format.
              var els = window.__rustifyMadeEls || [];
              for (var i = 0; i < els.length && i < 2; i++) {
                var e = els[i];
                var src = e.src ? 'src' : (e.srcObject ? 'srcobj' : 'nosrc');
                line += ' [rs' + e.readyState + ' net' + e.networkState + ' ' + src +
                        (e.error ? ' err' + e.error.code : '') +
                        ' buf' + ((e.buffered && e.buffered.length) || 0) +
                        (e.paused ? '' : ' running') + ']';
              }
              var ms = window.__rustifyMsObj;
              if (ms) {
                line += ' [ms:' + ms.readyState +
                        ' sb' + ((ms.sourceBuffers && ms.sourceBuffers.length) || 0) + ']';
              }
              // Whether the page was even considered on screen, and whether it was getting frames.
              // Both measured before [JS_VISIBLE_SHIM] changed either.
              if (window.__rustifyVis) {
                line += ' vis:' + window.__rustifyVis + ' raf:' + (window.__rustifyRaf || '?');
              }
              // Whether a consent overlay was in the way, and how much of it there was.
              if (window.__rustifyConsentRemoved) line += ' consent:' + window.__rustifyConsentRemoved;
              return line;
            }
            function t(v){ return isFinite(v) ? Math.round(v) + 's' : '?'; }
            return (m.paused ? 'paused' : 'playing') + ' ' + t(m.currentTime) + '/' + t(m.duration) +
                   ' · ' + (m.muted ? 'muted' : 'vol ' + m.volume) +
                   ' · ' + inDom + ' in DOM' + (document.contains(m) ? '' : ' · detached');
          } catch (e) { return 'error'; }
        })();
    """

    private val JS_PLAY = """
        (function(){
          $JS_MEDIA_FN
          var m = __rmMedia();
          if (m && m.paused) { m.play().catch(function(){}); return; }
          var b = document.querySelector('[data-testid="control-button-playpause"]');
          if (b) b.click();
        })();
    """

    private val JS_PAUSE = """
        (function(){
          $JS_MEDIA_FN
          var m = __rmMedia();
          if (m && !m.paused) m.pause();
        })();
    """

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
    private val JS_AUTOPLAY = """
        (function(){
          try {
            $JS_MEDIA_FN
            var m = __rmMedia();
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
            if (desktopPage) {
                wv.evaluateJavascript(JS_DESKTOP_SHIM, null)
                wv.evaluateJavascript(JS_BUILD_PROBE, null)
                wv.evaluateJavascript(JS_VISIBLE_SHIM, null)
                wv.evaluateJavascript(JS_NET_HOOK, null)
                wv.evaluateJavascript(JS_DISMISS_CONSENT, null)
                wv.evaluateJavascript(JS_PROMISE_TRACE, null)
            }
            wv.evaluateJavascript(JS_ERROR_HOOK, null)
            wv.evaluateJavascript(JS_EME_RELAX, null)
            wv.evaluateJavascript(JS_MEDIA_HOOK, null)
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
    private val JS_READ_STATE = """
        (function(){
          try {
            $JS_MEDIA_FN
            var m = __rmMedia();
            var md = (navigator.mediaSession && navigator.mediaSession.metadata) || null;
            var art = '';
            if (md && md.artwork && md.artwork.length) { art = md.artwork[md.artwork.length-1].src || ''; }
            return JSON.stringify({
              available: !!m || !!md,
              local: !!m,
              playing: !!m && !m.paused && !m.ended,
              title: md ? (md.title || '') : '',
              artist: md ? (md.artist || '') : '',
              artwork: art,
              position: m ? (m.currentTime || 0) : 0,
              duration: (m && isFinite(m.duration)) ? m.duration : 0
            });
          } catch (e) { return JSON.stringify({available:false}); }
        })();
    """

    /**
     * What a blocked request answers. Shared with WebPlayerScreen.
     *
     * An empty body is not enough on its own. A cross-origin `fetch()` answered without CORS headers
     * does not fail as "nothing came back" — it fails as a **security violation**, and the promise
     * rejects with a `TypeError` the caller never expected. Measured on the desktop page: blocking
     * Spotify's error sink produced *Access to fetch at 'https://…sentry.io/…' has been blocked*,
     * and an `Uncaught (in promise) undefined` from the bundle right behind it.
     *
     * A blocked tracker should look like a request that worked and returned nothing: an empty `200`
     * with permissive CORS headers. It stays blocked — nothing leaves the device — but the page's own
     * error handling gets an answer it knows how to deal with.
     *
     * **And the content type has to match what was asked for.** This answered everything as
     * `text/plain`, which for a `<script>` is not "empty", it is *refused*: Chromium will not execute
     * a script served with a non-executable MIME type, and the element fires **`error`**. Fourteen
     * rounds of measurement ended on that event —
     * `reject(undefined) @ … < HTMLScriptElement.onError (vendor~web-player…)` — with the player
     * abandoning its startup before it ever attached the MediaSource. The blocked script was
     * `cdn.cookielaw.org/otSDKStub.js`, in every log since the first one, dismissed as noise twice.
     *
     * So a blocked script is answered as empty JavaScript, a stylesheet as empty CSS, and an image as
     * a real 1×1 GIF — an empty body would fail to decode and fire `error` just the same. This is why
     * Firefox with uBlock Origin plays the same page while blocking the same script: uBO neutralises a
     * script by serving an empty *script*, not by making the load fail.
     */
    internal fun blockedResponse(mime: String = "text/plain"): WebResourceResponse {
        val body = if (mime == "image/gif") TRANSPARENT_GIF else ByteArray(0)
        val res = WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
        runCatching {
            res.setStatusCodeAndReasonPhrase(200, "OK")
            res.responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Cache-Control" to "no-store"
            )
        }
        return res
    }

    /**
     * The MIME type a blocked request should be answered with, from what it asked for.
     *
     * Anything not listed keeps `text/plain`: `fetch`/XHR callers read the body, not the header, and an
     * empty one is what they should see.
     */
    internal fun blockedMimeFor(resourceType: String): String = when (resourceType) {
        "script" -> "application/javascript"
        "stylesheet" -> "text/css"
        "image" -> "image/gif"
        else -> "text/plain"
    }

    /** Smallest valid GIF there is: 1×1, fully transparent. Decodes, so it fires `load`. */
    private val TRANSPARENT_GIF = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00,
        0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44,
        0x01, 0x00, 0x3B
    )

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
