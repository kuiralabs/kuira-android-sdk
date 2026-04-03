package com.midnight.kuira.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.app.NotificationCompat
import com.midnight.kuira.core.connector.JsonRpcRouter
import com.midnight.kuira.ui.approval.ConnectionApprovalActivity
import com.midnight.kuira.ui.approval.ConnectionApprovalBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles JSON-RPC messages from external apps via Android Messenger IPC.
 *
 * First message triggers a connection approval notification.
 * User taps notification → approval sheet → approve/deny.
 * Subsequent messages route directly to the JsonRpcRouter.
 */
class ConnectorMessengerHandler(
    private val router: JsonRpcRouter,
    private val scope: CoroutineScope,
    private val context: Context,
) : Handler(Looper.getMainLooper()) {

    companion object {
        const val MSG_REQUEST = 1
        const val MSG_RESPONSE = 2
        private const val TAG = "ConnectorMsgr"
        private const val APPROVAL_CHANNEL_ID = "kuira_approval"
        private const val APPROVAL_NOTIFICATION_ID = 1002
    }

    private var connectionApproved = false

    override fun handleMessage(msg: Message) {
        if (msg.what != MSG_REQUEST) return

        val request = msg.data?.getString("request") ?: return
        val replyTo = msg.replyTo ?: return

        Log.d(TAG, "Received request: ${request.take(80)}...")

        scope.launch {
            if (!connectionApproved) {
                Log.d(TAG, "Requesting connection approval")
                val approved = requestConnectionApproval()
                if (!approved) {
                    Log.d(TAG, "Connection denied by user")
                    sendReply(replyTo, """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"PermissionRejected: Connection denied"}}""")
                    return@launch
                }
                connectionApproved = true
                Log.d(TAG, "Connection approved")
            }

            val response = router.handleMessage(request)
            Log.d(TAG, "Sending response: ${response.take(80)}...")
            sendReply(replyTo, response)
        }
    }

    private fun sendReply(replyTo: Messenger, response: String) {
        try {
            val reply = Message.obtain(null, MSG_RESPONSE).apply {
                data = Bundle().apply { putString("response", response) }
            }
            replyTo.send(reply)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply", e)
        }
    }

    private suspend fun requestConnectionApproval(): Boolean {
        val deferred = ConnectionApprovalBridge.post()
        showApprovalNotification()
        return deferred.await()
    }

    private fun showApprovalNotification() {
        // Create high-priority notification channel for approvals
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                APPROVAL_CHANNEL_ID,
                "Connection Requests",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "DApp connection approval requests"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val intent = Intent(context, ConnectionApprovalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
            .setContentTitle("Connection request")
            .setContentText("An app wants to connect to your wallet")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(APPROVAL_NOTIFICATION_ID, notification)
    }
}
