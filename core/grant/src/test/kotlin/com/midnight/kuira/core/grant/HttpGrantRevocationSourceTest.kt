package com.midnight.kuira.core.grant

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The revocation list read over HTTP from a real local server serving the vectors' signed revocations. */
class HttpGrantRevocationSourceTest {
    private val root = Vectors["grant"].field("fields").field("root").bytes()
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val requested = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Answers one HTTP/1.1 request with [status] and [body], recording the request's path. */
    private fun serve(status: Int, body: String) {
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine()
                while (!reader.readLine().isNullOrEmpty()) Unit
                requested.add(requestLine.split(" ")[1])
                val bytes = body.toByteArray()
                val head = "HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }

    private val origin: String get() = "http://127.0.0.1:${server.localPort}"

    @After
    fun stop() = server.close()

    @Test
    fun theListIsReadFromTheWellKnownPathAndVerified() = runTest {
        val entry = Vectors["revocations"].field("retire")
        val wire = GrantWire(entry.field("preimage").bytes(), entry.field("signature").bytes()).text
        serve(200, """{"revocations": ["$wire"]}""")

        val list = HttpGrantRevocationSource(origin) { 1_800_000_500 }.list(root)

        assertEquals(listOf("/${HttpGrantRevocationSource.PATH_PREFIX}/${hex(root)}"), requested)
        assertEquals(1_800_000_500L, list.readAt)
        assertEquals(1, list.revocations.size)
        assertEquals(GrantRevocation.decode(entry.field("preimage").bytes()), list.revocations.single())
    }

    @Test
    fun aStatusOtherThan200IsRefused() = runTest {
        serve(404, "")

        val error = runCatching { HttpGrantRevocationSource(origin).list(root) }.exceptionOrNull()

        assertEquals("malformed: the revocation list answered 404", error?.message)
    }

    @Test
    fun aListWithAForgedEntryIsRefused() = runTest {
        val entry = Vectors["revocations"].field("revoke")
        val signature = entry.field("signature").bytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
        serve(200, """{"revocations": ["${GrantWire(entry.field("preimage").bytes(), signature).text}"]}""")

        assertRefused("badSignature") { HttpGrantRevocationSource(origin).list(root) }
    }

    @Test
    fun aBodyThatIsNotAListIsRefused() = runTest {
        serve(200, """{"revoked": []}""")

        val error = runCatching { HttpGrantRevocationSource(origin).list(root) }.exceptionOrNull()

        assertEquals("malformed: not a revocation list", error?.message)
    }
}
