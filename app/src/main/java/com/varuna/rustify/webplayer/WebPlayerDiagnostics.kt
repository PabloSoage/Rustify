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

    /**
     * 20s was the budget before the Connect takeover started running. That step costs two evaluations
     * with two seconds of deliberate waiting between them, and it only fires once the page has had a
     * few polls to render — so what used to be the whole window is now the window minus its most
     * promising four seconds.
     */
    private const val PLAYBACK_TIMEOUT_MS = 30_000L
    /** Four configurations now, each instantiating a CDM, so the old 4s was not enough room. */
    private const val DRM_TIMEOUT_MS = 10_000L

    /** Five hosts, up to three attempts each, and a failing one waits on a connection that never comes. */
    private const val HOSTS_TIMEOUT_MS = 18_000L

    /** How long a successful test keeps playing, so the result can be confirmed by ear. */
    private const val AUDIBLE_PROOF_MS = 1_500L

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
        //    The step passes on the configuration any player needs at minimum; the rest of the matrix
        //    is reported either way, because "which of the four" is the question worth answering here.
        val drm = checkDrm()
        steps += Step(R.string.wp_test_step_drm, drm.contains("basic:ok"), drm)

        // 4b. Can this WebView actually talk to the hosts the player is made of? It can — `spcli:401`
        //     is a real answer from a real server. Kept because that is worth knowing on every run:
        //     it is what stops the next failure from being blamed on the network again, and it makes
        //     an actual outage on those hosts visible instead of arriving disguised as a broken page.
        val hosts = checkHosts()
        steps += Step(R.string.wp_test_step_hosts, !hosts.contains("TypeError"), hosts)

        // 5. Actual audio. The only step that proves the mode is usable.
        val target = if (trackId != null && SPOTIFY_ID.matches(trackId)) {
            "${WebPlayerController.SPOTIFY_WEB}track/$trackId"
        } else {
            "${WebPlayerController.SPOTIFY_WEB}collection/tracks"
        }
        // Only errors thrown from here on belong to this attempt; whatever the page complained about
        // while it was merely sitting there is somebody else's problem.
        WebPlayerController.clearPageErrors()
        WebPlayerController.playSpotifyUrl(target)
        val playing = WebPlayerController.awaitPlaybackStart(PLAYBACK_TIMEOUT_MS)
        val heard = WebPlayerController.state.value.title
        // Let it be heard. The watchdog returns the instant the position moves off zero, which on a
        // page that starts quickly is well under a second — so the test would pass and pause again
        // before any sound came out, and "it says OK but I hear nothing" is indistinguishable from a
        // false positive. A moment of audio is the part a person can actually verify.
        if (playing) delay(AUDIBLE_PROOF_MS)
        // Read the element before pausing, or the report describes what the pause did.
        val media = WebPlayerController.mediaReport()
        // And read the page before pausing too: a notice about playback can disappear the moment it
        // stops being true.
        val notice = if (playing) "" else WebPlayerController.pageNotice()
        WebPlayerController.pause()
        // On failure, the page it ended up on plus what pressing play actually did: a login wall, a
        // missing button and a page that was pressed and refused anyway (no Premium, region block)
        // all look identical otherwise, and only the last one means the mode is unusable.
        val detail = if (playing) {
            // On success too: passing is now decided from an element that may be detached, one of
            // several, or a silent placeholder, so the evidence goes next to the verdict.
            if (heard.isBlank()) media else "$heard · $media"
        } else buildString {
            append(WebPlayerController.lastPlayAttempt ?: "play never attempted")
            // Present only when the page turned out to be a remote for another device, which is the
            // one failure that looks exactly like success right up to the sound.
            WebPlayerController.lastDeviceTakeover?.let { append(" · takeover: $it") }
            append(" · ")
            append(media)
            // Which third parties we stopped during this attempt. The page cannot tell a request we
            // refused from one that failed on its own — both end at the same `resource failed` line —
            // and this side knows exactly which ones it stopped.
            append(" · blocked ")
            append(WebPlayerController.blockedSummary())
            // First, because it is the only line here written for a person to read, and if Spotify
            // names the reason it refused this then everything after it is detail.
            if (notice.isNotBlank()) append(" · says: ${notice.take(90)}")
            // A title without playback means the page loaded a *different* track — worth seeing,
            // because "started the wrong song" and "started nothing" need opposite fixes.
            if (heard.isNotBlank()) append(" · loaded \"${heard.take(24)}\"")
            append(" · ")
            append(WebPlayerController.currentUrl().orEmpty().take(48))
            // The page's own errors, last, because they are the long part — and the only part that
            // can say *why* nothing was built rather than that nothing was.
            //
            // All of them, not the first. The first one measured turned out to be a reCAPTCHA frame
            // failing to get storage access, which is noise on its own; picking one meant picking
            // wrong. The full text of every one of these also goes to the log (Settings → Logs),
            // which is where to read them when they do not fit here.
            val errors = WebPlayerController.pageErrors()
            if (errors.isNotEmpty()) {
                append(" · js(${errors.size}): ")
                append(errors.joinToString(" | "))
            }
        }
        steps += Step(R.string.wp_test_step_playback, playing, detail)

        return steps
    }

    /**
     * `requestMediaKeySystemAccess` is a promise, and `evaluateJavascript` cannot await one, so the
     * probes are kicked off into a global and then polled.
     *
     * Returns the raw outcome rather than a boolean — the differences are the whole value of running
     * it. `"unsupported"` and `"insecure context"` mean completely different things, and so does each
     * configuration in the matrix below.
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

    /** Same polling shape as [checkDrm]: several requests in flight, one global with the answer. */
    private suspend fun checkHosts(): String {
        WebPlayerController.eval(JS_HOST_REACH) ?: return "no answer"
        val deadline = System.currentTimeMillis() + HOSTS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            when (val status = WebPlayerController.eval("window.__rustifyReach || 'pending';", timeoutMs = 2_000)) {
                null, "pending" -> {}
                else -> return status
            }
        }
        return "timed out"
    }

    /**
     * Asks the three hosts the player needs, from inside the page, and reports what each one said.
     *
     * The measurement this exists for: the desktop page constructs a media element, opens a
     * `MediaSource` and negotiates EME seven times — it builds the entire player — and then
     * `POST gew1-spclient.spotify.com/track-playback/v1/devices` fails with `Failed to fetch`. That is
     * the call that registers the browser as a playback device; without it the player is complete,
     * armed, and never told to play. Which is exactly what "everything green and no sound" looks like.
     *
     * `Failed to fetch` is Chromium's message for *the request did not complete*, and it covers two
     * opposite situations. So each failure is asked again with `mode: 'no-cors'`, which is sent as a
     * plain request the page is not allowed to read:
     *
     *  - a plain retry without cookies answers → the host is fine and **credentials** were the
     *    problem, reported as `nocred <status>`;
     *  - the `no-cors` retry succeeds → the network reached the host and the **CORS layer** rejected
     *    the answer (headers, preflight), reported as `cors`;
     *  - that fails too → the request never got there at all, and this is a **connectivity** or
     *    host-resolution problem, reported by error name.
     *
     * A status code — any status code, including 401 or 404 — means the host is reachable and answering.
     * Measured: `spcli:401 apres:nocred 200`. So the network is not broken in general, and the first
     * version of this probe accusing CORS on `apresolve` was its own doing — it sent cookies to a
     * response that answers `Access-Control-Allow-Origin: *`, which is refused by rule.
     *
     * Two hosts were added after that, and they are the point of the probe now: **`api-partner`** and
     * **`spclient.wg`**, both of which the page cannot reach while `gew1-spclient` answers fine. The
     * second one serves the Widevine application certificate, which is on the playback path and fits
     * the element the page is left holding (`readyState 0`, no source). The first is the one the app's
     * own Rust client talks to all day over plain HTTP — so if the WebView cannot reach it, that is a
     * difference between our two network stacks and not between us and Spotify.
     *
     * The unwrapped `fetch` is used ([JS_NET_HOOK] keeps it), so the probe does not show up in the
     * page's own error list and get mistaken for the page failing again.
     */
    private val JS_HOST_REACH = """
        (function(){
          try {
            window.__rustifyReach = 'pending';
            var f = window.__rustifyFetchOrig || window.fetch.bind(window);
            var targets = [
              ['token',  'https://open.spotify.com/api/token?reason=transport&productType=web_player'],
              ['apres',  'https://apresolve.spotify.com/?type=dealer&type=spclient'],
              ['spcli',  'https://gew1-spclient.spotify.com/track-playback/v1/devices'],
              ['apart',  'https://api-partner.spotify.com/pathfinder/v2/query'],
              ['wglic',  'https://spclient.wg.spotify.com/widevine-license/v1/application-certificate']
            ];
            var out = [], left = targets.length;
            targets.forEach(function(t){
              f(t[1], { credentials: 'include' }).then(
                function(r){ out.push(t[0] + ':' + r.status); },
                function(){
                  // Without credentials first. A response that answers `Access-Control-Allow-Origin: *`
                  // is refused outright when the request carries cookies, so the attempt above can
                  // fail on a host that is perfectly reachable - which is what `apres:cors` turned out
                  // to be. `nocred` says the host is fine and the credentials were the problem.
                  return f(t[1]).then(
                    function(r){ out.push(t[0] + ':nocred ' + r.status); },
                    function(){
                      return f(t[1], { mode: 'no-cors' }).then(
                        function(){ out.push(t[0] + ':cors'); },
                        function(e){ out.push(t[0] + ':' + ((e && e.name) || '?')); }
                      );
                    }
                  );
                }
              ).catch(function(){ out.push(t[0] + ':threw'); })
               .then(function(){ if (--left === 0) window.__rustifyReach = out.join(' '); });
            });
            return 'pending';
          } catch (e) { window.__rustifyReach = 'error'; return 'error'; }
        })();
    """

    /**
     * Asks for Widevine four ways instead of one, and takes each answer one step further.
     *
     * The check this replaces asked for the most permissive configuration there is and stopped at
     * `requestMediaKeySystemAccess`, which only reports whether a configuration *could* be supported.
     * A real player asks for more than the minimum and then instantiates the CDM, and either of those
     * can fail on a device where the permissive question answers yes — which is exactly the shape of
     * the desktop page's failure: every check passing and no player ever built.
     *
     * So: the plain configuration, then the software robustness a streaming service actually asks
     * for, then persistent state, then persistent state *with a persistent-license session*, then a
     * distinctive identifier — and `createMediaKeys()` on each, which is the call that loads the CDM.
     * `MediaSource` is in there too because a page that cannot build a source buffer never builds a
     * media element either, and that also reads as "no media element".
     *
     * `pstate` and `plicense` are split because the first measurement asked for both at once, got
     * `NotSupportedError`, and could not say which half was refused — and the two have very different
     * consequences. Persistent state is storage the CDM keeps between sessions; a persistent license
     * is what offline playback needs and streaming does not.
     *
     * Split, it answered: `pstate:ok plicense:NotSupportedError`. So the one refusal is the session
     * type no streaming player asks for, four configurations pass, each instantiates a CDM — and
     * Widevine is **not** what stops the desktop page. The matrix stays as the record of that, and
     * because a regression here would otherwise be invisible.
     */
    private val JS_DRM_REQUEST = """
        (function(){
          try {
            window.__rustifyDrm = 'pending';
            if (!window.isSecureContext) { window.__rustifyDrm = 'insecure context'; return 'insecure'; }
            if (!navigator.requestMediaKeySystemAccess) { window.__rustifyDrm = 'unsupported'; return 'unsupported'; }
            var AUDIO = 'audio/mp4;codecs="mp4a.40.2"';
            var probes = [
              ['basic',   [{ initDataTypes: ['cenc'], audioCapabilities: [{ contentType: AUDIO }] }]],
              ['sw',      [{ initDataTypes: ['cenc'],
                             audioCapabilities: [{ contentType: AUDIO, robustness: 'SW_SECURE_CRYPTO' }] }]],
              ['pstate',  [{ initDataTypes: ['cenc'], persistentState: 'required',
                             audioCapabilities: [{ contentType: AUDIO }] }]],
              ['plicense',[{ initDataTypes: ['cenc'], persistentState: 'required',
                             sessionTypes: ['temporary', 'persistent-license'],
                             audioCapabilities: [{ contentType: AUDIO }] }]],
              ['distinct',[{ initDataTypes: ['cenc'], distinctiveIdentifier: 'required',
                             audioCapabilities: [{ contentType: AUDIO }] }]]
            ];
            var out = [];
            // aac/opus/mp3, in that order. One codec was not enough: the page picks the format it
            // streams from what the browser says it can take, and a missing one narrows it to a
            // format that may then fail for its own reasons.
            function mse(t){
              try {
                return (window.MediaSource && MediaSource.isTypeSupported &&
                        MediaSource.isTypeSupported(t)) ? 'ok' : 'no';
              } catch (e) { return 'err'; }
            }
            out.push('mse:' + mse('audio/mp4; codecs="mp4a.40.2"') + '/' +
                              mse('audio/webm; codecs="opus"') + '/' +
                              mse('audio/mpeg'));
            // Deliberately the unshimmed one: JS_EME_RELAX retries a refused request without
            // persistent state, which is the right thing for the page and the wrong thing for a
            // measurement of what the CDM actually supports.
            var ask = window.__rustifyEmeOrig ||
                      navigator.requestMediaKeySystemAccess.bind(navigator);
            var left = probes.length;
            probes.forEach(function(p){
              ask('com.widevine.alpha', p[1]).then(
                function(access){
                  return access.createMediaKeys().then(
                    function(){ out.push(p[0] + ':ok'); },
                    function(e){ out.push(p[0] + ':cdm ' + ((e && e.name) || '?')); }
                  );
                },
                function(e){ out.push(p[0] + ':' + ((e && e.name) || '?')); }
              ).catch(function(){ out.push(p[0] + ':threw'); })
               .then(function(){ if (--left === 0) window.__rustifyDrm = out.join(' '); });
            });
            return 'pending';
          } catch (e) { window.__rustifyDrm = 'error'; return 'error'; }
        })();
    """
}
