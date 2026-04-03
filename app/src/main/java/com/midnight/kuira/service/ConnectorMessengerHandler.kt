package com.midnight.kuira.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.midnight.kuira.core.connector.JsonRpcRouter
import com.midnight.kuira.ui.approval.ConnectionApprovalBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles JSON-RPC messages from external apps via Android Messenger IPC.
 *
 * First message triggers a connection approval prompt.
 * Subsequent messages are routed directly to the JsonRpcRouter.
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
    }

    private var connectionApproved = false

    override fun handleMessage(msg: Message) {
        if (msg.what != MSG_REQUEST) return

        val request = msg.data?.getString("request") ?: return
        val replyTo = msg.replyTo ?: return

        Log.d(TAG, "Received request: ${request.take(80)}...")

        scope.launch {
            // Gate: first message requires connection approval
            if (!connectionApproved) {
                Log.d(TAG, "Requesting connection approval")
                val approved = requestConnectionApproval()
                if (!approved) {
                    Log.d(TAG, "Connection denied by user")
                    val errorResponse = """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"PermissionRejected: Connection denied"}}"""
                    sendReply(replyTo, errorResponse)
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

        val intent = Intent(context, com.midnight.kuira.ui.approval.ConnectionApprovalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        return deferred.await()
    }
}
