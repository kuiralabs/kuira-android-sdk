package com.midnight.kuira.core.connector

import android.util.Log
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Orchestrates the connector lifecycle: handler → router → WebSocket server.
 *
 * Call [start] with wallet addresses to begin accepting dApp connections.
 * Call [stop] to shut down. The manager owns the coroutine scope for
 * the server's message handling.
 *
 * Injected via Hilt — the Service calls start/stop.
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

        val ws = ConnectorWebSocketServer(router, serverScope, serverPort)
        ws.start()

        server = ws
        scope = serverScope
        Log.d(TAG, "Connector started on port ${ws.port}")
    }

    fun stop() {
        server?.stop(0)
        scope?.cancel()
        server = null
        scope = null
        Log.d(TAG, "Connector stopped")
    }
}
