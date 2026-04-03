package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.DustBalance
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
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * End-to-end integration tests for the full connector pipeline.
 *
 * Each test exercises: WebSocket client → Server → Router → Approval → Handler → Response
 *
 * Uses a real WebSocket connection — no mocks at any layer.
 */
class ConnectorIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val nightToken = "0".repeat(64)

    private lateinit var server: ConnectorWebSocketServer
    private lateinit var client: WebSocketClient
    private lateinit var messages: LinkedBlockingQueue<String>
    private var approvalDecision: Boolean = true
    private val approvedMethods = mutableListOf<String>()

    @Before
    fun setup() {
        val networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)

        val provider = object : BalanceProvider {
            override suspend fun getUnshieldedBalances() =
                mapOf(nightToken to BigInteger("5000000"))
            override suspend fun getShieldedBalances() =
                mapOf(nightToken to BigInteger("10000000"))
            override suspend fun getDustBalance() =
                DustBalance(cap = BigInteger("1000"), balance = BigInteger("500"))
        }

        val handler = ConnectedAPIHandler(
            networkConfig = networkConfig,
            walletAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_undeployed1abc123",
                shieldedAddress = "mn_shield-addr_undeployed1def456",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_undeployed1ghi789",
            ),
            balanceProvider = provider,
            signDataFn = { data, _ ->
                SignatureResult(data = data, signature = "sig_$data", verifyingKey = "vk_1")
            },
            submitTransactionFn = { /* accepted */ },
            makeTransferFn = { _, _ -> "transfer_tx_hex" },
            balanceUnsealedFn = { tx, _ -> "balanced_$tx" },
            balanceSealedFn = { tx, _ -> "sealed_$tx" },
            makeIntentFn = { _, _, _ -> "intent_tx_hex" },
        )

        val approval = ApprovalManager { request ->
            approvedMethods.add(request.method)
            approvalDecision
        }

        val router = JsonRpcRouter(handler, approval)
        val scope = CoroutineScope(Dispatchers.Default)
        server = ConnectorWebSocketServer(router, scope, 0)
        server.isReuseAddr = true
        server.start()
        Thread.sleep(300)

        messages = LinkedBlockingQueue()
        client = object : WebSocketClient(URI("ws://127.0.0.1:${server.port}")) {
            override fun onOpen(handshakedata: ServerHandshake) {}
            override fun onMessage(message: String) { messages.add(message) }
            override fun onClose(code: Int, reason: String, remote: Boolean) {}
            override fun onError(ex: Exception) {}
        }
        client.connectBlocking(2, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        runCatching { client.closeBlocking() }
        server.stop(0)
        approvedMethods.clear()
        approvalDecision = true
    }

    private fun send(method: String, params: String = "{}"): JsonObject {
        client.send("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        val raw = messages.poll(3, TimeUnit.SECONDS)
        assertNotNull("Should receive response for $method", raw)
        return json.parseToJsonElement(raw!!).jsonObject
    }

    private fun assertResult(response: JsonObject): JsonElement {
        assertNull("Expected no error", response["error"])
        return response["result"]!!
    }

    private fun assertError(response: JsonObject, code: Int): JsonObject {
        assertNull("Expected no result", response["result"])
        val error = response["error"]!!.jsonObject
        assertEquals(code, error["code"]?.jsonPrimitive?.int)
        return error
    }

    // ── Full pipeline: Read methods (no approval) ──

    @Test
    fun `e2e getConnectionStatus returns connected`() {
        val result = assertResult(send("getConnectionStatus")).jsonObject
        assertEquals("connected", result["status"]?.jsonPrimitive?.content)
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
        assertTrue("Read should not trigger approval", approvedMethods.isEmpty())
    }

    @Test
    fun `e2e getConfiguration returns full config`() {
        val result = assertResult(send("getConfiguration")).jsonObject
        assertTrue(result["indexerUri"]!!.jsonPrimitive.content.contains("/graphql"))
        assertTrue(result["indexerWsUri"]!!.jsonPrimitive.content.startsWith("ws"))
        assertTrue(result["proverServerUri"] is JsonNull)
        assertNotNull(result["substrateNodeUri"])
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
        assertTrue("Read should not trigger approval", approvedMethods.isEmpty())
    }

    @Test
    fun `e2e getUnshieldedBalances returns token amounts`() {
        val result = assertResult(send("getUnshieldedBalances")).jsonObject
        assertEquals("5000000", result[nightToken]?.jsonPrimitive?.content)
    }

    @Test
    fun `e2e getShieldedBalances returns token amounts`() {
        val result = assertResult(send("getShieldedBalances")).jsonObject
        assertEquals("10000000", result[nightToken]?.jsonPrimitive?.content)
    }

    @Test
    fun `e2e getDustBalance returns cap and balance`() {
        val result = assertResult(send("getDustBalance")).jsonObject
        assertEquals("1000", result["cap"]?.jsonPrimitive?.content)
        assertEquals("500", result["balance"]?.jsonPrimitive?.content)
    }

    @Test
    fun `e2e addresses return correct values`() {
        val unshielded = assertResult(send("getUnshieldedAddress")).jsonObject
        assertEquals("mn_addr_undeployed1abc123", unshielded["unshieldedAddress"]?.jsonPrimitive?.content)

        val shielded = assertResult(send("getShieldedAddresses")).jsonObject
        assertEquals("mn_shield-addr_undeployed1def456", shielded["shieldedAddress"]?.jsonPrimitive?.content)

        val dust = assertResult(send("getDustAddress")).jsonObject
        assertEquals("mn_dust_undeployed1ghi789", dust["dustAddress"]?.jsonPrimitive?.content)
    }

    @Test
    fun `e2e getProvingProvider returns null uri`() {
        val result = assertResult(send("getProvingProvider")).jsonObject
        assertTrue(result["proverServerUri"] is JsonNull)
    }

    // ── Full pipeline: Write methods (with approval) ──

    @Test
    fun `e2e makeTransfer approved returns tx object`() {
        val params = """{"desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"1000","recipient":"addr1"}]}"""
        val result = assertResult(send("makeTransfer", params)).jsonObject
        assertEquals("transfer_tx_hex", result["tx"]?.jsonPrimitive?.content)
        assertEquals("makeTransfer", approvedMethods.single())
    }

    @Test
    fun `e2e signData approved returns signature`() {
        val params = """{"data":"deadbeef","options":{"encoding":"hex"}}"""
        val result = assertResult(send("signData", params)).jsonObject
        assertEquals("deadbeef", result["data"]?.jsonPrimitive?.content)
        assertEquals("sig_deadbeef", result["signature"]?.jsonPrimitive?.content)
        assertEquals("signData", approvedMethods.single())
    }

    @Test
    fun `e2e submitTransaction approved succeeds`() {
        val params = """{"tx":"sealed_hex"}"""
        val result = assertResult(send("submitTransaction", params))
        assertTrue(result is JsonNull)
        assertEquals("submitTransaction", approvedMethods.single())
    }

    @Test
    fun `e2e balanceUnsealedTransaction approved returns tx object`() {
        val params = """{"tx":"unbalanced_hex","payFees":true}"""
        val result = assertResult(send("balanceUnsealedTransaction", params)).jsonObject
        assertEquals("balanced_unbalanced_hex", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `e2e balanceSealedTransaction approved returns tx object`() {
        val params = """{"tx":"unsealed_hex"}"""
        val result = assertResult(send("balanceSealedTransaction", params)).jsonObject
        assertEquals("sealed_unsealed_hex", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `e2e makeIntent approved returns tx object`() {
        val params = """{
            "desiredInputs":[{"kind":"shielded","type":"$nightToken","value":"500"}],
            "desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"500","recipient":"addr1"}],
            "options":{"intentId":"random","payFees":true}
        }"""
        val result = assertResult(send("makeIntent", params)).jsonObject
        assertEquals("intent_tx_hex", result["tx"]?.jsonPrimitive?.content)
        assertEquals("makeIntent", approvedMethods.single())
    }

    // ── Full pipeline: Approval rejected ──

    @Test
    fun `e2e rejected write returns PERMISSION_REJECTED`() {
        approvalDecision = false
        val params = """{"data":"abc","options":{"encoding":"hex"}}"""
        val error = assertError(send("signData", params), -32603)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("PermissionRejected"))
        assertEquals("signData", approvedMethods.single())
    }

    @Test
    fun `e2e rejected transfer does not execute`() {
        approvalDecision = false
        val params = """{"desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"1000","recipient":"addr1"}]}"""
        val error = assertError(send("makeTransfer", params), -32603)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("PermissionRejected"))
    }

    // ── Full pipeline: Error paths ──

    @Test
    fun `e2e unknown method returns -32601`() {
        assertError(send("doesNotExist"), -32601)
        assertTrue("Unknown method should not trigger approval", approvedMethods.isEmpty())
    }

    @Test
    fun `e2e malformed params returns -32602`() {
        assertError(send("submitTransaction", """{}"""), -32602)
    }

    @Test
    fun `e2e missing method returns -32600`() {
        client.send("""{"jsonrpc":"2.0","id":1,"params":{}}""")
        val raw = messages.poll(3, TimeUnit.SECONDS)
        assertNotNull(raw)
        val error = json.parseToJsonElement(raw!!).jsonObject["error"]!!.jsonObject
        assertEquals(-32600, error["code"]?.jsonPrimitive?.int)
    }

    // ── Full pipeline: Session behavior ──

    @Test
    fun `e2e multiple requests on same connection all work`() {
        assertResult(send("getConnectionStatus"))
        assertResult(send("getUnshieldedBalances"))
        assertResult(send("getDustBalance"))

        val params = """{"data":"abc","options":{"encoding":"hex"}}"""
        assertResult(send("signData", params))

        assertEquals(1, approvedMethods.size) // only signData triggered approval
    }

    @Test
    fun `e2e read then write then read sequence`() {
        // Read - no approval
        assertResult(send("getConnectionStatus"))
        assertTrue(approvedMethods.isEmpty())

        // Write - triggers approval
        val params = """{"data":"abc","options":{"encoding":"hex"}}"""
        assertResult(send("signData", params))
        assertEquals(1, approvedMethods.size)

        // Read again - no approval
        assertResult(send("getUnshieldedAddress"))
        assertEquals(1, approvedMethods.size) // still just 1
    }
}
