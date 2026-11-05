package com.varuna.rustify.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.varuna.rustify.platform.IO
import com.varuna.rustify.ui.AppPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Drive's sign-in on the desktop: Google's page in the system browser, and the answer back on a
 * port of 127.0.0.1 that is open only while it waits ([LoopbackBrowser]). The https redirect page
 * hands the answer on to that port, which `state` names. Nothing listens anywhere but loopback.
 */
class DesktopDriveAuth(platform: AppPlatform) : DriveAuth {
    private val prefs = platform.prefs()
    private val oauth = DriveOAuth(prefs, LoopbackBrowser(platform))

    override val methods = listOf(DriveAuth.BROWSER)

    @OptIn(ExperimentalUuidApi::class)
    override val device: RustifyBackup.Device
        get() {
            val id = prefs.getString(K_DEVICE_ID) ?: Uuid.random().toString().also { prefs.putString(K_DEVICE_ID, it) }
            val name = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?: System.getProperty("os.name")
            return RustifyBackup.Device(id, name)
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

/** Opens a loopback port, the browser on Google's page, and waits there for the redirect page's request. */
internal class LoopbackBrowser(
    private val platform: AppPlatform,
    private val timeoutMs: Int = 5 * 60_000,
    private val openUrl: (String) -> Unit = { platform.openUrl(it) },
) : DriveOAuth.Browser {
    override suspend fun open(): DriveOAuth.Session = withContext(Dispatchers.IO) {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        object : DriveOAuth.Session {
            override val statePrefix = "$STATE_PREFIX${server.localPort}-"

            override suspend fun await(url: String): Map<String, String> = withContext(Dispatchers.IO) {
                server.use { socket ->
                    socket.soTimeout = timeoutMs
                    openUrl(url)
                    while (true) {
                        val client = try {
                            socket.accept()
                        } catch (e: SocketTimeoutException) {
                            throw DriveException("no answer from the browser")
                        }
                        client.use { c ->
                            val line = c.getInputStream().bufferedReader().readLine().orEmpty()
                            val target = line.split(' ').getOrNull(1).orEmpty()
                            val query = DriveOAuth.queryOf(target)
                            val done = "code" in query || "error" in query
                            val page = if (done) DONE_PAGE else ""
                            val status = if (done) "200 OK" else "404 Not Found"
                            c.getOutputStream().write(
                                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                    "Content-Length: ${page.encodeToByteArray().size}\r\nConnection: close\r\n\r\n$page").encodeToByteArray()
                            )
                            c.getOutputStream().flush()
                            if (done) return@withContext query
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    emptyMap()
                }
            }
        }
    }

    companion object {
        /** What the redirect page reads to send the answer to `http://127.0.0.1:<port>/`. */
        const val STATE_PREFIX = "rustify-desktop-"

        private const val DONE_PAGE =
            "<!doctype html><meta charset=utf-8><title>Rustify</title>" +
                "<body style=\"font-family:sans-serif;background:#121212;color:#eee;text-align:center;padding-top:20vh\">" +
                "<h1>Rustify</h1><p>Google Drive &#10003;</p></body>"
    }
}
