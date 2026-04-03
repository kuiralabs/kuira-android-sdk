package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.DustBalance
import com.midnight.kuira.core.connector.model.SignatureResult
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Simulates WebView JS Bridge transport.
 *
 * WebView bridge = JavaScript calls `window.midnight` which invokes
 * a Kotlin handler via `@JavascriptInterface`. Messages are JSON-RPC
 * strings — exactly what JsonRpcRouter.handleMessage() processes.
 *
 * These tests prove that a web dApp in Kuira's WebView can call
 * all ConnectedAPI methods via JSON-RPC string messages.
 */
class WebViewBridgeTransportTest {

    private lateinit var router: JsonRpcRouter
    private val json = Json { ignoreUnknownKeys = true }
    private val nightToken = "0".repeat(64)

    private suspend fun call(method: String, params: String = "{}"): JsonObject {
        val request = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
        val response = router.handleMessage(request)
        return json.parseToJsonElement(response).jsonObject
    }

    private fun result(response: JsonObject): JsonElement {
        assertNull(response["error"])
        return response["result"]!!
    }

    @Before
    fun setup() {
        val handler = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_test",
                shieldedAddress = "mn_shield_test",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_test",
            ),
            balanceProvider = object : BalanceProvider {
                override suspend fun getUnshieldedBalances() =
                    mapOf(nightToken to BigInteger("5000000"))
                override suspend fun getShieldedBalances() =
                    mapOf(nightToken to BigInteger("10000000"))
                override suspend fun getDustBalance() =
                    DustBalance(BigInteger("1000"), BigInteger("500"))
            },
            signDataFn = { data, _ -> SignatureResult(data, "sig_$data", "vk_1") },
            submitTransactionFn = { },
            makeTransferFn = { _, _ -> "tx_hex" },
        )
        router = JsonRpcRouter(handler)
    }

    // ── JSON-RPC string in → string out (WebView bridge pattern) ──

    @Test
    fun `webview bridge - getConnectionStatus`() = runTest {
        val r = result(call("getConnectionStatus")).jsonObject
        assertEquals("connected", r["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `webview bridge - getConfiguration`() = runTest {
        val r = result(call("getConfiguration")).jsonObject
        assertEquals("undeployed", r["networkId"]?.jsonPrimitive?.content)
        assertTrue(r.containsKey("indexerUri"))
        assertTrue(r.containsKey("indexerWsUri"))
        assertTrue(r.containsKey("proverServerUri"))
        assertTrue(r.containsKey("substrateNodeUri"))
    }

    @Test
    fun `webview bridge - getUnshieldedBalances`() = runTest {
        val r = result(call("getUnshieldedBalances")).jsonObject
        assertEquals("5000000", r[nightToken]?.jsonPrimitive?.content)
    }

    @Test
    fun `webview bridge - getShieldedBalances`() = runTest {
        val r = result(call("getShieldedBalances")).jsonObject
        assertEquals("10000000", r[nightToken]?.jsonPrimitive?.content)
    }

    @Test
    fun `webview bridge - getDustBalance`() = runTest {
        val r = result(call("getDustBalance")).jsonObject
        assertEquals("500", r["balance"]?.jsonPrimitive?.content)
    }

    @Test
    fun `webview bridge - addresses`() = runTest {
        assertEquals("mn_addr_test", result(call("getUnshieldedAddress")).jsonObject["unshieldedAddress"]?.jsonPrimitive?.content)
        assertEquals("mn_shield_test", result(call("getShieldedAddresses")).jsonObject["shieldedAddress"]?.jsonPrimitive?.content)
        assertEquals("mn_dust_test", result(call("getDustAddress")).jsonObject["dustAddress"]?.jsonPrimitive?.content)
    }

    @Test
    fun `webview bridge - signData`() = runTest {
        val params = """{"data":"deadbeef","options":{"encoding":"hex"}}"""
        val r = result(call("signData", params)).jsonObject
        assertEquals("deadbeef", r["data"]?.jsonPrimitive?.content)
        assertEquals("sig_deadbeef", r["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `webview bridge - makeTransfer returns tx object`() = runTest {
        val params = """{"desiredOutputs":[{"kind":"shielded","type":"$nightToken","value":"1000","recipient":"addr1"}]}"""
        val r = result(call("makeTransfer", params)).jsonObject
        assertEquals("tx_hex", r["tx"]?.jsonPrimitive?.content)
    }

    @Test
    fun `webview bridge - submitTransaction`() = runTest {
        val r = result(call("submitTransaction", """{"tx":"sealed_hex"}"""))
        assertTrue(r is JsonNull)
    }

    @Test
    fun `webview bridge - error response is valid JSON-RPC`() = runTest {
        val response = call("unknownMethod")
        val error = response["error"]!!.jsonObject
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
        assertEquals("2.0", response["jsonrpc"]?.jsonPrimitive?.content)
    }
}
