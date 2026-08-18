package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.InstalledAddon
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.TrackRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One installed addon, wearing the [AudioSourceProvider] interface.
 *
 * This is the whole reason point B fits: E60 already produced the right abstraction, so an addon
 * does not need a new mechanism — it needs to *be* a provider. [AudioSourceRegistry] puts one of
 * these in the list per installed addon and the chain, the fallback and the drag-and-drop ordering
 * in Settings all keep working untouched.
 *
 * What is sent to the addon is exactly the query built in [queryFor]: kind, id, title, artists,
 * duration and ISRC. No token, no cookie, no account id. A `local:` track is not sent at all — its
 * id is a `content://` URI describing someone's storage layout, and an addon could do nothing with
 * it anyway.
 */
class AddonAudioSource(
    private val appContext: Context,
    private val addon: InstalledAddon
) : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = providerIdFor(addon.id),
        // Addon names are arbitrary text from a third party, so they cannot be a string resource.
        // The registry shows [addon.name] next to this generic label.
        displayNameRes = R.string.backend_addon,
        canStream = addon.canStream,
        // Downloading is resolve-then-fetch over the same answer, so it needs nothing extra.
        canDownload = addon.canDownload,
        requiresToken = false,
        maxQualityKbps = null
    )

    /** The addon's own display name, for the Settings list. */
    val addonName: String get() = addon.name

    override suspend fun isAvailableFor(track: FullTrack): Boolean =
        addon.enabled && addon.canStream && queryFor(track) != null

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val query = queryFor(track) ?: error("this addon is not asked about ${track.id}")
                val answer = ask(query) ?: error("${addon.id} does not have this track")
                StreamInfo(
                    uri = answer.optString("url"),
                    expiresAtMs = answer.optLong("expiresAtMs", 0L).takeIf { it > 0L },
                    mimeType = answer.optString("mime").takeIf { it.isNotBlank() }
                )
            }
        }

    override suspend fun downloadTo(
        track: FullTrack,
        dst: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val info = resolveStreamUrl(track, null).getOrThrow()
            if (dst.exists()) dst.delete()
            AudioHttp.download(appContext, info.uri, dst, onProgress)
            require(dst.exists() && dst.length() > 0) { "addon download produced empty file" }
            dst
        }
    }

    /**
     * Returns null when this addon should not be asked at all — a kind it does not declare, a local
     * file, or a track with nothing to match on.
     */
    private fun queryFor(track: FullTrack): JSONObject? {
        val ref = TrackRef.parse(track.id) ?: return null
        val kind = when (ref) {
            is TrackRef.Spotify -> "spotify"
            is TrackRef.Ytm -> "ytm"
            // Never sent: see the class comment.
            is TrackRef.Local, is TrackRef.SpotifyLocal -> return null
        }
        if (!addon.handlesKind(kind)) return null
        if (ref.raw.isBlank() && track.isrc.isBlank()) return null

        return JSONObject().apply {
            put("kind", kind)
            put("id", if (ref is TrackRef.Ytm) ref.videoId else ref.raw)
            put("title", track.name)
            put("artists", JSONArray().also { array -> track.artists.forEach { array.put(it.name) } })
            put("duration_ms", track.durationMs)
            put("isrc", track.isrc)
        }
    }

    /** Returns the answer object, or null when the addon simply does not have the track. */
    private fun ask(query: JSONObject): JSONObject? {
        val raw = NativeEngine.resolveViaAddonNative(addon.id, query.toString())
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.has("success") && !json.optBoolean("success")) {
            Log.w(TAG, "addon ${addon.id} failed: ${json.optString("error")}")
            error(json.optString("error", "addon failed"))
        }
        // `{}` is the protocol's "I do not have this", and must not read as an error.
        return json.takeIf { it.has("url") }
    }

    companion object {
        private const val TAG = "AddonAudioSource"

        /** Namespaced so an addon id can never collide with a built-in provider id. */
        fun providerIdFor(addonId: String): String = "addon:$addonId"

        /** The addon id inside a provider id, or null if it is not one of ours. */
        fun addonIdOf(providerId: String): String? =
            providerId.takeIf { it.startsWith("addon:") }?.removePrefix("addon:")
    }
}
