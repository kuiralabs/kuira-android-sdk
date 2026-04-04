package com.midnight.kuira.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Messenger
import android.util.Log
import androidx.core.app.NotificationCompat
import com.midnight.kuira.core.connector.ConnectorManager
import com.midnight.kuira.core.connector.WalletAddresses
import com.midnight.kuira.core.connector.model.SignatureResult
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * DApp Connector Service.
 *
 * Two modes of operation:
 * 1. **Foreground** — started via startForegroundService() from MainActivity.
 *    Keeps WebSocket server alive with a notification.
 * 2. **Bound** — external apps bind via intent action "com.midnight.kuira.CONNECTOR".
 *    Returns ConnectorBinder for direct Kotlin API access.
 *
 * The connector stack (handler + router + server) starts once in onCreate()
 * and serves both modes.
 */
@AndroidEntryPoint
class ConnectorService : Service() {

    companion object {
        private const val TAG = "ConnectorSvc"
        private const val CHANNEL_ID = "kuira_connector"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ConnectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectorService::class.java))
        }
    }

    @Inject lateinit var connectorManager: ConnectorManager
    private var isForeground = false
    private var messenger: Messenger? = null

    override fun onCreate() {
        super.onCreate()
        ensureConnectorStarted()
        Log.d(TAG, "Connector service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called when started as foreground service (from MainActivity)
        if (!isForeground) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            isForeground = true
            Log.d(TAG, "Connector running as foreground service")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        ensureConnectorStarted()
        val action = intent?.action
        Log.d(TAG, "Client bound: action=$action")

        // Same-process: return ConnectorBinder (direct Kotlin API)
        // Cross-process: return Messenger (JSON-RPC over IPC)
        return if (action == "com.midnight.kuira.CONNECTOR") {
            // Cross-process bind from external app
            if (messenger == null) {
                val binder = connectorManager.binder ?: return null
                val router = com.midnight.kuira.core.connector.JsonRpcRouter(binder.handler)
                messenger = Messenger(
                    ConnectorMessengerHandler(
                        router,
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
                        applicationContext,
                    )
                )
            }
            messenger!!.binder
        } else {
            // Same-process bind (tests, in-app usage)
            connectorManager.binder
        }
    }

    override fun onDestroy() {
        connectorManager.stop()
        Log.d(TAG, "Connector service destroyed")
        super.onDestroy()
    }

    private fun ensureConnectorStarted() {
        if (!connectorManager.isRunning) {
            val testAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_undeployed1test",
                shieldedAddress = "mn_shield-addr_undeployed1test",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_undeployed1test",
            )
            connectorManager.start(
                walletAddresses = testAddresses,
                signDataFn = { data, options ->
                    // MVP: sign with a test signature
                    // Real implementation would use the crypto module
                    SignatureResult(
                        data = data,
                        signature = "test_sig_${data.take(8)}",
                        verifyingKey = "test_vk",
                    )
                },
                submitTransactionFn = { tx ->
                    Log.d(TAG, "Transaction submitted: ${tx.take(20)}...")
                },
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DApp Connector",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the DApp connector active for wallet connections"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kuira Connector")
            .setContentText("Active — ready for dApp connections")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
