package com.midnight.kuira.ui.approval

import com.midnight.kuira.core.connector.ApprovalRequest
import kotlinx.coroutines.CompletableDeferred

/**
 * Singleton bridge between the suspend ApprovalManager and the ApprovalActivity.
 *
 * Flow:
 * 1. ApprovalManager sets [pendingRequest] and [pendingResult], launches Activity
 * 2. Activity reads [pendingRequest], shows the sheet
 * 3. User taps confirm/reject → Activity calls [complete]
 * 4. ApprovalManager resumes with the result
 */
object ApprovalBridge {

    var pendingRequest: ApprovalRequest? = null
        private set

    private var pendingResult: CompletableDeferred<Boolean>? = null

    fun post(request: ApprovalRequest): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pendingRequest = request
        pendingResult = deferred
        return deferred
    }

    fun complete(approved: Boolean) {
        pendingResult?.complete(approved)
        pendingRequest = null
        pendingResult = null
    }

    fun cancel() {
        pendingResult?.complete(false)
        pendingRequest = null
        pendingResult = null
    }
}
