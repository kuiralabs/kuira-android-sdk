package com.midnight.example.bboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kuira Wallet Client — how Android dApps talk to the wallet.
 *
 * Connects via WebSocket to Kuira's connector on localhost:9932.
 * Sends JSON-RPC 2.0 requests, receives responses.
 *
 * This is what a dApp developer uses. No Midnight SDK needed —
 * just this client and JSON-RPC.
 *
 * Usage:
 * ```kotlin
 * val wallet = KuiraWalletClient()
 * wallet.connect()
 * val status = wallet.call("getConnectionStatus")
 * val balances = wallet.call("getShieldedBalances")
 * wallet.disconnect()
 * ```
 */
class KuiraWalletClient(
    private val port: Int = 9932,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private var ws: WebSocketClient? = null

    val isConnected: Boolean get() = ws?.isOpen == true

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val latch = CompletableDeferred<Boolean>()

        ws = object : WebSocketClient(URI("ws://127.0.0.1:$port")) {
            override fun onOpen(handshakedata: ServerHandshake) {
                latch.complete(true)
            }
            override fun onMessage(message: String) {
                val obj = json.parseToJsonElement(message).jsonObject
                val id = obj["id"]?.jsonPrimitive?.int ?: return
                pending.remove(id)?.complete(obj)
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) {
                if (!latch.isCompleted) latch.complete(false)
                pending.values.forEach {
                    it.completeExceptionally(Exception("Connection closed"))
                }
                pending.clear()
            }
            override fun onError(ex: Exception) {
                if (!latch.isCompleted) latch.complete(false)
            }
        }
        ws?.connect()
        latch.await()
    }

    fun disconnect() {
        ws?.close()
        ws = null
    }

    /**
     * Call a ConnectedAPI method. Returns the "result" field from the response.
     *
     * @throws WalletError if the response contains an error
     */
    suspend fun call(method: String, params: JsonObject = buildJsonObject {}): JsonElement {
        val client = ws ?: throw WalletError(-1, "Not connected")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        client.send(request.toString())

        val response = deferred.await()

        val error = response["error"]?.jsonObject
        if (error != null) {
            throw WalletError(
                error["code"]?.jsonPrimitive?.int ?: -1,
                error["message"]?.jsonPrimitive?.content ?: "Unknown error",
            )
        }
        return response["result"] ?: JsonNull
    }

    // ── Convenience methods (what bboard actually calls) ──

    suspend fun getConnectionStatus(): ConnectionInfo {
        val result = call("getConnectionStatus").jsonObject
        return ConnectionInfo(
            status = result["status"]?.jsonPrimitive?.content ?: "unknown",
            networkId = result["networkId"]?.jsonPrimitive?.content,
        )
    }

    suspend fun getConfiguration(): WalletConfiguration {
        val result = call("getConfiguration").jsonObject
        return WalletConfiguration(
            indexerUri = result["indexerUri"]?.jsonPrimitive?.content ?: "",
            indexerWsUri = result["indexerWsUri"]?.jsonPrimitive?.content ?: "",
            substrateNodeUri = result["substrateNodeUri"]?.jsonPrimitive?.content ?: "",
            networkId = result["networkId"]?.jsonPrimitive?.content ?: "",
        )
    }

    suspend fun getShieldedAddresses(): ShieldedAddresses {
        val result = call("getShieldedAddresses").jsonObject
        return ShieldedAddresses(
            shieldedAddress = result["shieldedAddress"]?.jsonPrimitive?.content ?: "",
            coinPublicKey = result["shieldedCoinPublicKey"]?.jsonPrimitive?.content ?: "",
            encryptionPublicKey = result["shieldedEncryptionPublicKey"]?.jsonPrimitive?.content ?: "",
        )
    }

    suspend fun getUnshieldedAddress(): String {
        val result = call("getUnshieldedAddress").jsonObject
        return result["unshieldedAddress"]?.jsonPrimitive?.content ?: ""
    }

    suspend fun balanceUnsealedTransaction(txHex: String): String {
        val params = buildJsonObject { put("tx", txHex) }
        val result = call("balanceUnsealedTransaction", params).jsonObject
        return result["tx"]?.jsonPrimitive?.content ?: ""
    }

    suspend fun submitTransaction(txHex: String) {
        val params = buildJsonObject { put("tx", txHex) }
        call("submitTransaction", params)
    }
}

data class ConnectionInfo(val status: String, val networkId: String?)
data class WalletConfiguration(
    val indexerUri: String,
    val indexerWsUri: String,
    val substrateNodeUri: String,
    val networkId: String,
)
data class ShieldedAddresses(
    val shieldedAddress: String,
    val coinPublicKey: String,
    val encryptionPublicKey: String,
)
class WalletError(val code: Int, override val message: String) : Exception(message)
