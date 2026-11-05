package com.varuna.rustify.ui

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The second launch and the first: what it hands over arrives, and what anyone else sends does not.
 * A real lock and a real loopback socket, in a folder of the test's own.
 */
class SingleInstanceTest {
    private lateinit var dir: File
    private val opened = mutableListOf<SingleInstance>()

    @BeforeTest
    fun folder() {
        dir = Files.createTempDirectory("rustify-instance").toFile()
    }

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun first(): Pair<SingleInstance, LinkedBlockingQueue<List<String>>> {
        val instance = assertNotNull(SingleInstance.claim(dir))
        opened += instance
        val received = LinkedBlockingQueue<List<String>>()
        instance.listen { received.put(it) }
        return instance to received
    }

    @Test
    fun aSecondLaunchFindsTheFolderTaken() {
        first()
        assertNull(SingleInstance.claim(dir))
    }

    @Test
    fun whatTheSecondLaunchWasGivenReachesTheFirst() {
        val (_, received) = first()
        assertTrue(SingleInstance.forward(dir, listOf("rustify://track/abc123", "https://open.spotify.com/album/x")))
        assertEquals(listOf("rustify://track/abc123", "https://open.spotify.com/album/x"), received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun aLaunchWithNothingToOpenStillReachesIt() {
        val (_, received) = first()
        assertTrue(SingleInstance.forward(dir, emptyList()))
        assertEquals(emptyList<String>(), received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun anArgumentWithALineBreakStaysOneArgument() {
        val (_, received) = first()
        assertTrue(SingleInstance.forward(dir, listOf("a\nb")))
        assertEquals(listOf("a b"), received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun withoutTheTokenNothingIsTaken() {
        val (_, received) = first()
        val port = File(dir, SingleInstance.PORT_FILE).readLines().first().toInt()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(SingleInstance.LOOPBACK, port), 1_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write("${SingleInstance.HELLO}\nnot-the-token\nrustify://track/abc\n".toByteArray())
            socket.shutdownOutput()
            // No answer: the connection is closed without a word.
            assertNull(socket.getInputStream().bufferedReader().readLine())
        }
        assertNull(received.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun somethingThatIsNotALaunchIsIgnored() {
        val (_, received) = first()
        val port = File(dir, SingleInstance.PORT_FILE).readLines().first().toInt()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(SingleInstance.LOOPBACK, port), 1_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            socket.shutdownOutput()
            assertNull(socket.getInputStream().bufferedReader().readLine())
        }
        assertNull(received.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun theTokenIsReadableOnlyByTheUser() {
        first()
        val file = File(dir, SingleInstance.PORT_FILE).toPath()
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
    }

    @Test
    fun withNobodyListeningTheSecondLaunchGivesUp() {
        assertFalse(SingleInstance.forward(dir, listOf("rustify://track/abc"), waitMs = 300))
    }

    @Test
    fun onceTheFirstQuitsTheNextLaunchIsTheFirst() {
        val (instance, _) = first()
        instance.close()
        opened.remove(instance)
        assertFalse(File(dir, SingleInstance.PORT_FILE).exists())
        opened += assertNotNull(SingleInstance.claim(dir))
    }
}
