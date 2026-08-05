package com.varuna.rustify.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.varuna.rustify.R
import com.varuna.rustify.util.ShareUtils.shareRustifyLink
import com.varuna.rustify.util.ShareUtils.shareSpotifyLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Robust sharing of Spotify entity links.
 *
 * When launched from a non-Activity context (e.g. the window of a Compose
 * [androidx.compose.material3.ModalBottomSheet]) `startActivity` can throw
 * [android.util.AndroidRuntimeException] ("...requires the FLAG_ACTIVITY_NEW_TASK flag"), and a
 * narrow catch on [android.content.ActivityNotFoundException] alone would let it crash the app.
 * This helper adds `FLAG_ACTIVITY_NEW_TASK` and catches any exception so a share can never take the
 * app down. Reused by track / album / playlist / artist share actions.
 *
 * Two entry points:
 *  - [shareSpotifyLink] always shares the canonical `https://open.spotify.com/<type>/<id>` URL.
 *  - [shareRustifyLink] wraps the URL via [RustifyWrapperLink.wrap] (verified host or rustify:// fallback).
 *    The Settings toggle controls whether the UI shows the "Share as Rustify" button at all — this
 *    helper does NOT read prefs; the caller decides whether to call it.
 */
object ShareUtils {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** @param type one of "track", "album", "playlist", "artist". */
    fun shareSpotifyLink(context: Context, type: String, id: String) {
        if (id.isBlank()) {
            Toast.makeText(context, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        val text = "https://open.spotify.com/$type/$id"
        shareText(context, text)
    }

    /**
     * Shares the entity as a Rustify wrapper link.
     * Reads the wrapper host from [rustify_settings] only to pass it to [RustifyWrapperLink.wrap];
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
        context: Context,
        type: String,
        id: String,
        title: String? = null,
        subtitle: String? = null,
        imageUrl: String? = null
    ) {
        if (id.isBlank()) {
            Toast.makeText(context, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        val spotifyUrl = "https://open.spotify.com/$type/$id"
        val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
        val host = prefs.getString("rustify_wrapper_host", null)
        val link = RustifyWrapperLink.wrap(spotifyUrl, host)
        shareLinkWithArtwork(context, link, title, subtitle, imageUrl)
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
        context: Context,
        link: String,
        title: String?,
        subtitle: String?,
        imageUrl: String?
    ) {
        val text = caption(link, title, subtitle)
        if (imageUrl.isNullOrBlank()) {
            shareText(context, text)
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            val file = runCatching { downloadCover(appContext, imageUrl) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (file != null) shareImage(appContext, file, text) else shareText(appContext, text)
            }
        }
    }

    /** Downloads [imageUrl] into `cacheDir/share/`, reusing a single slot. Null on any failure. */
    private fun downloadCover(context: Context, imageUrl: String): File? {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(dir, "cover.jpg")
        val req = Request.Builder().url(imageUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            out.outputStream().use { dst -> body.byteStream().use { it.copyTo(dst) } }
        }
        return out.takeIf { it.length() > 0 }
    }

    private fun shareImage(context: Context, file: File, text: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                // Some targets read the payload from clipData rather than the extra.
                clipData = ClipData.newUri(context.contentResolver, "cover", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, context.getString(R.string.share_track))
                .apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Never let a share take the app down — degrade to the plain link.
            shareText(context, text)
        }
    }

    /**
     * Shares a YouTube Music entity link. Mirror of [shareSpotifyLink] + [shareRustifyLink]:
     * if the "share as Rustify link" toggle is on it wraps [ytmUrl] via [RustifyWrapperLink.wrap]
     * (verified host or `rustify://ytm*` fallback); otherwise shares the canonical YTM URL as-is.
     *
     * Reads the same `share_as_rustify_link` pref the Spotify path uses, so behaviour is consistent
     * without every YTM screen having to touch prefs.
     *
     * @param ytmUrl canonical `https://music.youtube.com/...` URL of the entity.
     */
    fun shareYtmLink(context: Context, ytmUrl: String) {
        if (ytmUrl.isBlank()) {
            Toast.makeText(context, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        shareText(context, ytmUrl)
    }

    /**
     * Shares an arbitrary URL (used for YouTube Music entities) as a Rustify wrapper link, with the
     * artwork attached when available. See [shareRustifyLink] for why the artwork is sent as an image.
     */
    fun shareRustifyUrl(
        context: Context,
        url: String,
        title: String? = null,
        subtitle: String? = null,
        imageUrl: String? = null
    ) {
        if (url.isBlank()) {
            Toast.makeText(context, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
        val host = prefs.getString("rustify_wrapper_host", null)
        shareLinkWithArtwork(context, RustifyWrapperLink.wrap(url, host), title, subtitle, imageUrl)
    }

    private fun shareText(context: Context, text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            this.type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(sendIntent, context.getString(R.string.share_track))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.share_no_target, Toast.LENGTH_SHORT).show()
        }
    }
}
