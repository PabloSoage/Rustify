package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.byteSize
import com.varuna.rustify.ui.deleteFile
import com.varuna.rustify.ui.fileExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/** Where the user's Subsonic server is (`20`). Kept in the sources' settings (`rustify_settings`). */
object SubsonicSettings {
    const val KEY_SERVER = "subsonic_server"
    const val KEY_USER = "subsonic_user"
    const val KEY_PASSWORD = "subsonic_password"

    @Serializable
    data class Config(val server: String = "", val user: String = "", val password: String = "")

    fun load(p: KeyValueStore): Config =
        Config(p.getString(KEY_SERVER).orEmpty(), p.getString(KEY_USER).orEmpty(), p.getString(KEY_PASSWORD).orEmpty())

    fun save(p: KeyValueStore, config: Config) {
        p.putString(KEY_SERVER, config.server.trim())
        p.putString(KEY_USER, config.user.trim())
        p.putString(KEY_PASSWORD, config.password)
    }

    fun isConfigured(config: Config) = config.server.isNotBlank() && config.user.isNotBlank()

    fun json(config: Config): String = RustifyJson.encodeToString(Config.serializer(), config)

    /** The server's name and version, or the reason it could not be reached. */
    suspend fun ping(config: Config): Result<String> {
        val answer = NativeEngine.subsonicPing(json(config))
        return runCatching { RustifyJson.decodeFromString(String.serializer(), answer) }
            .recoverCatching { error(Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(answer)?.groupValues?.get(1) ?: answer) }
    }
}

/**
 * The user's own Subsonic / OpenSubsonic server (Navidrome, Gonic, Airsonic…) as an audio source
 * (`20`, 5.x §4): a catalogue track the server has is played from the user's own file, at its own
 * quality, without YouTube. The core finds it (`subsonic::find`, the local matcher's rule) and builds
 * the authenticated stream URL. A track the server does not have answers "not available", so the
 * chain moves on quietly.
 */
class SubsonicAudioSource(private val env: AudioSourceEnvironment) : AudioSourceProvider {

    @Serializable
    private data class Found(val id: String, val streamUrl: String, val suffix: String = "", val bitRate: Int = 0)

    override val capabilities = AudioSourceCapabilities(
        id = ID,
        canStream = true,
        canDownload = true,
        requiresToken = true,
        // A server on the LAN answers in milliseconds; one over the internet may not.
        resolveTimeoutMs = 8_000,
        // It matches by title and artist: a chosen YouTube video means nothing to it.
        honoursYoutubeHint = false
    )

    override suspend fun isAvailableFor(track: FullTrack): Boolean =
        SubsonicSettings.isConfigured(SubsonicSettings.load(env.settings))

    private suspend fun find(track: FullTrack): Found? {
        val config = SubsonicSettings.load(env.settings)
        val artists = RustifyJson.encodeToString(ListSerializer(String.serializer()), track.artists.map { it.name })
        val answer = NativeEngine.subsonicFind(SubsonicSettings.json(config), track.name, artists, track.durationMs.toLong())
        return runCatching { RustifyJson.decodeFromString(Found.serializer().nullable, answer) }
            .getOrElse { throw IllegalStateException(answer.take(200)) }
    }

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val found = find(track) ?: error("not on the Subsonic server")
            Log.d(TAG, "${track.name}: ${found.id} (${found.suffix}, ${found.bitRate} kbps)")
            StreamInfo(uri = found.streamUrl, mimeType = mimeOf(found.suffix))
        }
    }

    override suspend fun downloadTo(track: FullTrack, dst: AppFile, onProgress: (Int) -> Unit): Result<AppFile> = withContext(Dispatchers.IO) {
        runCatching {
            val found = find(track) ?: error("not on the Subsonic server")
            if (dst.fileExists()) dst.deleteFile()
            AudioHttp.download(found.streamUrl, dst, onProgress)
            require(dst.fileExists() && dst.byteSize() > 0) { "download produced empty file" }
            dst
        }
    }

    private fun mimeOf(suffix: String): String? = when (suffix.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> null
    }

    companion object {
        const val ID = "subsonic"
        private const val TAG = "SubsonicAudioSource"
    }
}
