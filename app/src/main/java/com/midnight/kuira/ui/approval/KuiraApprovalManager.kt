package com.midnight.kuira.ui.approval

import android.content.Context
import android.content.Intent
import com.midnight.kuira.core.connector.ApprovalManager
import com.midnight.kuira.core.connector.ApprovalRequest

/**
 * Real ApprovalManager that shows the approval bottom sheet Activity.
 *
 * Suspends until the user approves or rejects.
 */
class KuiraApprovalManager(
    private val context: Context,
) : ApprovalManager {

    override suspend fun requestApproval(request: ApprovalRequest): Boolean {
        val deferred = ApprovalBridge.post(request)

        val intent = Intent(context, ApprovalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        return deferred.await()
    }
}
