package com.varuna.rustify.sync

import androidx.compose.runtime.Composable
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.currentTimeMillis
import com.varuna.rustify.platform.formUrlEncode
import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.util.parseObject
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * How this platform gets a Drive access token with the `drive.appdata` scope, for [DriveSync] and
 * the Drive section in Settings. Android has two ways (Play Services, and the browser); the desktop
 * and iOS sign in in the browser ([DriveOAuth]); where there is no client configured, or no way at
 * all (the web), the platform has none and the section is not offered.
 */
interface DriveAuth {
    /** The ways to sign in, by the keys [DriveSyncPrefs.authMethod] stores; a choice shows only with two. */
    val methods: List<String>

    /** This device, as the backup names it. */
    val device: RustifyBackup.Device

    fun isConfigured(method: String): Boolean

    /** A token without asking the person (a stored refresh, a cached consent), or null when it needs them. */
    suspend fun silentToken(method: String): String?

    /** The sign-in that asks: remembered by the section, since Android's needs activity launchers. Throws on failure. */
    @Composable
    fun rememberSignIn(method: String): suspend () -> String

    /** Forgets the sign-in here; the person can revoke the access from their Google account. */
    fun unlink()

    companion object {
        const val PLAY = "play"
        const val BROWSER = "browser"
        const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}

/**
 * Google's OAuth for an installed app, in the browser (RFC 8252 + PKCE), with the phone's "browser"
 * client: the same flow as Android's AppAuth, over Ktor. The redirect is the https page the phone
 * uses ([REDIRECT_URI]); on the phone the app takes it as an App Link, and elsewhere the page hands
 * the answer on to where the [Browser] waits for it, which [Browser.statePrefix] tells it.
 *
 * Keeps the refresh token in the platform's preferences, as AppAuth keeps its state on the phone.
 */
class DriveOAuth(
    private val prefs: KeyValueStore,
    private val browser: Browser,
    private val clientId: String = DriveClient.ID,
    private val clientSecret: String = DriveClient.SECRET,
    private val authEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
) {
    /** Where the platform shows Google's page and waits for the redirect's query. */
    fun interface Browser {
        /** Gets ready to receive the answer (the desktop opens its port here). */
        suspend fun open(): Session
    }

    interface Session {
        /** What `state` starts with, for the redirect page to know where to hand the answer on. */
        val statePrefix: String

        /** Opens [url] and answers the redirect's query parameters (`code`, `state` or `error`). */
        suspend fun await(url: String): Map<String, String>
    }

    val isConfigured: Boolean get() = clientId.isNotBlank()

    val isAuthorized: Boolean get() = !prefs.getString(K_REFRESH).isNullOrBlank()

    /** A token from the stored refresh token, or null when there is none (sign in first). */
    suspend fun silentToken(): String? {
        val cached = prefs.getString(K_ACCESS)
        if (!cached.isNullOrBlank() && prefs.getLong(K_EXPIRES, 0L) > currentTimeMillis() + 60_000) return cached
        val refresh = prefs.getString(K_REFRESH)?.takeIf { it.isNotBlank() } ?: return null
        return token(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
        )
    }

    /** Asks in the browser, exchanges the code and keeps the refresh token. */
    suspend fun signIn(): String {
        check(isConfigured) { "no OAuth client configured" }
        val verifier = randomUrlSafe(48)
        val session = browser.open()
        val state = session.statePrefix + randomUrlSafe(16)
        val url = authEndpoint +
            "?client_id=" + formUrlEncode(clientId) +
            "&redirect_uri=" + formUrlEncode(REDIRECT_URI) +
            "&response_type=code" +
            "&scope=" + formUrlEncode("openid ${DriveAuth.SCOPE_APPDATA}") +
            "&code_challenge=" + challengeOf(verifier) +
            "&code_challenge_method=S256" +
            "&state=" + formUrlEncode(state) +
            "&access_type=offline&prompt=consent"
        val answer = session.await(url)
        answer["error"]?.let { throw DriveException("Google: $it") }
        if (answer["state"] != state) throw DriveException("the answer is not for this sign-in")
        val code = answer["code"]?.takeIf { it.isNotBlank() } ?: throw DriveException("Google gave no code")
        return token(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to verifier,
        )
    }

    fun signOut() {
        prefs.remove(K_REFRESH)
        prefs.remove(K_ACCESS)
        prefs.remove(K_EXPIRES)
    }

    private val http by lazy {
        httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    private suspend fun token(vararg fields: Pair<String, String>): String {
        val response = http.submitForm(tokenEndpoint, parameters {
            fields.forEach { (k, v) -> append(k, v) }
            append("client_id", clientId)
            if (clientSecret.isNotBlank()) append("client_secret", clientSecret)
        })
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // A refresh token Google no longer honours (revoked, or a test client's 7 days): sign in again.
            if (fields.any { it.first == "refresh_token" }) signOut()
            throw DriveException("Google token HTTP ${response.status.value}: ${text.take(300)}")
        }
        val json = parseObject(text)
        val access = (json["access_token"] as? JsonPrimitive)?.contentOrNull ?: throw DriveException("Google gave no token")
        val expiresIn = (json["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L
        prefs.putString(K_ACCESS, access)
        prefs.putLong(K_EXPIRES, currentTimeMillis() + expiresIn * 1000)
        (json["refresh_token"] as? JsonPrimitive)?.contentOrNull?.let { prefs.putString(K_REFRESH, it) }
        return access
    }

    companion object {
        /** The phone's redirect (AppAuthDriveAuth.REDIRECT_URI), registered in the client. */
        const val REDIRECT_URI = "https://rustify-music.github.io/oauth2redirect"

        private const val K_REFRESH = "drive_oauth_refresh"
        private const val K_ACCESS = "drive_oauth_access"
        private const val K_EXPIRES = "drive_oauth_expires"

        /** The S256 challenge of [verifier]: base64url, no padding, of its SHA-256. */
        @OptIn(ExperimentalEncodingApi::class)
        fun challengeOf(verifier: String): String =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(Sha256.digest(verifier.encodeToByteArray()))

        /** [bytes] of the system's secure random (`Uuid.random` is cryptographically strong), base64url. */
        @OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
        fun randomUrlSafe(bytes: Int): String {
            val raw = ByteArray(bytes)
            var at = 0
            while (at < bytes) {
                val chunk = Uuid.random().toByteArray()
                // A version 4 UUID fixes 6 of its 128 bits; the rest are random. Bytes 6 and 8 carry
                // them, so only the other fourteen are taken.
                for (i in chunk.indices) if (i != 6 && i != 8 && at < bytes) raw[at++] = chunk[i]
            }
            return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(raw)
        }

        /** The query of a redirect URL (or of a request line's target), decoded. */
        fun queryOf(url: String): Map<String, String> =
            url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.associate { part ->
                val key = part.substringBefore('=')
                decode(key) to decode(part.substringAfter('=', ""))
            }

        private fun decode(text: String): String {
            val out = ArrayList<Byte>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '+' -> { out.add(' '.code.toByte()); i++ }
                    c == '%' && i + 2 <= text.lastIndex -> {
                        out.add(text.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
                    }
                    else -> { c.toString().encodeToByteArray().forEach(out::add); i++ }
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}

/** SHA-256 (FIPS 180-4), for PKCE's challenge: common code has no digest of its own. */
@OptIn(ExperimentalUnsignedTypes::class)
internal object Sha256 {
    private val K = uintArrayOf(
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
    ).map { it.toInt() }.toIntArray()

    fun digest(message: ByteArray): ByteArray {
        val h = uintArrayOf(
            0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au, 0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u,
        ).map { it.toInt() }.toIntArray()
        val bitLength = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 9 + 63) / 64) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in padded.indices step 64) {
            for (t in 0 until 16) {
                val o = block + t * 4
                w[t] = ((padded[o].toInt() and 0xff) shl 24) or ((padded[o + 1].toInt() and 0xff) shl 16) or
                    ((padded[o + 2].toInt() and 0xff) shl 8) or (padded[o + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }
}
