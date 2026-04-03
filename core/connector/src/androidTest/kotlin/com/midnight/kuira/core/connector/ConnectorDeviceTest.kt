package com.midnight.kuira.core.connector

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.connector.model.DustBalance
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests — runs on an actual Android device/emulator.
 *
 * Starts a real WebSocket server, connects a real WebSocket client,
 * sends real JSON-RPC messages, and verifies real responses.
 * This is the closest to what a dApp would actually do.
 */
@RunWith(AndroidJUnit4::class)
class ConnectorDeviceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val nightToken = "0".repeat(64)

    private lateinit var server: ConnectorWebSocketServer
    private lateinit var client: WebSocketClient
    private lateinit var messages: LinkedBlockingQueue<String>

    @Before
    fun setup() {
        val handler = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_undeployed1device_test",
                shieldedAddress = "mn_shield-addr_undeployed1device_test",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_undeployed1device_test",
            ),
            balanceProvider = object : BalanceProvider {
                override suspend fun getUnshieldedBalances() =
                    mapOf(nightToken to BigInteger("7000000"))
                override suspend fun getShieldedBalances() =
                    mapOf(nightToken to BigInteger("3000000"))
                override suspend fun getDustBalance() =
                    DustBalance(BigInteger("2000"), BigInteger("800"))
            },
            signDataFn = { data, _ ->
                SignatureResult(data, "device_sig_$data", "device_vk")
            },
            submitTransactionFn = { },
            makeTransferFn = { _, _ -> "device_transfer_tx" },
        )

        val router = JsonRpcRouter(handler)
        val scope = CoroutineScope(Dispatchers.Default)
        server = ConnectorWebSocketServer(router, scope, 0) // random port
        server.isReuseAddr = true
        server.start()
        Thread.sleep(500)

        messages = LinkedBlockingQueue()
        client = object : WebSocketClient(URI("ws://127.0.0.1:${server.port}")) {
            override fun onOpen(handshakedata: ServerHandshake) {}
            override fun onMessage(message: String) { messages.add(message) }
            override fun onClose(code: Int, reason: String, remote: Boolean) {}
            override fun onError(ex: Exception) {}
        }
        assertTrue("Client should connect on device", client.connectBlocking(5, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        runCatching { client.closeBlocking() }
        server.stop(1000)
    }

    private fun send(method: String, params: String = "{}"): JsonObject {
        client.send("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        val raw = messages.poll(5, TimeUnit.SECONDS)
        assertNotNull("Should receive response for $method on device", raw)
        return json.parseToJsonElement(raw!!).jsonObject
    }

    // ── Device: WebSocket connection works ──

    @Test
    fun device_webSocketConnects() {
        assertTrue(client.isOpen)
    }

    // ── Device: Read methods ──

    @Test
    fun device_getConnectionStatus() {
        val result = send("getConnectionStatus")["result"]!!.jsonObject
        assertEquals("connected", result["status"]?.jsonPrimitive?.content)
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
    }

    @Test
    fun device_getConfiguration() {
        val result = send("getConfiguration")["result"]!!.jsonObject
        assertNotNull(result["indexerUri"])
        assertNotNull(result["indexerWsUri"])
        assertNotNull(result["substrateNodeUri"])
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
    }

    @Test
    fun device_getUnshieldedBalances() {
        val result = send("getUnshieldedBalances")["result"]!!.jsonObject
        assertEquals("7000000", result[nightToken]?.jsonPrimitive?.content)
    }

    @Test
    fun device_getShieldedBalances() {
        val result = send("getShieldedBalances")["result"]!!.jsonObject
        assertEquals("3000000", result[nightToken]?.jsonPrimitive?.content)
    }

    @Test
    fun device_getDustBalance() {
        val result = send("getDustBalance")["result"]!!.jsonObject
        assertEquals("2000", result["cap"]?.jsonPrimitive?.content)
        assertEquals("800", result["balance"]?.jsonPrimitive?.content)
    }

    @Test
    fun device_getAddresses() {
        val unshielded = send("getUnshieldedAddress")["result"]!!.jsonObject
        assertEquals("mn_addr_undeployed1device_test", unshielded["unshieldedAddress"]?.jsonPrimitive?.content)

        val shielded = send("getShieldedAddresses")["result"]!!.jsonObject
        assertEquals("mn_shield-addr_undeployed1device_test", shielded["shieldedAddress"]?.jsonPrimitive?.content)

        val dust = send("getDustAddress")["result"]!!.jsonObject
        assertEquals("mn_dust_undeployed1device_test", dust["dustAddress"]?.jsonPrimitive?.content)
    }

    // ── Device: Write methods ──

    @Test
    fun device_signData() {
        val params = """{"data":"cafe","options":{"encoding":"hex"}}"""
        val result = send("signData", params)["result"]!!.jsonObject
        assertEquals("cafe", result["data"]?.jsonPrimitive?.content)
        assertEquals("device_sig_cafe", result["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun device_makeTransfer() {
        val params = """{"desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"100","recipient":"addr1"}]}"""
        val result = send("makeTransfer", params)["result"]!!.jsonObject
        assertEquals("device_transfer_tx", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun device_submitTransaction() {
        val result = send("submitTransaction", """{"tx":"sealed_hex"}""")["result"]
        assertTrue(result is JsonNull)
    }

    // ── Device: Error handling ──

    @Test
    fun device_unknownMethodReturnsError() {
        val error = send("fakeMethod")["error"]!!.jsonObject
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun device_malformedParamsReturnsError() {
        val error = send("submitTransaction", """{}""")["error"]!!.jsonObject
        assertEquals(-32602, error["code"]?.jsonPrimitive?.int)
    }

    // ── Device: Multiple messages on same connection ──

    @Test
    fun device_multipleRequestsOnSameConnection() {
        val r1 = send("getConnectionStatus")
        assertNotNull(r1["result"])

        val r2 = send("getUnshieldedBalances")
        assertNotNull(r2["result"])

        val r3 = send("signData", """{"data":"abc","options":{"encoding":"hex"}}""")
        assertNotNull(r3["result"])
    }
}
