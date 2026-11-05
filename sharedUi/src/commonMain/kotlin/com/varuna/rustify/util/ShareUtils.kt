package com.varuna.rustify.util

import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.byteSize
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.deleteFile
import com.varuna.rustify.ui.listChildren
import com.varuna.rustify.ui.makeDirs
import com.varuna.rustify.ui.resources.*
import com.varuna.rustify.ui.toast
import com.varuna.rustify.ui.writeAllBytes
import com.varuna.rustify.bridge.maximiseThumbnail
import com.varuna.rustify.util.ShareUtils.shareRustifyLink
import com.varuna.rustify.util.ShareUtils.shareSpotifyLink
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.varuna.rustify.platform.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Robust sharing of Spotify entity links.
 *
 * The share sheet itself is the platform's ([AppPlatform.share]; Android's never lets a failed
 * `startActivity` take the app down). Reused by track / album / playlist / artist share actions.
 *
 * Two entry points:
 *  - [shareSpotifyLink] always shares the canonical `https://open.spotify.com/<type>/<id>` URL.
 *  - [shareRustifyLink] wraps the URL via [LinkTarget.wrap] (verified host or rustify:// fallback).
 *    The Settings toggle controls whether the UI shows the "Share as Rustify" button at all — this
 *    helper does NOT read prefs; the caller decides whether to call it.
 */
object ShareUtils {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Ten seconds to connect and fifteen between bytes, OkHttp's settings here before Ktor.
    private val http by lazy {
        httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    }

    /** @param type one of "track", "album", "playlist", "artist". */
    fun shareSpotifyLink(platform: AppPlatform, type: String, id: String) {
        if (id.isBlank()) {
            platform.toast(Res.string.share_no_link)
            return
        }
        val text = "https://open.spotify.com/$type/$id"
        shareText(platform, text)
    }

    /**
     * Shares the entity as a Rustify wrapper link.
     * Reads the wrapper host from [rustify_settings] only to pass it to [LinkTarget.wrap];
     * the host is NOT used to gate visibility — the caller decides that via the Settings toggle.
     *
     * When [title]/[imageUrl] are supplied the artwork is attached as a real image with the title,
     * artist and link as its caption. Chat apps build their link previews by crawling the URL for
     * Open Graph tags, and a Rustify wrapper link points at static hosting that cannot serve
     * per-track tags — so a shared Rustify link arrives bare. Sending the cover as an actual image
     * gives the recipient the same information without needing any server. Falls back to the plain
     * text link if there is no artwork or the download fails.
     */
    fun shareRustifyLink(
        platform: AppPlatform,
        type: String,
        id: String,
        title: String? = null,
        subtitle: String? = null,
        imageUrl: String? = null
    ) {
        if (id.isBlank()) {
            platform.toast(Res.string.share_no_link)
            return
        }
        val spotifyUrl = "https://open.spotify.com/$type/$id"
        val prefs = platform.prefs()
        val host = prefs.getString("rustify_wrapper_host")
        val link = LinkTarget.wrap(spotifyUrl, host)
        shareLinkWithArtwork(platform, link, title, subtitle, imageUrl)
    }

    /** Caption for an artwork share: "Title — Artist" (when known) above the link. */
    private fun caption(link: String, title: String?, subtitle: String?): String = buildString {
        if (!title.isNullOrBlank()) {
            append(title)
            if (!subtitle.isNullOrBlank()) append(" — ").append(subtitle)
            append('\n')
        }
        append(link)
    }

    /**
     * Shares [link] with its cover art attached, falling back to a plain text share when there is no
     * artwork or it can't be fetched. The download runs off the main thread.
     */
    private fun shareLinkWithArtwork(
        platform: AppPlatform,
        link: String,
        title: String?,
        subtitle: String?,
        imageUrl: String?
    ) {
        val text = caption(link, title, subtitle)
        if (imageUrl.isNullOrBlank()) {
            shareText(platform, text)
            return
        }
        scope.launch {
            val file = runCatching { downloadCover(platform.cacheDir, imageUrl) }.getOrNull()
            withContext(Dispatchers.Main) {
                // An image nothing would take degrades to the plain link.
                if (file == null || !platform.shareImage(file, text)) shareText(platform, text)
            }
        }
    }

    /**
     * Downloads the cover into `cacheDir/share/`, reusing a single slot. Null on any failure.
     *
     * The full-resolution variant is tried first and the URL as given is kept as a fallback, so a
     * rewrite that stops being valid degrades to the original image instead of to no image at all.
     */
    private suspend fun downloadCover(cacheDir: AppFile, imageUrl: String): AppFile? {
        val dir = cacheDir.child("share").apply { makeDirs() }
        dir.listChildren().forEach { runCatching { it.deleteFile() } }
        val out = dir.child("cover.jpg")
        for (candidate in listOf(highResCover(imageUrl), imageUrl).distinct()) {
            val ok = runCatching {
                val resp = http.get(candidate) { header(HttpHeaders.UserAgent, coverDownloadUserAgent()) }
                if (!resp.status.isSuccess()) return@runCatching false
                out.writeAllBytes(resp.body<ByteArray>())
                true
            }.getOrDefault(false)
            if (ok && out.byteSize() > 0) return out
        }
        return null
    }

    /**
     * Rewrites a cover URL to its full-resolution variant.
     *
     * Recipients see this image at full size, so a thumbnail that looks fine inside a list row looks
     * bad in a chat. Two families are handled:
     *
     *  - **Spotify's CDN** encodes the size of an album cover in the id prefix: `…0000b273` is the
     *    640px one, `…00001e02` is 300px and `…00004851` is 64px. Only that family is rewritten;
     *    artist and playlist images use different prefixes with no documented mapping.
     *  - **Google's** thumbnail hosts (YouTube Music) take the size in the URL, which
     *    [maximiseThumbnail] already normalises everywhere else in the app.
     */
    private fun highResCover(url: String): String {
        if (url.isBlank()) return url
        val spotifyLarge = "ab67616d0000b273"
        val spotifySmaller = listOf("ab67616d00001e02", "ab67616d00004851")
        spotifySmaller.forEach { small ->
            if (url.contains(small)) return url.replace(small, spotifyLarge)
        }
        return maximiseThumbnail(url)
    }

    /**
     * Shares a YouTube Music entity link. Mirror of [shareSpotifyLink] + [shareRustifyLink]:
     * if the "share as Rustify link" toggle is on it wraps [ytmUrl] via [LinkTarget.wrap]
     * (verified host or `rustify://ytm*` fallback); otherwise shares the canonical YTM URL as-is.
     *
     * Reads the same `share_as_rustify_link` pref the Spotify path uses, so behaviour is consistent
     * without every YTM screen having to touch prefs.
     *
     * @param ytmUrl canonical `https://music.youtube.com/...` URL of the entity.
     */
    fun shareYtmLink(platform: AppPlatform, ytmUrl: String) {
        if (ytmUrl.isBlank()) {
            platform.toast(Res.string.share_no_link)
            return
        }
        shareText(platform, ytmUrl)
    }

    /**
     * Shares an arbitrary URL (used for YouTube Music entities) as a Rustify wrapper link, with the
     * artwork attached when available. See [shareRustifyLink] for why the artwork is sent as an image.
     */
    fun shareRustifyUrl(
        platform: AppPlatform,
        url: String,
        title: String? = null,
        subtitle: String? = null,
        imageUrl: String? = null
    ) {
        if (url.isBlank()) {
            platform.toast(Res.string.share_no_link)
            return
        }
        val prefs = platform.prefs()
        val host = prefs.getString("rustify_wrapper_host")
        shareLinkWithArtwork(platform, LinkTarget.wrap(url, host), title, subtitle, imageUrl)
    }

    private fun shareText(platform: AppPlatform, text: String) {
        if (!platform.share(text)) platform.toast(Res.string.share_no_target)
    }
}
