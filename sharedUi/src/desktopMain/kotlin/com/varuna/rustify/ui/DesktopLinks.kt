// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopLinks.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.DesktopStores
import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.desktop_link_not_understood
import com.varuna.rustify.util.AppLinksHosts
import com.varuna.rustify.util.LinkTarget
import com.varuna.rustify.util.canonicalUrl
import com.varuna.rustify.util.deepLinkToken
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.net.URI
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The links the desktop is handed — on the command line, by a second launch ([SingleInstance]), by
 * macOS's "open this URL" — turned into the tokens [RustifyApp] routes on (`track:ID`), the same
 * `intentFlow` the phone's `MainActivity` feeds from its intents.
 *
 * Every one is read by the core's parser ([LinkTarget], `links::parse`), never by a pattern here.
 * `rustify://track/ID` is the one shape the core does not parse, because on the phone Android splits
 * the URI; here it is split by `java.net.URI` into the same host and first path segment, and the
 * target it names is then accepted only if its public URL reads back through the core as that same
 * target — so an id the core would not take is not taken here either.
 *
 * What arrives before the window is up waits in the channel: one collector, the root, takes each
 * token once.
 */
object DesktopLinks {
    private const val TAG = "DesktopLinks"

    /** Longer than any link Rustify makes or reads; a longer argument is not one. */
    private const val MAX_LINK = 2048

    private val tokens = Channel<String>(Channel.UNLIMITED)
    private val raises = Channel<Unit>(Channel.CONFLATED)

    /** For [RustifyApp]'s `intentFlow`. */
    val intents: Flow<String> = tokens.receiveAsFlow()

    /** Someone asked for the window: a second launch, a link, the Dock. */
    val raiseRequests: Flow<Unit> = raises.receiveAsFlow()

    /** Where to say that a link handed over is not one Rustify opens: the window's, once it is up. */
    @Volatile
    var toasts: AppPlatform? = null

    /**
     * Takes [raw] if it is a link Rustify opens; false (and a toast in [toasts]) when it is not.
     * Arguments that are options (`-psn_…`, which old macOS passes, or anything else with a dash)
     * are not links and are ignored without a word.
     */
    fun offer(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith("-")) return false
        val token = runCatching { tokenOf(text, wrapperHosts()) }
            .onFailure { Log.w(TAG, "could not read a link", it) }
            .getOrNull()
        if (token == null) {
            Log.w(TAG, "not a link Rustify opens: ${text.take(200)}")
            toasts?.toast(Res.string.desktop_link_not_understood)
            return false
        }
        tokens.trySend(token)
        return true
    }

    /** Brings the window up (and back from the tray). */
    fun raise() {
        raises.trySend(Unit)
    }

    /**
     * What macOS sends instead of a second launch: a `rustify://` link opened anywhere (the bundle
     * declares the scheme), and a click on the Dock icon while the window is in the tray. Installed
     * before the first window, so the link a cold start was for is not missed. Windows and Linux
     * support neither action and start a second process instead ([SingleInstance]).
     */
    fun installSystemHandlers() {
        runCatching {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler { event ->
                    offer(event.uri.toString())
                    raise()
                }
            }
            if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                desktop.addAppEventListener(AppReopenedListener { raise() })
            }
        }.onFailure { Log.w(TAG, "could not take links from the system", it) }
    }

    /** The user's own wrapper host, if one is set, and the ones the phone's manifest verifies. */
    private fun wrapperHosts(): List<String> {
        val own = DesktopStores.named(AppPlatform.SETTINGS).getString("rustify_wrapper_host")?.trim()
        return listOfNotNull(own?.takeIf { it.isNotEmpty() }) + AppLinksHosts.verifiedHosts
    }

    /**
     * The token for [text], or `null` when it is not a link Rustify opens. The same three readings
     * as `MainActivity.extractDeepLink`, in its order: the `rustify://` scheme, a Rustify wrapper on
     * one of [knownHosts], and any Spotify or YouTube Music link (or `spotify:track:ID` URI).
     */
    fun tokenOf(text: String, knownHosts: List<String>): String? {
        if (text.isBlank() || text.length > MAX_LINK) return null
        schemeTarget(text)?.let { return it.deepLinkToken }
        if (text.startsWith("https://", ignoreCase = true)) {
            LinkTarget.unwrap(text, knownHosts)?.let { payload -> return LinkTarget.parse(payload)?.deepLinkToken }
        }
        return LinkTarget.parse(text)?.deepLinkToken
    }

    private fun schemeTarget(text: String): LinkTarget? {
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("rustify", ignoreCase = true)) return null
        val id = uri.path.orEmpty().trim('/').substringBefore('/')
        if (id.isEmpty()) return null
        val candidate = when (uri.host?.lowercase()) {
            "track" -> LinkTarget.SpotifyTrack(id)
            "album" -> LinkTarget.SpotifyAlbum(id)
            "playlist" -> LinkTarget.SpotifyPlaylist(id)
            "artist" -> LinkTarget.SpotifyArtist(id)
            "ytmtrack" -> LinkTarget.YtmTrack(id)
            "ytmalbum" -> LinkTarget.YtmAlbum(id)
            "ytmartist" -> LinkTarget.YtmArtist(id)
            "ytmplaylist" -> LinkTarget.YtmPlaylist(id)
            else -> null
        } ?: return null
        return LinkTarget.parse(candidate.canonicalUrl)?.takeIf { it == candidate }
    }
}
