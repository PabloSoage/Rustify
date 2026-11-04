// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/SingleInstance.kt
package com.varuna.rustify.ui

import com.varuna.rustify.platform.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.EnumSet
import kotlin.concurrent.thread

/**
 * One Rustify per data folder, and what a second launch was asked to open goes to the first.
 *
 * The first launch holds a lock on `instance.lock` for as long as it runs and listens on a loopback
 * port that nothing but this computer can reach (`127.0.0.1`, a port the system picks). It writes
 * that port and a random token to `instance.port`, readable only by the user who started it. A later
 * launch — the browser following a `rustify://` link, a link handed over on the command line — finds
 * the lock taken, reads the file, sends its arguments with the token and exits; the first one checks
 * the token before reading anything else, and whoever acts on the arguments still reads them with the
 * core's link parser ([DesktopLinks]). Another user on the same computer cannot read the file, so
 * cannot know the token, and a page in a browser cannot speak this protocol at all.
 *
 * The protocol, one connection per launch: a line [HELLO], a line with the token, one line per
 * argument, then the sender closes its half; the answer is `ok` or nothing.
 */
class SingleInstance private constructor(
    private val dir: File,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private var server: ServerSocket? = null

    /**
     * Listens for later launches; [onMessage] gets each one's arguments (none for a plain second
     * launch, which only wants the window), on a background thread.
     */
    fun listen(onMessage: (List<String>) -> Unit) {
        val socket = ServerSocket(0, BACKLOG, LOOPBACK)
        server = socket
        val token = newToken()
        writePrivately(File(dir, PORT_FILE), "${socket.localPort}\n$token\n")
        thread(name = "rustify-single-instance", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                client.use { connection ->
                    val args = runCatching { receive(connection, token) }
                        .onFailure { Log.w(TAG, "a second launch's message could not be read: ${it.message}") }
                        .getOrNull() ?: return@use
                    runCatching { onMessage(args) }.onFailure { Log.w(TAG, "a second launch's message failed", it) }
                }
            }
        }
    }

    /** Stops listening and lets the next launch be the first. */
    override fun close() {
        runCatching { server?.close() }
        // Still ours: nobody else writes it without the lock, which this process holds until below.
        if (server != null) runCatching { File(dir, PORT_FILE).delete() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val TAG = "SingleInstance"
        const val LOCK_FILE = "instance.lock"
        const val PORT_FILE = "instance.port"
        const val HELLO = "rustify-instance 1"
        private const val BACKLOG = 8
        const val MAX_ARGS = 16
        const val MAX_ARG_CHARS = 4096
        // The header, the token and MAX_ARGS arguments of MAX_ARG_CHARS (UTF-8, up to 4 bytes each).
        private const val MAX_BYTES = 128 + MAX_ARGS * (MAX_ARG_CHARS * 4 + 1)
        private const val READ_TIMEOUT_MS = 2_000

        /** 127.0.0.1 by number: `getLoopbackAddress()` may answer ::1, and the port is written for v4. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /**
         * This launch's claim on [dir]: the instance when it is the first, `null` when another holds
         * it (then [forward] what this one was asked to do).
         */
        fun claim(dir: File): SingleInstance? {
            dir.mkdirs()
            val channel = try {
                FileChannel.open(File(dir, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (e: IOException) {
                return withoutLock(dir, e)
            }
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // This same process holds it (a test claiming twice): taken all the same.
                null
            } catch (e: IOException) {
                runCatching { channel.close() }
                return withoutLock(dir, e)
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return SingleInstance(dir, channel, lock)
        }

        // A folder where locks cannot be taken (a read-only one, some network shares): run, as before
        // there was a lock, rather than not at all.
        private fun withoutLock(dir: File, e: IOException): SingleInstance? {
            Log.w(TAG, "cannot lock ${File(dir, LOCK_FILE)}: ${e.message}; not checking for another Rustify")
            unlockable = true
            return null
        }

        /**
         * Set when [claim] could not take a lock at all, rather than finding it taken: the caller
         * should then run as the only instance instead of forwarding to one that may not exist.
         */
        @Volatile
        var unlockable: Boolean = false
            private set

        /**
         * Hands [args] to the instance running over [dir]; false when it could not be reached within
         * [waitMs] (it may still be starting, hence the retries, or not answering at all).
         */
        fun forward(dir: File, args: List<String>, waitMs: Long = 6_000): Boolean {
            val deadline = System.currentTimeMillis() + waitMs
            while (true) {
                if (send(dir, args)) return true
                if (System.currentTimeMillis() >= deadline) return false
                Thread.sleep(150)
            }
        }

        private fun send(dir: File, args: List<String>): Boolean {
            val lines = runCatching { File(dir, PORT_FILE).takeIf { it.isFile }?.readLines() }.getOrNull() ?: return false
            val port = lines.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
            val token = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            return runCatching { exchange(port, token, args) }.getOrDefault(false)
        }

        private fun exchange(port: Int, token: String, args: List<String>): Boolean =
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, port), 1_000)
                socket.soTimeout = READ_TIMEOUT_MS
                val message = buildString {
                    append(HELLO).append('\n')
                    append(token).append('\n')
                    args.take(MAX_ARGS).forEach { append(oneLine(it).take(MAX_ARG_CHARS)).append('\n') }
                }
                socket.getOutputStream().apply {
                    write(message.toByteArray(Charsets.UTF_8))
                    flush()
                }
                socket.shutdownOutput()
                socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() == "ok"
            }

        /** A link has no line break; an argument with one is not a link, but must not split in two. */
        private fun oneLine(arg: String): String = arg.replace('\r', ' ').replace('\n', ' ')

        /** What one connection asked for, or `null` (nothing answered) when it is not a launch's. */
        internal fun receive(socket: Socket, token: String): List<String>? {
            socket.soTimeout = READ_TIMEOUT_MS
            val bytes = readBounded(socket) ?: return null
            val lines = bytes.toString(Charsets.UTF_8).split('\n')
            if (lines.getOrNull(0) != HELLO) return null
            val offered = lines.getOrNull(1) ?: return null
            // Compared in constant time, though the port is loopback-only: it costs nothing.
            if (!MessageDigest.isEqual(offered.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))) {
                Log.w(TAG, "a connection with the wrong token was ignored")
                return null
            }
            val args = lines.drop(2)
                .filter { it.isNotEmpty() && it.length <= MAX_ARG_CHARS }
                .take(MAX_ARGS)
            socket.getOutputStream().apply {
                write("ok\n".toByteArray(Charsets.UTF_8))
                flush()
            }
            return args
        }

        /**
         * Everything the peer sends until it closes its half, or `null` past [MAX_BYTES] or past
         * [READ_TIMEOUT_MS] in all: a peer sending a byte at a time must not keep the next launch out.
         */
        private fun readBounded(socket: Socket): ByteArrayOutputStream? {
            val input = socket.getInputStream()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return out
                if (out.size() + read > MAX_BYTES || System.nanoTime() > deadline) return null
                out.write(buffer, 0, read)
            }
        }

        private fun newToken(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Writes [text] to [file] so that only this user can read it: `rw-------` where the system has
         * POSIX permissions, an access list naming only the owner on Windows (whose `%APPDATA%` is
         * already the user's alone; this is the second lock on the same door). Written beside and
         * moved over, so a launch reading it never sees half.
         */
        internal fun writePrivately(file: File, text: String) {
            val dir = file.absoluteFile.parentFile
            val tmp = File(dir, file.name + ".tmp").toPath()
            Files.deleteIfExists(tmp)
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                Files.createFile(tmp)
                runCatching { ownerOnly(tmp) }.onFailure { Log.w(TAG, "could not restrict ${file.name} to its owner: ${it.message}") }
            }
            Files.write(tmp, text.toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun ownerOnly(path: Path) {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
            val entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .build()
            view.acl = listOf(entry)
        }
    }
}
