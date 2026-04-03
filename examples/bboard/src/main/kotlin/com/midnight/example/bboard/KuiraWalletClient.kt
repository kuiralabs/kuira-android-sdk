package com.midnight.example.bboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.midnight.kuira.core.connector.model.ConnectionStatus
import com.midnight.kuira.core.connector.transport.ConnectorBinder
import kotlinx.coroutines.CompletableDeferred

/**
 * Kuira Wallet Client — how native Android dApps talk to the wallet.
 *
 * Binds to Kuira's ConnectorService via Android IPC. No WebSocket,
 * no JSON-RPC, no serialization. Direct Kotlin API calls.
 *
 * This is the recommended transport for Android-native dApps.
 *
 * Usage:
 * ```kotlin
 * val wallet = KuiraWalletClient(context)
 * wallet.bind()
 * val status = wallet.getConnectionStatus()
 * val addresses = wallet.getShieldedAddresses()
 * wallet.unbind()
 * ```
 */
class KuiraWalletClient(private val context: Context) {

    private var binder: ConnectorBinder? = null
    private var bindDeferred: CompletableDeferred<Boolean>? = null

    val isConnected: Boolean get() = binder != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? ConnectorBinder
            bindDeferred?.complete(binder != null)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    suspend fun bind(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        bindDeferred = deferred

        val intent = Intent("com.midnight.kuira.CONNECTOR").apply {
            setPackage("com.midnight.kuira")
        }
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) return false

        return deferred.await()
    }

    fun unbind() {
        context.unbindService(connection)
        binder = null
    }

    // ── Direct Kotlin API (type-safe, no serialization) ──

    fun getConnectionStatus(): ConnectionInfo {
        val handler = binder?.handler ?: throw WalletError("Not bound to Kuira")
        val status = handler.getConnectionStatus()
        return when (status) {
            is ConnectionStatus.Connected -> ConnectionInfo("connected", status.networkId)
            is ConnectionStatus.Disconnected -> ConnectionInfo("disconnected", null)
        }
    }

    fun getConfiguration(): WalletConfiguration {
        val handler = binder?.handler ?: throw WalletError("Not bound to Kuira")
        val config = handler.getConfiguration()
        return WalletConfiguration(
            indexerUri = config.indexerUri,
            indexerWsUri = config.indexerWsUri,
            substrateNodeUri = config.substrateNodeUri,
            networkId = config.networkId,
        )
    }

    fun getShieldedAddresses(): ShieldedAddresses {
        val handler = binder?.handler ?: throw WalletError("Not bound to Kuira")
        val result = handler.getShieldedAddresses()
        return ShieldedAddresses(
            shieldedAddress = result.shieldedAddress,
            coinPublicKey = result.shieldedCoinPublicKey,
            encryptionPublicKey = result.shieldedEncryptionPublicKey,
        )
    }

    fun getUnshieldedAddress(): String {
        val handler = binder?.handler ?: throw WalletError("Not bound to Kuira")
        return handler.getUnshieldedAddress().unshieldedAddress
    }

    suspend fun balanceUnsealedTransaction(txHex: String): String {
        val handler = binder?.handler ?: throw WalletError("Not bound to Kuira")
        return handler.balanceUnsealedTransaction(txHex)
    }

    suspend fun submitTransaction(txHex: String) {
        val handler = binder?.handler ?: throw WalletError("Not bound to Kuira")
        handler.submitTransaction(txHex)
    }

    suspend fun signData(data: String, encoding: String = "hex"): SignResult {
        val handler = binder?.handler ?: throw WalletError("Not bound to Kuira")
        val result = handler.signData(
            data,
            com.midnight.kuira.core.connector.model.SignDataOptions(
                com.midnight.kuira.core.connector.model.SignEncoding.valueOf(encoding.uppercase()),
            ),
        )
        return SignResult(result.data, result.signature, result.verifyingKey)
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
data class SignResult(val data: String, val signature: String, val verifyingKey: String)
class WalletError(override val message: String) : Exception(message)
