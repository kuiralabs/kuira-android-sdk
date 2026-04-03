package com.midnight.kuira.transport

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.connector.ConnectedAPIHandler
import com.midnight.kuira.core.connector.JsonRpcRouter
import com.midnight.kuira.core.connector.WalletAddresses
import com.midnight.kuira.core.connector.transport.ConnectorJsBridge
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real WebView JS Bridge transport test.
 *
 * Creates a real ConnectorJsBridge with @JavascriptInterface,
 * and calls the request() method directly — the same method
 * that JavaScript in a WebView would call via window.midnight.request().
 */
@RunWith(AndroidJUnit4::class)
class WebViewBridgeTransportTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var bridge: ConnectorJsBridge

    @Before
    fun setup() {
        val handler = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_webview_test",
                shieldedAddress = "mn_shield_webview_test",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_webview_test",
            ),
        )
        val router = JsonRpcRouter(handler)
        bridge = ConnectorJsBridge(router, CoroutineScope(Dispatchers.Default))
    }

    // ── @JavascriptInterface method calls (what JS does via window.midnight.request) ──

    @Test
    fun webView_request_getConnectionStatus() {
        val response = bridge.request(
            """{"jsonrpc":"2.0","id":1,"method":"getConnectionStatus","params":{}}"""
        )
        val result = json.parseToJsonElement(response).jsonObject["result"]!!.jsonObject
        assertEquals("connected", result["status"]?.jsonPrimitive?.content)
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
    }

    @Test
    fun webView_request_getConfiguration() {
        val response = bridge.request(
            """{"jsonrpc":"2.0","id":1,"method":"getConfiguration","params":{}}"""
        )
        val result = json.parseToJsonElement(response).jsonObject["result"]!!.jsonObject
        assertTrue(result["indexerUri"]!!.jsonPrimitive.content.contains("/graphql"))
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
    }

    @Test
    fun webView_request_getAddresses() {
        val response = bridge.request(
            """{"jsonrpc":"2.0","id":1,"method":"getUnshieldedAddress","params":{}}"""
        )
        val result = json.parseToJsonElement(response).jsonObject["result"]!!.jsonObject
        assertEquals("mn_addr_webview_test", result["unshieldedAddress"]?.jsonPrimitive?.content)
    }

    @Test
    fun webView_request_errorHandling() {
        val response = bridge.request(
            """{"jsonrpc":"2.0","id":1,"method":"unknownMethod","params":{}}"""
        )
        val error = json.parseToJsonElement(response).jsonObject["error"]!!.jsonObject
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun webView_request_malformedJson() {
        val response = bridge.request("not json")
        val error = json.parseToJsonElement(response).jsonObject["error"]!!.jsonObject
        assertEquals(-32700, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun webView_bridgeCanBeInjectedIntoWebView() {
        // WebView must be created on the main thread
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val webView = WebView(context)
                bridge.inject(webView)
                success = true
                webView.destroy()
            } finally {
                latch.countDown()
            }
        }
        assertTrue("Should inject within 5s", latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("Injection should succeed", success)
    }
}
