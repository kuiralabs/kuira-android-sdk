package com.midnight.kuira.ui.approval

import kotlinx.coroutines.CompletableDeferred

/**
 * Bridge between the ConnectorMessengerHandler and the ConnectionApprovalActivity.
 * Same pattern as ApprovalBridge but for the initial connection handshake.
 */
object ConnectionApprovalBridge {

    private var pendingResult: CompletableDeferred<Boolean>? = null

    fun post(): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pendingResult = deferred
        return deferred
    }

    fun complete(approved: Boolean) {
        pendingResult?.complete(approved)
        pendingResult = null
    }

    fun cancel() {
        pendingResult?.complete(false)
        pendingResult = null
    }
}
