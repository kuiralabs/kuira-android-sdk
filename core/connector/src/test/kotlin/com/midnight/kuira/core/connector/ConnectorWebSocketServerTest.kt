package com.midnight.kuira.core.connector

import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ConnectorWebSocketServerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private var server: ConnectorWebSocketServer? = null
    private val clients = mutableListOf<WebSocketClient>()

    private fun createServer(port: Int = 0): ConnectorWebSocketServer {
        val handler = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_test",
                shieldedAddress = "mn_shield_test",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_test",
            ),
        )
        val router = JsonRpcRouter(handler)
        val scope = CoroutineScope(Dispatchers.Default)
        return ConnectorWebSocketServer(router, scope, port).also { server = it }
    }

    private fun createClient(port: Int): Pair<WebSocketClient, LinkedBlockingQueue<String>> {
        val messages = LinkedBlockingQueue<String>()
        val client = object : WebSocketClient(URI("ws://127.0.0.1:$port")) {
            override fun onOpen(handshakedata: ServerHandshake) {}
            override fun onMessage(message: String) { messages.add(message) }
            override fun onClose(code: Int, reason: String, remote: Boolean) {}
            override fun onError(ex: Exception) {}
        }
        clients.add(client)
        return client to messages
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.closeBlocking() } }
        server?.stop(0)
    }

    // ── Lifecycle ──

    @Test
    fun `server starts and stops without error`() {
        val s = createServer()
        s.start()
        Thread.sleep(300)
        s.stop(0)
    }

    // ── Connection ──

    @Test
    fun `client can connect to server`() {
        val s = createServer()
        s.start()
        Thread.sleep(300)
        val (client, _) = createClient(s.port)
        assertTrue("Client should connect", client.connectBlocking(2, TimeUnit.SECONDS))
    }

    // ── JSON-RPC routing ──

    @Test
    fun `server routes JSON-RPC message and returns response`() {
        val s = createServer()
        s.start()
        Thread.sleep(300)
        val (client, messages) = createClient(s.port)
        client.connectBlocking(2, TimeUnit.SECONDS)

        client.send("""{"jsonrpc":"2.0","id":1,"method":"getConnectionStatus","params":{}}""")

        val response = messages.poll(2, TimeUnit.SECONDS)
        assertNotNull("Should receive response", response)
        val obj = json.parseToJsonElement(response!!).jsonObject
        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        val result = obj["result"]!!.jsonObject
        assertEquals("connected", result["status"]?.jsonPrimitive?.content)
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `server handles invalid JSON gracefully`() {
        val s = createServer()
        s.start()
        Thread.sleep(300)
        val (client, messages) = createClient(s.port)
        client.connectBlocking(2, TimeUnit.SECONDS)

        client.send("not valid json")

        val response = messages.poll(2, TimeUnit.SECONDS)
        assertNotNull(response)
        val error = json.parseToJsonElement(response!!).jsonObject["error"]!!.jsonObject
        assertEquals(-32700, error["code"]?.jsonPrimitive?.int)
    }

    // ── Concurrency ──

    @Test
    fun `server handles multiple concurrent connections`() {
        val s = createServer()
        s.start()
        Thread.sleep(300)
        val (client1, messages1) = createClient(s.port)
        val (client2, messages2) = createClient(s.port)
        client1.connectBlocking(2, TimeUnit.SECONDS)
        client2.connectBlocking(2, TimeUnit.SECONDS)

        client1.send("""{"jsonrpc":"2.0","id":1,"method":"getConnectionStatus","params":{}}""")
        client2.send("""{"jsonrpc":"2.0","id":2,"method":"getUnshieldedAddress","params":{}}""")

        val resp1 = messages1.poll(2, TimeUnit.SECONDS)
        val resp2 = messages2.poll(2, TimeUnit.SECONDS)
        assertNotNull("Client 1 should get response", resp1)
        assertNotNull("Client 2 should get response", resp2)
        assertEquals(1, json.parseToJsonElement(resp1!!).jsonObject["id"]?.jsonPrimitive?.int)
        assertEquals(2, json.parseToJsonElement(resp2!!).jsonObject["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `server handles multiple messages on same connection`() {
        val s = createServer()
        s.start()
        Thread.sleep(300)
        val (client, messages) = createClient(s.port)
        client.connectBlocking(2, TimeUnit.SECONDS)

        client.send("""{"jsonrpc":"2.0","id":1,"method":"getConnectionStatus","params":{}}""")
        client.send("""{"jsonrpc":"2.0","id":2,"method":"getDustAddress","params":{}}""")

        val resp1 = messages.poll(2, TimeUnit.SECONDS)
        val resp2 = messages.poll(2, TimeUnit.SECONDS)
        assertNotNull("Should get first response", resp1)
        assertNotNull("Should get second response", resp2)
    }
}
