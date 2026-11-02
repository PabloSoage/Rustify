package com.varuna.rustify.metrics

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Scrobbling to ListenBrainz (`18`): the app side of `core_engine/src/scrobble/`. The core queues
 * and sends, and decides what a listen is ([qualifies] mirrors `scrobble::qualifies`, so a play that
 * would not count is not even handed over); this holds the user's token and builds the listen from
 * the track. Off unless the user pastes a token in Settings.
 */
object ListenBrainz {
    private const val PREFS = "rustify_settings"
    const val PREF_TOKEN = "listenbrainz_token"
    const val PREF_USER = "listenbrainz_user"

    @Serializable
    data class Listen(
        val listenedAt: Long,
        val artist: String,
        val track: String,
        val release: String = "",
        val durationMs: Long = 0,
        val isrc: String = "",
    )

    @Serializable
    data class Report(val sent: Int = 0, val pending: Int = 0, val rejected: Int = 0)

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** ListenBrainz's guideline; the same rule as `scrobble::qualifies` in the core. */
    fun qualifies(listenedMs: Long, durationMs: Long): Boolean {
        if (durationMs in 1..30_000) return false
        return listenedMs >= 240_000 || (durationMs > 0 && listenedMs * 2 >= durationMs)
    }

    fun listenOf(track: FullTrack, startedAtMs: Long) = Listen(
        listenedAt = startedAtMs / 1000,
        artist = track.artists.joinToString(", ") { it.name },
        track = track.name,
        release = track.album?.name.orEmpty().takeUnless { it.startsWith("Unknown Album") }.orEmpty(),
        durationMs = track.durationMs.toLong(),
        isrc = track.isrc,
    )

    suspend fun submit(context: Context, listen: Listen): Report? {
        val token = token(context) ?: return null
        val answer = NativeEngine.listenBrainzSubmit(token, RustifyJson.encodeToString(Listen.serializer(), listen))
        return runCatching { RustifyJson.decodeFromString(Report.serializer(), answer) }.getOrNull()
            .also { android.util.Log.d("ListenBrainz", "submit: $it ${if (it == null) answer.take(200) else ""}") }
    }

    suspend fun playingNow(context: Context, listen: Listen) {
        val token = token(context) ?: return
        runCatching { NativeEngine.listenBrainzPlayingNow(token, RustifyJson.encodeToString(Listen.serializer(), listen)) }
    }

    /** The user [token] belongs to, or null when it is not valid or ListenBrainz cannot be reached. */
    suspend fun validate(token: String): String? = runCatching {
        RustifyJson.decodeFromString(String.serializer().nullable, NativeEngine.listenBrainzValidate(token.trim()))
    }.getOrNull()
}
