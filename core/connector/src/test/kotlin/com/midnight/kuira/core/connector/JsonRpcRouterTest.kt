package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.DustBalance
import com.midnight.kuira.core.connector.model.HistoryEntry
import com.midnight.kuira.core.connector.model.SignDataOptions
import com.midnight.kuira.core.connector.model.SignEncoding
import com.midnight.kuira.core.connector.model.SignatureResult
import com.midnight.kuira.core.connector.model.TxStatus
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class JsonRpcRouterTest {

    private lateinit var router: JsonRpcRouter
    private val json = Json { ignoreUnknownKeys = true }
    private val nightToken = "0".repeat(64)

    private fun rpc(method: String, params: String = "{}"): String =
        """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""

    private fun parseResult(response: String): JsonElement {
        val obj = json.parseToJsonElement(response).jsonObject
        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        assertNull("Expected no error in response", obj["error"])
        return obj["result"]!!
    }

    private fun parseError(response: String): JsonObject {
        val obj = json.parseToJsonElement(response).jsonObject
        assertNull("Expected no result in error response", obj["result"])
        return obj["error"]!!.jsonObject
    }

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
            submitTransactionFn = { /* no-op */ },
            makeTransferFn = { _, _ -> "transfer_tx_hex" },
            balanceUnsealedFn = { tx, _ -> "balanced_$tx" },
            balanceSealedFn = { tx, _ -> "sealed_$tx" },
            makeIntentFn = { _, _, _ -> "intent_tx_hex" },
            getTxHistoryFn = { _, _ ->
                listOf(
                    HistoryEntry("0xabc", TxStatus.Pending),
                    HistoryEntry("0xdef", TxStatus.Finalized(mapOf(0 to "Success", 1 to "Failure"))),
                )
            },
        )
        router = JsonRpcRouter(handler)
    }

    // ── Read Methods ──

    @Test
    fun `getConnectionStatus returns connected with networkId`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getConnectionStatus"))).jsonObject
        assertEquals("connected", result["status"]?.jsonPrimitive?.content)
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getConfiguration returns all required fields`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getConfiguration"))).jsonObject
        assertTrue(result["indexerUri"]!!.jsonPrimitive.content.contains("/graphql"))
        assertTrue(result["indexerWsUri"]!!.jsonPrimitive.content.startsWith("ws"))
        assertTrue(result["proverServerUri"] is JsonNull)
        assertNotNull(result["substrateNodeUri"])
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getUnshieldedAddress returns address`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getUnshieldedAddress"))).jsonObject
        assertEquals("mn_addr_undeployed1abc123", result["unshieldedAddress"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getShieldedAddresses returns address and keys`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getShieldedAddresses"))).jsonObject
        assertEquals("mn_shield-addr_undeployed1def456", result["shieldedAddress"]?.jsonPrimitive?.content)
        assertEquals("aa".repeat(32), result["shieldedCoinPublicKey"]?.jsonPrimitive?.content)
        assertEquals("bb".repeat(32), result["shieldedEncryptionPublicKey"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getDustAddress returns dust address`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getDustAddress"))).jsonObject
        assertEquals("mn_dust_undeployed1ghi789", result["dustAddress"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getUnshieldedBalances returns token amounts as strings`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getUnshieldedBalances"))).jsonObject
        assertEquals("5000000", result[nightToken]?.jsonPrimitive?.content)
    }

    @Test
    fun `getShieldedBalances returns token amounts`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getShieldedBalances"))).jsonObject
        assertEquals("10000000", result[nightToken]?.jsonPrimitive?.content)
    }

    @Test
    fun `getDustBalance returns cap and balance`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getDustBalance"))).jsonObject
        assertEquals("1000", result["cap"]?.jsonPrimitive?.content)
        assertEquals("500", result["balance"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getProvingProvider returns null proverServerUri`() = runTest {
        val result = parseResult(router.handleMessage(rpc("getProvingProvider"))).jsonObject
        assertTrue(result["proverServerUri"] is JsonNull)
    }

    @Test
    fun `hintUsage returns null result`() = runTest {
        val params = """{"methodNames":["getUnshieldedBalances","makeTransfer"]}"""
        val result = parseResult(router.handleMessage(rpc("hintUsage", params)))
        assertTrue(result is JsonNull)
    }

    // ── getTxHistory (proper serialization) ──

    @Test
    fun `getTxHistory returns entries with proper txStatus JSON structure`() = runTest {
        val params = """{"pageNumber":0,"pageSize":10}"""
        val result = parseResult(router.handleMessage(rpc("getTxHistory", params))).jsonArray
        assertEquals(2, result.size)

        // Pending entry
        val pending = result[0].jsonObject
        assertEquals("0xabc", pending["txHash"]?.jsonPrimitive?.content)
        val pendingStatus = pending["txStatus"]!!.jsonObject
        assertEquals("pending", pendingStatus["status"]?.jsonPrimitive?.content)

        // Finalized entry with executionStatus
        val finalized = result[1].jsonObject
        assertEquals("0xdef", finalized["txHash"]?.jsonPrimitive?.content)
        val finalizedStatus = finalized["txStatus"]!!.jsonObject
        assertEquals("finalized", finalizedStatus["status"]?.jsonPrimitive?.content)
        val execStatus = finalizedStatus["executionStatus"]!!.jsonObject
        assertEquals("Success", execStatus["0"]?.jsonPrimitive?.content)
        assertEquals("Failure", execStatus["1"]?.jsonPrimitive?.content)
    }

    // ── Write Methods (return {tx: string} per official API) ──

    @Test
    fun `makeTransfer returns tx wrapped in object`() = runTest {
        val params = """{"desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"1000","recipient":"addr1"}],"payFees":true}"""
        val result = parseResult(router.handleMessage(rpc("makeTransfer", params))).jsonObject
        assertEquals("transfer_tx_hex", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `makeTransfer accepts payFees inside options object`() = runTest {
        val params = """{"desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"1000","recipient":"addr1"}],"options":{"payFees":true}}"""
        val result = parseResult(router.handleMessage(rpc("makeTransfer", params))).jsonObject
        assertEquals("transfer_tx_hex", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `submitTransaction returns null`() = runTest {
        val params = """{"tx":"sealed_tx_hex"}"""
        val result = parseResult(router.handleMessage(rpc("submitTransaction", params)))
        assertTrue(result is JsonNull)
    }

    @Test
    fun `balanceUnsealedTransaction returns tx wrapped in object`() = runTest {
        val params = """{"tx":"unbalanced_tx","payFees":true}"""
        val result = parseResult(router.handleMessage(rpc("balanceUnsealedTransaction", params))).jsonObject
        assertEquals("balanced_unbalanced_tx", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `balanceSealedTransaction returns tx wrapped in object`() = runTest {
        val params = """{"tx":"unsealed_tx","payFees":true}"""
        val result = parseResult(router.handleMessage(rpc("balanceSealedTransaction", params))).jsonObject
        assertEquals("sealed_unsealed_tx", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `makeIntent with numeric intentId returns tx wrapped in object`() = runTest {
        val params = """{
            "desiredInputs":[{"kind":"shielded","type":"$nightToken","value":"500"}],
            "desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"500","recipient":"addr1"}],
            "options":{"intentId":42,"payFees":true}
        }"""
        val result = parseResult(router.handleMessage(rpc("makeIntent", params))).jsonObject
        assertEquals("intent_tx_hex", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `makeIntent with random intentId returns tx wrapped in object`() = runTest {
        val params = """{
            "desiredInputs":[],
            "desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"100","recipient":"addr1"}],
            "options":{"intentId":"random","payFees":true}
        }"""
        val result = parseResult(router.handleMessage(rpc("makeIntent", params))).jsonObject
        assertEquals("intent_tx_hex", result["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `signData returns signature result`() = runTest {
        val params = """{"data":"deadbeef","options":{"encoding":"hex"}}"""
        val result = parseResult(router.handleMessage(rpc("signData", params))).jsonObject
        assertEquals("deadbeef", result["data"]?.jsonPrimitive?.content)
        assertEquals("sig_deadbeef", result["signature"]?.jsonPrimitive?.content)
        assertEquals("vk_1", result["verifyingKey"]?.jsonPrimitive?.content)
    }

    // ── Error Handling ──

    @Test
    fun `unknown method returns error code -32601`() = runTest {
        val error = parseError(router.handleMessage(rpc("nonExistentMethod")))
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("nonExistentMethod"))
    }

    @Test
    fun `malformed JSON returns error code -32700`() = runTest {
        val error = parseError(router.handleMessage("not json at all"))
        assertEquals(-32700, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `missing method field returns error code -32600`() = runTest {
        val error = parseError(router.handleMessage("""{"jsonrpc":"2.0","id":1,"params":{}}"""))
        assertEquals(-32600, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `submitTransaction without tx param returns error code -32602`() = runTest {
        val error = parseError(router.handleMessage(rpc("submitTransaction", "{}")))
        assertEquals(-32602, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `signData without options returns error code -32602`() = runTest {
        val error = parseError(router.handleMessage(rpc("signData", """{"data":"abc"}""")))
        assertEquals(-32602, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `makeIntent without options returns error code -32602`() = runTest {
        val params = """{"desiredInputs":[],"desiredOutputs":[]}"""
        val error = parseError(router.handleMessage(rpc("makeIntent", params)))
        assertEquals(-32602, error["code"]?.jsonPrimitive?.int)
    }

    // ── JSON-RPC Protocol ──

    @Test
    fun `response preserves numeric id`() = runTest {
        val request = """{"jsonrpc":"2.0","id":42,"method":"getConnectionStatus","params":{}}"""
        val obj = json.parseToJsonElement(router.handleMessage(request)).jsonObject
        assertEquals(42, obj["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `response preserves string id`() = runTest {
        val request = """{"jsonrpc":"2.0","id":"abc-123","method":"getConnectionStatus","params":{}}"""
        val obj = json.parseToJsonElement(router.handleMessage(request)).jsonObject
        assertEquals("abc-123", obj["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `error response preserves request id`() = runTest {
        val request = """{"jsonrpc":"2.0","id":99,"method":"unknownMethod","params":{}}"""
        val obj = json.parseToJsonElement(router.handleMessage(request)).jsonObject
        assertEquals(99, obj["id"]?.jsonPrimitive?.int)
        assertNotNull(obj["error"])
    }

    @Test
    fun `request without params defaults to empty object`() = runTest {
        val request = """{"jsonrpc":"2.0","id":1,"method":"getConnectionStatus"}"""
        val result = parseResult(router.handleMessage(request)).jsonObject
        assertEquals("connected", result["status"]?.jsonPrimitive?.content)
    }

    // ── Write method unconfigured errors ──

    @Test
    fun `unconfigured write method returns ConnectorApiError through router`() = runTest {
        val unconfiguredHandler = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = WalletAddresses(
                unshieldedAddress = "addr",
                shieldedAddress = "saddr",
                shieldedCoinPublicKey = "cpk",
                shieldedEncryptionPublicKey = "epk",
                dustAddress = "daddr",
            ),
        )
        val unconfiguredRouter = JsonRpcRouter(unconfiguredHandler)
        val error = parseError(unconfiguredRouter.handleMessage(rpc("signData", """{"data":"abc","options":{"encoding":"hex"}}""")))
        assertEquals(-32603, error["code"]?.jsonPrimitive?.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("InternalError"))
    }
}
