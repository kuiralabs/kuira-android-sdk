package com.midnight.example.bboard

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kuira Wallet Client for Android dApps.
 *
 * Two-phase connection:
 * 1. [requestApproval] — launch Kuira's approval Activity (user approves)
 * 2. [bind] — bind to ConnectorService via Messenger IPC
 *
 * After binding, call wallet methods via [call] or convenience methods.
 *
 * The dApp (foreground) launches the approval Activity — this is the
 * standard Android pattern used by Solana MWA, MetaMask, Phantom.
 */
class KuiraWalletClient(private val context: Context) {

    companion object {
        private const val MSG_REQUEST = 1
        private const val MSG_RESPONSE = 2
        private const val TAG = "KuiraClient"

        /** Intent action for launching Kuira's connection approval screen */
        const val ACTION_CONNECT_APPROVAL = "com.midnight.kuira.CONNECT_APPROVAL"
        const val KUIRA_PACKAGE = "com.midnight.kuira"
        const val ACTION_CONNECTOR = "com.midnight.kuira.CONNECTOR"

        /**
         * Create the Intent that the dApp should launch via startActivityForResult.
         * Returns RESULT_OK if approved, RESULT_CANCELED if denied.
         */
        fun createApprovalIntent(): Intent {
            return Intent(ACTION_CONNECT_APPROVAL).apply {
                setPackage(KUIRA_PACKAGE)
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    private var serviceMessenger: Messenger? = null
    private var bindDeferred: CompletableDeferred<Boolean>? = null

    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_RESPONSE) return
            val response = msg.data?.getString("response") ?: return
            Log.d(TAG, "Reply: ${response.take(100)}")
            val obj = json.parseToJsonElement(response).jsonObject
            val id = obj["id"]?.jsonPrimitive?.int ?: return
            pending.remove(id)?.complete(obj)
        }
    })

    val isConnected: Boolean get() = serviceMessenger != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            serviceMessenger = Messenger(service)
            bindDeferred?.complete(true)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            serviceMessenger = null
        }
    }

    /**
     * Check if approval result was granted.
     * Call this in your ActivityResultCallback.
     */
    fun isApproved(resultCode: Int): Boolean {
        return resultCode == Activity.RESULT_OK
    }

    /**
     * Bind to Kuira's ConnectorService after approval.
     * Call this ONLY after [requestApproval] returned RESULT_OK.
     */
    suspend fun bind(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        bindDeferred = deferred

        val intent = Intent(ACTION_CONNECTOR).apply {
            setPackage(KUIRA_PACKAGE)
        }
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.e(TAG, "bindService returned false")
            return false
        }

        return deferred.await()
    }

    fun unbind() {
        try { context.unbindService(connection) } catch (_: Exception) {}
        serviceMessenger = null
    }

    /**
     * Send a JSON-RPC request to Kuira and await the response.
     */
    suspend fun call(method: String, params: JsonObject = buildJsonObject {}): JsonElement {
        val messenger = serviceMessenger ?: throw WalletError("Not bound to Kuira")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        Log.d(TAG, "Sending: ${request.toString().take(100)}")

        val msg = Message.obtain(null, MSG_REQUEST).apply {
            data = Bundle().apply { putString("request", request.toString()) }
            replyTo = replyMessenger
        }
        messenger.send(msg)

        val response = deferred.await()
        val error = response["error"]?.jsonObject
        if (error != null) {
            throw WalletError(error["message"]?.jsonPrimitive?.content ?: "Unknown error")
        }
        return response["result"] ?: JsonNull
    }

    // ── Convenience methods ──

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

    suspend fun signData(data: String, encoding: String = "hex"): SignResult {
        val params = buildJsonObject {
            put("data", data)
            put("options", buildJsonObject { put("encoding", encoding) })
        }
        val result = call("signData", params).jsonObject
        return SignResult(
            result["data"]?.jsonPrimitive?.content ?: "",
            result["signature"]?.jsonPrimitive?.content ?: "",
            result["verifyingKey"]?.jsonPrimitive?.content ?: "",
        )
    }
}

data class ConnectionInfo(val status: String, val networkId: String?)
data class WalletConfiguration(
    val indexerUri: String, val indexerWsUri: String,
    val substrateNodeUri: String, val networkId: String,
)
data class ShieldedAddresses(
    val shieldedAddress: String, val coinPublicKey: String, val encryptionPublicKey: String,
)
data class SignResult(val data: String, val signature: String, val verifyingKey: String)
class WalletError(override val message: String) : Exception(message)
