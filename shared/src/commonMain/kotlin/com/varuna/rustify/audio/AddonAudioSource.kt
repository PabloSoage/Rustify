package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.InstalledAddon
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.TrackRef
import com.varuna.rustify.bridge.optBoolean
import com.varuna.rustify.bridge.optLong
import com.varuna.rustify.bridge.optString
import com.varuna.rustify.bridge.parseJsonObject
import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.byteSize
import com.varuna.rustify.ui.deleteFile
import com.varuna.rustify.ui.fileExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
 *
 * The request itself is the core's (`resolveViaAddon`), which resolves the add-on's host, refuses
 * anything private and pins the connection to what it approved, on every platform alike.
 */
class AddonAudioSource(
    private val addon: InstalledAddon
) : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = providerIdFor(addon.id),
        // Addon names are arbitrary text from a third party, so they cannot be a string resource.
        // The Settings list shows [addonName] next to the generic label for add-ons.
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
                val url = answer.optString("url")
                StreamInfo(
                    uri = url,
                    expiresAtMs = answer.optLong("expiresAtMs", 0L).takeIf { it > 0L },
                    mimeType = answer.optString("mime").takeIf { it.isNotBlank() },
                    // An add-on stream is cacheable like any other: the security checks already ran
                    // on this URL when the answer arrived, and what is stored is only bytes.
                    cache = if (url.startsWith("https://")) CacheHint(upstreamUrl = url) else null
                )
            }
        }

    override suspend fun downloadTo(
        track: FullTrack,
        dst: AppFile,
        onProgress: (Int) -> Unit
    ): Result<AppFile> = withContext(Dispatchers.IO) {
        runCatching {
            val info = resolveStreamUrl(track, null).getOrThrow()
            if (dst.fileExists()) dst.deleteFile()
            AudioHttp.download(info.uri, dst, onProgress)
            require(dst.fileExists() && dst.byteSize() > 0) { "addon download produced empty file" }
            dst
        }
    }

    /**
     * Returns null when this addon should not be asked at all — a kind it does not declare, a local
     * file, or a track with nothing to match on.
     */
    private fun queryFor(track: FullTrack): JsonObject? {
        val ref = TrackRef.parse(track.id) ?: return null
        val kind = when (ref) {
            is TrackRef.Spotify -> "spotify"
            is TrackRef.Ytm -> "ytm"
            // Never sent: see the class comment.
            is TrackRef.Local, is TrackRef.SpotifyLocal -> return null
        }
        if (!addon.handlesKind(kind)) return null
        if (ref.raw.isBlank() && track.isrc.isBlank()) return null

        return buildJsonObject {
            put("kind", kind)
            put("id", if (ref is TrackRef.Ytm) ref.videoId else ref.raw)
            put("title", track.name)
            put("artists", JsonArray(track.artists.map { JsonPrimitive(it.name) }))
            put("duration_ms", track.durationMs)
            put("isrc", track.isrc)
        }
    }

    /** Returns the answer object, or null when the addon simply does not have the track. */
    private suspend fun ask(query: JsonObject): JsonObject? {
        val raw = NativeEngine.resolveViaAddon(addon.id, query.toString())
        val json = parseJsonObject(raw) ?: return null
        if (json.containsKey("success") && !json.optBoolean("success")) {
            Log.w(TAG, "addon ${addon.id} failed: ${json.optString("error")}")
            error(json.optString("error", "addon failed"))
        }
        // `{}` is the protocol's "I do not have this", and must not read as an error.
        return json.takeIf { it.containsKey("url") }
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
