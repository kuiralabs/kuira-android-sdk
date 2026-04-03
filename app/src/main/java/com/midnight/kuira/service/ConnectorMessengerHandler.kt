package com.midnight.kuira.service

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.midnight.kuira.core.connector.JsonRpcRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Routes JSON-RPC messages from external apps via Android Messenger IPC.
 *
 * Connection-level approval happens BEFORE binding (the dApp launches
 * ConnectionApprovalActivity via startActivityForResult). By the time
 * messages arrive here, the user has already approved the connection.
 *
 * Write operations are still gated per-action by the ApprovalManager
 * in the JsonRpcRouter.
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

    override fun handleMessage(msg: Message) {
        if (msg.what != MSG_REQUEST) return

        val request = msg.data?.getString("request") ?: return
        val replyTo = msg.replyTo ?: return

        Log.d(TAG, "Received: ${request.take(100)}")

        scope.launch {
            val response = router.handleMessage(request)
            Log.d(TAG, "Response: ${response.take(100)}")
            try {
                val reply = Message.obtain(null, MSG_RESPONSE).apply {
                    data = Bundle().apply { putString("response", response) }
                }
                replyTo.send(reply)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply", e)
            }
        }
    }
}
