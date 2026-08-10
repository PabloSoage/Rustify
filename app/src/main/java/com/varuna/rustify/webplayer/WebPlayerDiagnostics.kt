package com.varuna.rustify.webplayer

import android.content.Context
import android.webkit.CookieManager
import com.varuna.rustify.R
import kotlinx.coroutines.delay

/**
 * Self-test for the web player, exposed from Settings.
 *
 * Whether Spotify's page can act as Rustify's playback engine depends on several things that fail
 * independently and, on their own, look identical from the outside ("it just doesn't play"): the
 * WebView, the filter lists, the Spotify session, the DRM stack, and finally the page actually
 * producing audio. This walks them in order and reports each one, so a failure points at what to fix
 * instead of leaving the mode to be turned on and hoped for.
 *
 * The last step really does start playback — that is the only honest way to test it — and pauses
 * again when it is done.
 */
object WebPlayerDiagnostics {

    /** One checked condition. [detail] is technical (user agent, error name) and stays untranslated. */
    data class Step(val labelRes: Int, val ok: Boolean, val detail: String = "")

    /** Spotify ids are 22 base62 characters; anything else is a local or YouTube Music track. */
    private val SPOTIFY_ID = Regex("^[A-Za-z0-9]{22}$")

    private const val PLAYBACK_TIMEOUT_MS = 20_000L
    private const val DRM_TIMEOUT_MS = 4_000L

    /**
     * Runs the whole sequence. [trackId] is the track to try — normally whatever is playing; when it
     * is missing or not a Spotify id the user's own Liked Songs page is used instead, which needs no
     * hardcoded track and exists for every account.
     *
     * Must be called from the main dispatcher: creating a WebView is a main-thread operation.
     */
    suspend fun run(context: Context, trackId: String?): List<Step> {
        val steps = mutableListOf<Step>()

        // 1. A WebView that runs our JavaScript. Everything else builds on this.
        val webView = runCatching { WebPlayerController.getOrCreate(context) }.getOrNull()
        if (webView == null) {
            steps += Step(R.string.wp_test_step_webview, false, "WebView unavailable")
            return steps
        }
        WebPlayerController.loadHomeIfNeeded()
        // Wait for a real, secure document before asking it anything. `loadUrl` is asynchronous, and
        // the blank page a WebView starts on already reports itself as loaded — see awaitPageReady.
        val pageReady = WebPlayerController.awaitPageReady()
        val echo = if (pageReady) {
            WebPlayerController.eval("(function(){return 'ok';})();", timeoutMs = 8_000)
        } else null
        val userAgent = runCatching { webView.settings.userAgentString }.getOrDefault("")
        // The address is the single most useful thing to report: a step that fails on about:blank
        // means something very different from one that fails on the real player.
        val url = WebPlayerController.currentUrl().orEmpty().ifBlank { "no page" }
        steps += Step(
            R.string.wp_test_step_webview,
            echo == "ok",
            "${if (userAgent.contains("Windows")) "desktop UA" else "mobile UA"} · ${url.take(60)}"
        )
        // 2. Filtering. Not fatal — playback works unfiltered, there are just ads.
        val filters = runCatching { AdblockFilters.ensureLoaded(context) }.getOrDefault(false)
        steps += Step(R.string.wp_test_step_filters, filters)

        // 3. The session cookie lives in the WebView's own cookie store, not the app's HTTP client.
        val cookies = runCatching {
            CookieManager.getInstance().getCookie(WebPlayerController.SPOTIFY_WEB)
        }.getOrNull().orEmpty()
        steps += Step(R.string.wp_test_step_session, cookies.contains("sp_dc"))

        // Steps 1–3 stand on their own; everything below has to interrogate the page, so without a
        // working one it would report three failures for a single cause.
        if (echo != "ok") return steps

        // 4. Widevine. This is the one that silently kills the embed: without the protected-media
        //    permission the page dies with "no supported keysystem", and the request itself is what
        //    exercises the permission callback, so it tests the real path rather than a capability
        //    flag.
        val drm = checkDrm()
        steps += Step(R.string.wp_test_step_drm, drm == "ok", if (drm == "ok") "" else drm)

        // 5. Actual audio. The only step that proves the mode is usable.
        val target = if (trackId != null && SPOTIFY_ID.matches(trackId)) {
            "${WebPlayerController.SPOTIFY_WEB}track/$trackId"
        } else {
            "${WebPlayerController.SPOTIFY_WEB}collection/tracks"
        }
        WebPlayerController.playSpotifyUrl(target)
        val playing = WebPlayerController.awaitPlaybackStart(PLAYBACK_TIMEOUT_MS)
        val heard = WebPlayerController.state.value.title
        WebPlayerController.pause()
        // On failure the page it ended up on says more than an empty label: a login wall, a country
        // block and a genuine playback refusal all look identical otherwise.
        val detail = if (playing) heard
                     else WebPlayerController.currentUrl().orEmpty().take(60)
        steps += Step(R.string.wp_test_step_playback, playing, detail)

        return steps
    }

    /**
     * `requestMediaKeySystemAccess` is a promise, and `evaluateJavascript` cannot await one, so the
     * request is kicked off into a global and then polled.
     *
     * Returns the raw outcome rather than a boolean — `"ok"`, `"unsupported"`, `"insecure context"`
     * or `"error:<DOMException name>"` — because those mean completely different things and the
     * difference is the whole value of running the check.
     */
    private suspend fun checkDrm(): String {
        WebPlayerController.eval(JS_DRM_REQUEST) ?: return "no answer"
        val deadline = System.currentTimeMillis() + DRM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            when (val status = WebPlayerController.eval("window.__rustifyDrm || 'pending';", timeoutMs = 2_000)) {
                null, "pending" -> {}
                else -> return status
            }
        }
        return "timed out"
    }

    private val JS_DRM_REQUEST = """
        (function(){
          try {
            window.__rustifyDrm = 'pending';
            if (!window.isSecureContext) { window.__rustifyDrm = 'insecure context'; return 'insecure'; }
            if (!navigator.requestMediaKeySystemAccess) { window.__rustifyDrm = 'unsupported'; return 'unsupported'; }
            navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
              initDataTypes: ['cenc'],
              audioCapabilities: [{ contentType: 'audio/mp4;codecs="mp4a.40.2"' }]
            }]).then(function(){ window.__rustifyDrm = 'ok'; })
              .catch(function(e){ window.__rustifyDrm = 'error:' + ((e && e.name) || 'unknown'); });
            return 'pending';
          } catch (e) { window.__rustifyDrm = 'error'; return 'error'; }
        })();
    """
}
