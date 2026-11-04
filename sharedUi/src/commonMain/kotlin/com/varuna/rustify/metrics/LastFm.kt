package com.varuna.rustify.metrics

import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.util.optString
import com.varuna.rustify.util.parseObject
import kotlinx.serialization.Serializable

/**
 * Scrobbling to Last.fm (`23` §3): the app side of `core_engine/src/scrobble/lastfm.rs`, beside
 * [ListenBrainz] and with the same listens and the same rule ([ListenBrainz.qualifies]).
 *
 * Rustify ships no Last.fm key: the user enters their own API key and secret, logs in once, and
 * only the session key is kept. Off until then.
 */
object LastFm {
    private const val PREFS = "rustify_settings"
    private const val PREF_KEY = "lastfm_api_key"
    private const val PREF_SECRET = "lastfm_secret"
    private const val PREF_SESSION = "lastfm_session"
    const val PREF_USER = "lastfm_user"

    @Serializable
    data class Credentials(val apiKey: String, val secret: String, val sessionKey: String)

    @Serializable
    data class Session(val name: String, val key: String)

    private fun prefs(platform: AppPlatform) = platform.prefs(PREFS)

    fun credentials(platform: AppPlatform): Credentials? {
        val p = prefs(platform)
        val key = p.getString(PREF_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val secret = p.getString(PREF_SECRET)?.takeIf { it.isNotBlank() } ?: return null
        val session = p.getString(PREF_SESSION)?.takeIf { it.isNotBlank() } ?: return null
        return Credentials(key, secret, session)
    }

    fun user(platform: AppPlatform): String? = prefs(platform).getString(PREF_USER)

    fun apiKey(platform: AppPlatform): String = prefs(platform).getString(PREF_KEY).orEmpty()
    fun secret(platform: AppPlatform): String = prefs(platform).getString(PREF_SECRET).orEmpty()

    /** Logs in and keeps the session; the password is used once and not stored. Null on success, else the reason. */
    suspend fun login(platform: AppPlatform, apiKey: String, secret: String, user: String, password: String): String? {
        val answer = NativeEngine.lastFmLogin(apiKey.trim(), secret.trim(), user.trim(), password)
        val session = runCatching { RustifyJson.decodeFromString(Session.serializer(), answer) }.getOrNull()
            ?: return runCatching { parseObject(answer)["error"].optString() }.getOrNull().orEmpty().ifBlank { answer.take(200) }
        prefs(platform).apply {
            putString(PREF_KEY, apiKey.trim())
            putString(PREF_SECRET, secret.trim())
            putString(PREF_SESSION, session.key)
            putString(PREF_USER, session.name)
        }
        return null
    }

    fun logout(platform: AppPlatform) {
        prefs(platform).apply {
            remove(PREF_SESSION)
            remove(PREF_USER)
        }
    }

    private fun json(c: Credentials) = RustifyJson.encodeToString(Credentials.serializer(), c)

    suspend fun submit(platform: AppPlatform, listen: ListenBrainz.Listen): ListenBrainz.Report? {
        val c = credentials(platform) ?: return null
        val answer = NativeEngine.lastFmSubmit(json(c), RustifyJson.encodeToString(ListenBrainz.Listen.serializer(), listen))
        return runCatching { RustifyJson.decodeFromString(ListenBrainz.Report.serializer(), answer) }.getOrNull()
            .also { com.varuna.rustify.platform.Log.d("LastFm", "submit: $it ${if (it == null) answer.take(200) else ""}") }
    }

    suspend fun nowPlaying(platform: AppPlatform, listen: ListenBrainz.Listen) {
        val c = credentials(platform) ?: return
        runCatching { NativeEngine.lastFmNowPlaying(json(c), RustifyJson.encodeToString(ListenBrainz.Listen.serializer(), listen)) }
    }
}
