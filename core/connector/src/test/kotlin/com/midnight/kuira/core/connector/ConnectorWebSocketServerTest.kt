package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.SignDataOptions
import com.midnight.kuira.core.connector.model.SignEncoding
import com.midnight.kuira.core.connector.model.SignatureResult
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

    private val testAddresses = WalletAddresses(
        unshieldedAddress = "mn_addr_test",
        shieldedAddress = "mn_shield_test",
        shieldedCoinPublicKey = "aa".repeat(32),
        shieldedEncryptionPublicKey = "bb".repeat(32),
        dustAddress = "mn_dust_test",
    )

    private fun createServer(
        port: Int = 0,
        handler: ConnectedAPIHandler? = null,
    ): ConnectorWebSocketServer {
        val h = handler ?: ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = testAddresses,
        )
        val router = JsonRpcRouter(h)
        val scope = CoroutineScope(Dispatchers.Default)
        return ConnectorWebSocketServer(router, scope, port).also { server = it }
    }

    private fun startAndConnect(
        handler: ConnectedAPIHandler? = null,
    ): Triple<ConnectorWebSocketServer, WebSocketClient, LinkedBlockingQueue<String>> {
        val s = createServer(handler = handler)
        s.start()
        Thread.sleep(300)
        val (client, messages) = createClient(s.port)
        client.connectBlocking(2, TimeUnit.SECONDS)
        return Triple(s, client, messages)
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
    fun `server handles multiple messages on same connection with correct content`() {
        val s = createServer()
        s.start()
        Thread.sleep(300)
        val (client, messages) = createClient(s.port)
        client.connectBlocking(2, TimeUnit.SECONDS)

        client.send("""{"jsonrpc":"2.0","id":1,"method":"getConnectionStatus","params":{}}""")
        client.send("""{"jsonrpc":"2.0","id":2,"method":"getDustAddress","params":{}}""")

        val responses = mutableListOf<JsonObject>()
        repeat(2) {
            val raw = messages.poll(2, TimeUnit.SECONDS)
            assertNotNull("Should get response ${it + 1}", raw)
            responses.add(json.parseToJsonElement(raw!!).jsonObject)
        }
        // Responses may arrive out of order — find by id
        val byId = responses.associateBy { it["id"]?.jsonPrimitive?.int }
        assertNotNull("Should have response for id=1", byId[1])
        assertNotNull("Should have response for id=2", byId[2])
        assertEquals("connected", byId[1]!!["result"]!!.jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals("mn_dust_test", byId[2]!!["result"]!!.jsonObject["dustAddress"]?.jsonPrimitive?.content)
    }

    // ── Write method through WebSocket ──

    @Test
    fun `write method routes through WebSocket end-to-end`() {
        val handler = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = testAddresses,
            signDataFn = { data, _ ->
                SignatureResult(data = data, signature = "sig_$data", verifyingKey = "vk_test")
            },
        )
        val (_, client, messages) = startAndConnect(handler)

        client.send("""{"jsonrpc":"2.0","id":5,"method":"signData","params":{"data":"cafe","options":{"encoding":"hex"}}}""")

        val response = messages.poll(2, TimeUnit.SECONDS)
        assertNotNull("Should receive signData response", response)
        val result = json.parseToJsonElement(response!!).jsonObject["result"]!!.jsonObject
        assertEquals("cafe", result["data"]?.jsonPrimitive?.content)
        assertEquals("sig_cafe", result["signature"]?.jsonPrimitive?.content)
        assertEquals("vk_test", result["verifyingKey"]?.jsonPrimitive?.content)
    }

    // ── Error routing through WebSocket ──

    @Test
    fun `unknown method returns -32601 through WebSocket`() {
        val (_, client, messages) = startAndConnect()

        client.send("""{"jsonrpc":"2.0","id":10,"method":"doesNotExist","params":{}}""")

        val response = messages.poll(2, TimeUnit.SECONDS)
        assertNotNull(response)
        val error = json.parseToJsonElement(response!!).jsonObject["error"]!!.jsonObject
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("doesNotExist"))
    }

    @Test
    fun `unconfigured write method returns -32603 through WebSocket`() {
        val (_, client, messages) = startAndConnect()

        client.send("""{"jsonrpc":"2.0","id":11,"method":"signData","params":{"data":"abc","options":{"encoding":"hex"}}}""")

        val response = messages.poll(2, TimeUnit.SECONDS)
        assertNotNull(response)
        val error = json.parseToJsonElement(response!!).jsonObject["error"]!!.jsonObject
        assertEquals(-32603, error["code"]?.jsonPrimitive?.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("InternalError"))
    }

    // ── Resilience ──

    @Test
    fun `client disconnect does not crash server`() {
        val s = createServer()
        s.start()
        Thread.sleep(300)

        // Connect and disconnect first client
        val (client1, _) = createClient(s.port)
        client1.connectBlocking(2, TimeUnit.SECONDS)
        client1.closeBlocking()
        Thread.sleep(100)

        // Second client should still be able to connect and get responses
        val (client2, messages2) = createClient(s.port)
        assertTrue("Client 2 should connect after client 1 disconnect", client2.connectBlocking(2, TimeUnit.SECONDS))
        client2.send("""{"jsonrpc":"2.0","id":1,"method":"getConnectionStatus","params":{}}""")
        val response = messages2.poll(2, TimeUnit.SECONDS)
        assertNotNull("Should receive response after peer disconnect", response)
    }

    @Test
    fun `empty message returns parse error`() {
        val (_, client, messages) = startAndConnect()

        client.send("")

        val response = messages.poll(2, TimeUnit.SECONDS)
        assertNotNull(response)
        val obj = json.parseToJsonElement(response!!).jsonObject
        assertNotNull("Should return an error", obj["error"])
    }

    // ── Constants ──

    @Test
    fun `default port is 9932`() {
        assertEquals(9932, ConnectorWebSocketServer.DEFAULT_PORT)
    }
}
