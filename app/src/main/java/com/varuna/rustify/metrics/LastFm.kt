package com.varuna.rustify.metrics

import android.content.Context
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun credentials(context: Context): Credentials? {
        val p = prefs(context)
        val key = p.getString(PREF_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val secret = p.getString(PREF_SECRET, null)?.takeIf { it.isNotBlank() } ?: return null
        val session = p.getString(PREF_SESSION, null)?.takeIf { it.isNotBlank() } ?: return null
        return Credentials(key, secret, session)
    }

    fun user(context: Context): String? = prefs(context).getString(PREF_USER, null)

    fun apiKey(context: Context): String = prefs(context).getString(PREF_KEY, "").orEmpty()
    fun secret(context: Context): String = prefs(context).getString(PREF_SECRET, "").orEmpty()

    /** Logs in and keeps the session; the password is used once and not stored. Null on success, else the reason. */
    suspend fun login(context: Context, apiKey: String, secret: String, user: String, password: String): String? {
        val answer = NativeEngine.lastFmLogin(apiKey.trim(), secret.trim(), user.trim(), password)
        val session = runCatching { RustifyJson.decodeFromString(Session.serializer(), answer) }.getOrNull()
            ?: return runCatching { org.json.JSONObject(answer).optString("error") }.getOrNull().orEmpty().ifBlank { answer.take(200) }
        prefs(context).edit()
            .putString(PREF_KEY, apiKey.trim())
            .putString(PREF_SECRET, secret.trim())
            .putString(PREF_SESSION, session.key)
            .putString(PREF_USER, session.name)
            .apply()
        return null
    }

    fun logout(context: Context) {
        prefs(context).edit().remove(PREF_SESSION).remove(PREF_USER).apply()
    }

    private fun json(c: Credentials) = RustifyJson.encodeToString(Credentials.serializer(), c)

    suspend fun submit(context: Context, listen: ListenBrainz.Listen): ListenBrainz.Report? {
        val c = credentials(context) ?: return null
        val answer = NativeEngine.lastFmSubmit(json(c), RustifyJson.encodeToString(ListenBrainz.Listen.serializer(), listen))
        return runCatching { RustifyJson.decodeFromString(ListenBrainz.Report.serializer(), answer) }.getOrNull()
            .also { android.util.Log.d("LastFm", "submit: $it ${if (it == null) answer.take(200) else ""}") }
    }

    suspend fun nowPlaying(context: Context, listen: ListenBrainz.Listen) {
        val c = credentials(context) ?: return
        runCatching { NativeEngine.lastFmNowPlaying(json(c), RustifyJson.encodeToString(ListenBrainz.Listen.serializer(), listen)) }
    }
}
