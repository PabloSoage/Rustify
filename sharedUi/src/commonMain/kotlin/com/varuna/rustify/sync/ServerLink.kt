package com.varuna.rustify.sync

import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.util.parseObject
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Hands this app's Spotify session to the person's own rustify-server (docs serie-4.x/28 §5 bis), so
 * the web interface it serves has the account without pasting the cookie by hand: the server's
 * access token buys a session (`/login`, as the browser does), and with it the server's core logs in
 * with this app's `sp_dc` (`/api/call/loginSpotify`). Nothing is written to the account.
 *
 * The cookie is the account, so it goes only where it cannot be read on the way: https anywhere, or
 * plain http to this machine or a private network address (a NAS at home). Never http elsewhere.
 */
object ServerLink {
    sealed interface Outcome {
        data object Linked : Outcome
        /** The address is not one the cookie may travel to in clear. */
        data object Insecure : Outcome
        data object BadAddress : Outcome
        /** The server did not take the access token. */
        data object WrongToken : Outcome
        /** The server answered, and its core could not log in (the reason is the core's). */
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** `http(s)://host[:port]` with no path, as typed or with `http://` when none was. */
    fun normalise(address: String): String? {
        val trimmed = address.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = withScheme.substringAfter("://")
        if (rest.isEmpty() || '/' in rest || '?' in rest || '@' in rest) return null
        return "$scheme://$rest"
    }

    /** May the cookie go to [base] (already [normalise]d)? */
    fun mayCarryCookie(base: String): Boolean {
        if (base.startsWith("https://")) return true
        val host = hostOf(base)
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        if (host.startsWith("[")) return isPrivateV6(host)
        val parts = host.split('.').map { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null || it !in 0..255 }) return false
        val a = parts[0]!!
        val b = parts[1]!!
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) // CGNAT: Tailscale and the like, a private network too.
    }

    private fun hostOf(base: String): String {
        val rest = base.substringAfter("://")
        return if (rest.startsWith("[")) rest.substringBefore("]") + "]" else rest.substringBefore(':').lowercase()
    }

    private fun isPrivateV6(host: String): Boolean {
        val inner = host.removePrefix("[").removeSuffix("]").lowercase()
        return inner == "::1" || inner.startsWith("fd") || inner.startsWith("fc") || inner.startsWith("fe80")
    }

    private val http by lazy {
        httpClient {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    suspend fun link(address: String, token: String, spDc: String): Outcome {
        val base = normalise(address) ?: return Outcome.BadAddress
        if (!mayCarryCookie(base)) return Outcome.Insecure
        return try {
            val login = http.submitForm("$base/login", parameters { append("token", token.trim()) })
            val session = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .map { it.substringBefore(';') }
                .firstOrNull { it.startsWith("$SESSION_COOKIE=") && it.length > SESSION_COOKIE.length + 1 }
                ?: return Outcome.WrongToken
            val answer = http.post("$base/api/call/loginSpotify") {
                header(HttpHeaders.Cookie, session)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("spDc", spDc) }.toString())
            }
            val text = answer.bodyAsText()
            if (!answer.status.isSuccess()) return Outcome.Failed("HTTP ${answer.status.value}")
            val json = runCatching { parseObject(text) }.getOrNull() ?: return Outcome.Failed(text.take(200))
            if ((json["success"] as? JsonPrimitive)?.booleanOrNull == true) Outcome.Linked
            else Outcome.Refused((json["error"] as? JsonPrimitive)?.contentOrNull ?: text.take(200))
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e::class.simpleName ?: "error")
        }
    }

    /** The server's session cookie (server/src/auth.rs `COOKIE`). */
    const val SESSION_COOKIE = "rustify_session"
}
