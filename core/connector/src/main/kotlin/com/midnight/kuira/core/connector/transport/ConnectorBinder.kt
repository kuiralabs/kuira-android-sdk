package com.midnight.kuira.core.connector.transport

import android.os.Binder
import com.midnight.kuira.core.connector.ConnectedAPIHandler
import com.midnight.kuira.core.connector.JsonRpcRouter

/**
 * Android Binder transport for the DApp Connector.
 *
 * Native Android apps bind to ConnectorService and get this Binder,
 * which provides two access modes:
 *
 * 1. [handler] — direct Kotlin API (type-safe, fastest, no serialization)
 * 2. [handleJsonRpc] — JSON-RPC string I/O (for apps that prefer the standard protocol)
 */
class ConnectorBinder(
    val handler: ConnectedAPIHandler,
    private val router: JsonRpcRouter,
) : Binder() {

    /** JSON-RPC string in → string out. Same protocol as WebSocket. */
    suspend fun handleJsonRpc(request: String): String {
        return router.handleMessage(request)
    }
}
