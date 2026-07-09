package com.varuna.rustify.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.varuna.rustify.R

/**
 * Robust sharing of Spotify entity links.
 *
 * B4 fix: the previous inline share code (TrackOptionsMenu) only caught [android.content.ActivityNotFoundException].
 * When launched from a non-Activity context (e.g. the window of a Compose [androidx.compose.material3.ModalBottomSheet])
 * `startActivity` can throw [android.util.AndroidRuntimeException] ("...requires the FLAG_ACTIVITY_NEW_TASK flag"),
 * which escaped the narrow catch and crashed the app. This helper adds `FLAG_ACTIVITY_NEW_TASK` and catches any
 * exception so a share can never take the app down. Reused by track / album / playlist / artist share actions.
 *
 * Two entry points:
 *  - [shareSpotifyLink] always shares the canonical `https://open.spotify.com/<type>/<id>` URL.
 *  - [shareRustifyLink] wraps the URL via [RustifyWrapperLink.wrap] (verified host or rustify:// fallback).
 *    The Settings toggle controls whether the UI shows the "Share as Rustify" button at all — this
 *    helper does NOT read prefs; the caller decides whether to call it.
 */
object ShareUtils {

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
     */
    fun shareRustifyLink(context: Context, type: String, id: String) {
        if (id.isBlank()) {
            Toast.makeText(context, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        val spotifyUrl = "https://open.spotify.com/$type/$id"
        val prefs = context.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
        val host = prefs.getString("rustify_wrapper_host", null)
        val text = RustifyWrapperLink.wrap(spotifyUrl, host)
        shareText(context, text)
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
