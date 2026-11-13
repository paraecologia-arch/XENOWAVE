package com.dd3boh.xenowave.insights.musicalage.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Controlled local TCP server used only to prove that cancellation/deadline
 * actually closes the client socket. It receives no personal data and the
 * production allowlist remains MusicBrainz-only.
 */
class MetadataTransportCancellationTest {
    private lateinit var server: LocalServer

    @Before
    fun setUp() {
        server = LocalServer(LocalServer.Mode.HEADERS)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun transport() = KtorMetadataTransport(
        userAgent = "XENOWAVE-test",
        dispatcher = Dispatchers.IO,
        allowedHosts = setOf("127.0.0.1"),
        requireHttps = false,
    )

    @Test
    fun `cancelling while headers are pending closes the client socket`() = runBlocking {
        val job = launch(Dispatchers.Default) {
            transport().execute(
                MetadataRequest("http://127.0.0.1:${server.port}/ws/2/slow"),
                maxResponseBytes = 1_048_576L,
                timeoutMillis = 10_000L,
            )
        }
        withTimeout(5_000) { server.requestRead.await() }
        job.cancelAndJoin()

        assertTrue(
            "server did not observe client close",
            server.clientClosed.await(3, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `cancelling while the body is slow closes the client socket`() = runBlocking {
        server.close()
        server = LocalServer(LocalServer.Mode.BODY)
        val job = launch(Dispatchers.Default) {
            transport().execute(
                MetadataRequest("http://127.0.0.1:${server.port}/ws/2/slow"),
                maxResponseBytes = 1_048_576L,
                timeoutMillis = 10_000L,
            )
        }
        withTimeout(5_000) { server.requestRead.await() }
        // Let the transport reach the body read before cancelling.
        Thread.sleep(200)
        job.cancelAndJoin()

        assertTrue(
            "server did not observe client close",
            server.clientClosed.await(3, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `total deadline closes a slow response socket`() = runBlocking {
        // The deadline has to be long enough for a cold JVM to open the local
        // socket and send the headers on a loaded CI runner; the server never
        // answers, so the deadline is still the only way this call can finish.
        try {
            transport().execute(
                MetadataRequest("http://127.0.0.1:${server.port}/ws/2/slow"),
                maxResponseBytes = 1_048_576L,
                timeoutMillis = 5_000L,
            )
            throw AssertionError("expected MetadataTransportException")
        } catch (_: MetadataTransportException) {
            // Expected.
        }

        assertTrue(
            "server did not receive the request before the deadline",
            server.requestRead.await(5, TimeUnit.SECONDS),
        )
        val closed = server.clientClosed.await(15, TimeUnit.SECONDS)
        assertTrue(
            "server did not observe client close after deadline (closed=$closed)",
            closed,
        )
    }

    private class LocalServer(private val mode: Mode) {
        enum class Mode {
            HEADERS,
            BODY,
        }

        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort
        val requestRead = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        private val thread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    socket.soTimeout = 20_000
                    readHeaders(socket)
                    requestRead.countDown()
                    if (mode == Mode.BODY) {
                        val body = ByteArray(10) { 'x'.code.toByte() }
                        val headers = (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: 100\r\n\r\n"
                            ).toByteArray()
                        socket.getOutputStream().apply {
                            write(headers)
                            write(body)
                            flush()
                        }
                    }
                    // Do not answer. The only way out is the client closing
                    // its side, which proves cancellation/deadline.
                    val input = socket.getInputStream()
                    val buffer = ByteArray(1_024)
                    while (input.read(buffer) >= 0) {
                        // Drain until EOF.
                    }
                }
            } catch (_: Exception) {
                // A closed socket is the expected observation.
            } finally {
                clientClosed.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun close() {
            runCatching { serverSocket.close() }
            runCatching { thread.interrupt() }
            clientClosed.countDown()
        }

        private fun readHeaders(socket: Socket) {
            val input = socket.getInputStream()
            var matched = 0
            val terminator = byteArrayOf(
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
            )
            while (matched < terminator.size) {
                val value = input.read()
                if (value < 0) return
                matched = if (value.toByte() == terminator[matched]) {
                    matched + 1
                } else if (value.toByte() == terminator[0]) {
                    1
                } else {
                    0
                }
            }
        }
    }
}
