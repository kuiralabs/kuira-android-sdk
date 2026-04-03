package com.midnight.kuira.core.connector

import android.util.Log
import com.midnight.kuira.core.connector.transport.ConnectorBinder
import com.midnight.kuira.core.connector.transport.ConnectorJsBridge
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Orchestrates the connector lifecycle and all transport layers.
 *
 * After [start], provides access to three transports:
 * - [binder] — for Android Bound Service (direct Kotlin API)
 * - [jsBridge] — for WebView injection (window.midnight)
 * - WebSocket server on localhost (for CLI/web dApps)
 */
class ConnectorManager(
    private val networkConfig: NetworkConfig,
    private val approvalManager: ApprovalManager = ApprovalManager { true },
) {
    companion object {
        private const val TAG = "ConnectorMgr"
    }

    private var server: ConnectorWebSocketServer? = null
    private var scope: CoroutineScope? = null

    /** Bound Service transport — direct API + JSON-RPC via Binder */
    var binder: ConnectorBinder? = null
        private set

    /** WebView JS Bridge transport — inject into WebView as window.midnight */
    var jsBridge: ConnectorJsBridge? = null
        private set

    val isRunning: Boolean get() = server != null

    val port: Int get() = server?.port ?: ConnectorWebSocketServer.DEFAULT_PORT

    fun start(
        walletAddresses: WalletAddresses,
        balanceProvider: BalanceProvider? = null,
        serverPort: Int = ConnectorWebSocketServer.DEFAULT_PORT,
    ) {
        if (isRunning) {
            Log.w(TAG, "Already running on port $port")
            return
        }

        val handler = ConnectedAPIHandler(
            networkConfig = networkConfig,
            walletAddresses = walletAddresses,
            balanceProvider = balanceProvider,
        )
        val router = JsonRpcRouter(handler, approvalManager)
        val serverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // WebSocket transport
        val ws = ConnectorWebSocketServer(router, serverScope, serverPort)
        ws.start()

        // Bound Service transport
        binder = ConnectorBinder(handler, router)

        // WebView JS Bridge transport
        jsBridge = ConnectorJsBridge(router, serverScope)

        server = ws
        scope = serverScope
        Log.d(TAG, "Connector started — WS:${ws.port}, Binder:ready, JSBridge:ready")
    }

    fun stop() {
        try {
            server?.stop(1000)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server", e)
        }
        scope?.cancel()
        server = null
        binder = null
        jsBridge = null
        scope = null
        Log.d(TAG, "Connector stopped")
    }
}
