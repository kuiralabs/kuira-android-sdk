package com.midnight.kuira.core.connector

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * WebSocket server for the DApp Connector.
 *
 * Listens on localhost:[port] for JSON-RPC 2.0 messages from dApps.
 * Each incoming text message is routed through [JsonRpcRouter],
 * and the JSON-RPC response is sent back on the same connection.
 *
 * Binds to 127.0.0.1 only — no remote connections accepted.
 *
 * @param router Routes JSON-RPC messages to ConnectedAPIHandler methods
 * @param scope Coroutine scope for launching suspend handler calls
 * @param port TCP port to listen on (default: 9932, matches CLI's `mn serve`)
 */
class ConnectorWebSocketServer(
    private val router: JsonRpcRouter,
    private val scope: CoroutineScope,
    port: Int = DEFAULT_PORT,
) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {

    companion object {
        const val DEFAULT_PORT = 9932
        private const val TAG = "ConnectorWS"
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        scope.launch {
            val response = router.handleMessage(message)
            conn.send(response)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "Server started on port $port")
    }
}
