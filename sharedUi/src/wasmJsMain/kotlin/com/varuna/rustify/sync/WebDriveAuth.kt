// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/sync/WebDriveAuth.kt
package com.varuna.rustify.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.varuna.rustify.ui.AppPlatform
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.await

private fun pageOrigin(): String = js("window.location.origin")

private fun pageHost(): String = js("window.location.hostname")

/**
 * Waits on the page's own channel for what `drive-callback.html` hands on: the redirect's query, or
 * `""` after [timeoutMs]. Listening starts here, before Google's page is opened.
 */
private fun awaitCallback(timeoutMs: Int): Promise<JsString> = js(
    """new Promise(function(resolve) {
        var channel = new BroadcastChannel('rustify-drive');
        var timer = setTimeout(function() { channel.close(); resolve(''); }, timeoutMs);
        channel.onmessage = function(e) { clearTimeout(timer); channel.close(); resolve(String(e.data)); };
    })"""
)

/**
 * Drive's sign-in in the browser: the desktop's flow ([DriveOAuth], the same client and the same https
 * redirect page), with the answer coming back to this server instead of a loopback port. `state` names
 * this page's address; the redirect page sends the answer to its `drive-callback.html`, which hands it
 * to this tab over a BroadcastChannel and closes. Google sees one redirect URI whatever the server's
 * address, so nothing has to be registered per server.
 */
class WebDriveAuth(platform: AppPlatform) : DriveAuth {
    private val prefs = platform.prefs()
    private val oauth = DriveOAuth(prefs, CallbackBrowser(platform))

    override val methods = listOf(DriveAuth.BROWSER)

    @OptIn(ExperimentalUuidApi::class)
    override val device: RustifyBackup.Device
        get() {
            val id = prefs.getString(K_DEVICE_ID) ?: Uuid.random().toString().also { prefs.putString(K_DEVICE_ID, it) }
            return RustifyBackup.Device(id, "Web (${pageHost()})")
        }

    override fun isConfigured(method: String): Boolean = oauth.isConfigured

    override suspend fun silentToken(method: String): String? = oauth.silentToken()

    @Composable
    override fun rememberSignIn(method: String): suspend () -> String = remember { suspend { oauth.signIn() } }

    override fun unlink() = oauth.signOut()

    private companion object {
        const val K_DEVICE_ID = "backup_device_id"
    }
}

internal class CallbackBrowser(
    private val platform: AppPlatform,
    private val timeoutMs: Int = 5 * 60_000,
) : DriveOAuth.Browser {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun open(): DriveOAuth.Session = object : DriveOAuth.Session {
        // The redirect page reads the address up to the dot; the random part follows it.
        override val statePrefix = STATE_PREFIX +
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(pageOrigin().encodeToByteArray()) + "."

        override suspend fun await(url: String): Map<String, String> {
            val answer = awaitCallback(timeoutMs)
            platform.openUrl(url)
            val query = answer.await<JsString>().toString()
            if (query.isBlank()) throw DriveException("no answer from the browser")
            return DriveOAuth.queryOf(query)
        }
    }

    companion object {
        /** What the redirect page reads to send the answer to this server's `drive-callback.html`. */
        const val STATE_PREFIX = "rustify-web-"
    }
}
