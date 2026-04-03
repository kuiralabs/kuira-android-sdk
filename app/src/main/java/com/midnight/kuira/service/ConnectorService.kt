package com.midnight.kuira.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.midnight.kuira.R
import com.midnight.kuira.core.connector.ConnectorManager
import com.midnight.kuira.core.connector.WalletAddresses
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that keeps the DApp Connector WebSocket server alive.
 *
 * Starts the WebSocket server on localhost:9932 so dApps can connect.
 * Shows a persistent notification while running.
 *
 * Start with: `startForegroundService(Intent(context, ConnectorService::class.java))`
 * Stop with: `stopService(Intent(context, ConnectorService::class.java))`
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // MVP: start with test addresses — real addresses wired when wallet is loaded
        val testAddresses = WalletAddresses(
            unshieldedAddress = "mn_addr_undeployed1test",
            shieldedAddress = "mn_shield-addr_undeployed1test",
            shieldedCoinPublicKey = "aa".repeat(32),
            shieldedEncryptionPublicKey = "bb".repeat(32),
            dustAddress = "mn_dust_undeployed1test",
        )
        connectorManager.start(testAddresses)
        Log.d(TAG, "Connector service started")
    }

    override fun onDestroy() {
        connectorManager.stop()
        Log.d(TAG, "Connector service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kuira Connector")
            .setContentText("Active — ready for dApp connections")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // placeholder — use Kuira icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
