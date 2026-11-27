package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RemoteMfiAuthenticationClientTest {
    private lateinit var server: TestHttpServer

    @Before
    fun startServer() {
        server = TestHttpServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun resetsLoadsAndCachesCertificateThenSignsWithStableRequestId() {
        val certificate = ByteArray(300) { it.toByte() }
        val signature = ByteArray(64) { (0xa0 + it).toByte() }
        val certificateRequests = AtomicInteger()
        val signRequests = Collections.synchronizedList(mutableListOf<String>())

        server.createContext("/mfi/reset") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("{}", exchange.requestBody.readText())
            exchange.respond(200, "{\"detail\":\"\"}")
        }
        server.createContext("/mfi/certificate") { exchange ->
            certificateRequests.incrementAndGet()
            val encoded = Base64.getEncoder().encodeToString(certificate)
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate).toHex()
            exchange.respond(
                200,
                "{\"protocolMajor\":3,\"certificate\":\"$encoded\"," +
                    "\"certificateSha256\":\"$digest\"}",
            )
        }
        server.createContext("/mfi/sign") { exchange ->
            val body = exchange.requestBody.readText()
            signRequests += body
            if (signRequests.size == 1) {
                Thread.sleep(200)
            }
            exchange.respond(
                200,
                "{\"signature\":\"${Base64.getEncoder().encodeToString(signature)}\"}",
            )
        }

        val client = RemoteMfiAuthenticationClient(
            serverAddress = serverAddress(),
            token = "test-token",
            readTimeoutMillis = 50,
        )
        client.reset()
        assertEquals(3, client.protocolMajor())
        assertArrayEquals(certificate, client.readCertificate())
        assertEquals(1, certificateRequests.get())

        val challenge = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(signature, client.signChallenge(challenge))
        assertEquals(2, signRequests.size)
        val firstRequestId = RemoteMfiJson.string(signRequests[0], "requestId")
        val secondRequestId = RemoteMfiJson.string(signRequests[1], "requestId")
        assertEquals(firstRequestId, secondRequestId)
        assertEquals(
            Base64.getEncoder().encodeToString(challenge),
            RemoteMfiJson.string(signRequests[0], "challenge"),
        )
    }

    @Test
    fun rejectsCertificateWhenSha256DoesNotMatch() {
        server.createContext("/mfi/certificate") { exchange ->
            exchange.respond(
                200,
                "{\"protocolMajor\":3,\"certificate\":\"AQID\"," +
                    "\"certificateSha256\":\"${"00".repeat(32)}\"}",
            )
        }

        val error = assertThrows(MfiInvalidDataException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).readCertificate()
        }
        assertEquals(
            "Remote certificate SHA-256 does not match certificateSha256",
            error.message,
        )
    }

    @Test
    fun loadsBaaPackageAndBuildsShowcaseIap2Payload() {
        val leaf = ByteArray(40) { (0x10 + it).toByte() }
        val intermediate = ByteArray(60) { (0x80 + it).toByte() }
        val packageBytes = u32(leaf.size) + u32(intermediate.size) + leaf + intermediate

        server.createContext("/mfi/certificate") { exchange ->
            val encoded = Base64.getEncoder().encodeToString(packageBytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(packageBytes).toHex()
            exchange.respond(
                200,
                "{\"type\":\"baa\",\"protocolMajor\":3,\"certificate\":\"$encoded\"," +
                    "\"certificateSha256\":\"$digest\"}",
            )
        }

        val client = RemoteMfiAuthenticationClient(serverAddress())
        assertEquals(MfiCertificateType.BAA, client.certificateType)
        assertArrayEquals(
            Iap2AuthenticationMessages.baaCertificatePackage(leaf, intermediate).payload,
            client.readCertificate(),
        )
        val certificates = client.baaCertificates()
        assertArrayEquals(leaf, certificates.leaf)
        assertArrayEquals(intermediate, certificates.intermediate)
    }

    @Test
    fun rejectsUnknownCertificateType() {
        server.createContext("/mfi/certificate") { exchange ->
            exchange.respond(
                200,
                "{\"type\":\"unknown\",\"protocolMajor\":3,\"certificate\":\"AQID\"," +
                    "\"certificateSha256\":\"${"00".repeat(32)}\"}",
            )
        }

        val error = assertThrows(MfiInvalidDataException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).readCertificate()
        }
        assertEquals("Unsupported remote MFI certificate type 'unknown'", error.message)
    }

    @Test
    fun resetSurfacesHttp500DetailWithoutRetrying() {
        val requests = AtomicInteger()
        server.createContext("/mfi/reset") { exchange ->
            requests.incrementAndGet()
            exchange.respond(500, "{\"detail\":\"chip reset failed\"}")
        }

        val error = assertThrows(RemoteMfiHttpException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).reset()
        }
        assertEquals(500, error.statusCode)
        assertEquals("chip reset failed", error.detail)
        assertEquals(1, requests.get())
    }

    private fun serverAddress(): String = "http://127.0.0.1:${server.address.port}"

    private fun TestExchange.respond(statusCode: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (statusCode in 200..299) "OK" else "Error"
        output.write("HTTP/1.1 $statusCode $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: application/json\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun java.io.InputStream.readText(): String =
        use { String(it.readBytes(), StandardCharsets.UTF_8) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private inner class TestHttpServer {
        private val socket = ServerSocket()
        private val executor = Executors.newCachedThreadPool()
        private val contexts = ConcurrentHashMap<String, (TestExchange) -> Unit>()
        val address: InetSocketAddress
            get() = socket.localSocketAddress as InetSocketAddress

        fun start() {
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            executor.execute {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        executor.execute { handle(client) }
                    } catch (failure: java.net.SocketException) {
                        if (!socket.isClosed) throw failure
                    }
                }
            }
        }

        fun createContext(path: String, handler: (TestExchange) -> Unit) {
            contexts[path] = handler
        }

        fun stop(delaySeconds: Int) {
            socket.close()
            executor.shutdownNow()
        }

        private fun handle(client: Socket) {
            client.use { connection ->
                try {
                    val input = connection.getInputStream()
                    val requestLine = input.readHttpLine() ?: return
                    val requestParts = requestLine.split(' ', limit = 3)
                    if (requestParts.size != 3) return
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = input.readHttpLine() ?: return
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            headers[line.substring(0, separator).trim().lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < body.size) {
                        val count = input.read(body, offset, body.size - offset)
                        if (count < 0) return
                        offset += count
                    }
                    val path = requestParts[1].substringBefore('?')
                    val exchange = TestExchange(
                        requestMethod = requestParts[0],
                        requestHeaders = TestHeaders(headers),
                        requestBody = ByteArrayInputStream(body),
                        output = connection.getOutputStream(),
                    )
                    contexts[path]?.invoke(exchange) ?: exchange.respond(404, "not found")
                } catch (_: Exception) {
                    runCatching {
                        connection.getOutputStream().write(
                            "HTTP/1.1 500 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(StandardCharsets.US_ASCII),
                        )
                    }
                }
            }
        }
    }

    private data class TestExchange(
        val requestMethod: String,
        val requestHeaders: TestHeaders,
        val requestBody: InputStream,
        val output: OutputStream,
    )

    private class TestHeaders(private val values: Map<String, String>) {
        fun getFirst(name: String): String? = values[name.lowercase()]
    }

    private fun InputStream.readHttpLine(): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString("US-ASCII")
    }
}
