package com.midnight.kuira.core.compact

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Connects to a DApp Connector wallet (e.g., `mn serve`) via WebSocket JSON-RPC.
 *
 * Provides methods to balance and submit proven transactions.
 *
 * Usage:
 * ```
 * val client = DAppConnectorClient("ws://10.0.2.2:9932")
 * client.connect()
 * val balanced = client.balanceTransaction(provenTxHex)
 * client.submitTransaction(balanced)
 * client.disconnect()
 * ```
 */
class DAppConnectorClient(private val wsUrl: String) : TransactionBalancer {

    private var ws: WebSocketClient? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (Result<JSONObject>) -> Unit>()

    suspend fun connect(timeoutMs: Long = 10_000) = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val client = object : WebSocketClient(URI(wsUrl)) {
                override fun onOpen(handshake: ServerHandshake?) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onMessage(message: String) {
                    handleMessage(message)
                }
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    pending.values.forEach { it(Result.failure(Exception("Connection closed: $reason"))) }
                    pending.clear()
                }
                override fun onError(ex: Exception?) {
                    if (cont.isActive) cont.resumeWithException(ex ?: Exception("WebSocket error"))
                    pending.values.forEach { it(Result.failure(ex ?: Exception("WebSocket error"))) }
                    pending.clear()
                }
            }
            client.connectionLostTimeout = 0
            ws = client
            client.connect()
        }
    }

    fun disconnect() {
        ws?.close()
        ws = null
    }

    /** Get wallet configuration (network, endpoints). */
    suspend fun getConfiguration(): JSONObject = call("getConfiguration")

    override suspend fun balanceTransaction(provenTxHex: String): String =
        balanceTransaction(provenTxHex, payFees = true)

    /**
     * Balance a proven transaction (adds dust fees).
     *
     * @param provenTxHex Hex-encoded proven transaction
     * @param payFees Whether to pay fees
     * @return Hex-encoded balanced transaction ready for submission
     */
    suspend fun balanceTransaction(provenTxHex: String, payFees: Boolean): String {
        val params = JSONObject().put("tx", provenTxHex).put("payFees", payFees)
        val result = call("balanceUnsealedTransaction", params)
        // Response: {"jsonrpc":"2.0","id":N,"result":{"tx":"...hex..."}}
        val resultObj = result.get("result")
        return if (resultObj is JSONObject) {
            resultObj.getString("tx")
        } else {
            resultObj.toString()
        }
    }

    override suspend fun submitTransaction(balancedTxHex: String) {
        val params = JSONObject().put("tx", balancedTxHex)
        call("submitTransaction", params)
    }

    private suspend fun call(
        method: String,
        params: Any = JSONObject(), // JSONObject for named params, JSONArray for positional
        timeoutMs: Long = 120_000,
    ): JSONObject = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val id = nextId.getAndIncrement()
            pending[id] = { result ->
                result.fold(
                    onSuccess = { cont.resume(it) },
                    onFailure = { cont.resumeWithException(it) },
                )
            }

            val request = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }

            ws?.send(request.toString())
                ?: cont.resumeWithException(Exception("Not connected"))
        }
    }

    private fun handleMessage(message: String) {
        val json = try {
            JSONObject(message)
        } catch (e: Exception) {
            return // Skip malformed messages
        }

        // Skip notifications (progress updates, no id)
        if (!json.has("id")) return

        val id = json.getInt("id")
        val callback = pending.remove(id) ?: return

        if (json.has("error")) {
            val error = json.getJSONObject("error")
            val data = error.opt("data")
            val dataStr = if (data != null) " | data: $data" else ""
            callback(Result.failure(Exception("RPC error ${error.optInt("code")}: ${error.optString("message")}$dataStr")))
        } else {
            callback(Result.success(json))
        }
    }
}
