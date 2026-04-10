package com.midnight.kuira.ui.approval

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent

/**
 * Transparent activity that shows the approval bottom sheet.
 *
 * Launched by KuiraApprovalManager when a dApp requests a write operation.
 * The user sees the B&W approval sheet over a void/star background.
 * Tapping confirm/reject finishes the activity and completes the bridge.
 */
class ApprovalActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = ApprovalBridge.pendingRequest
        if (request == null) {
            finish()
            return
        }

        setContent {
            ApprovalSheetScreen(
                request = request,
                onApprove = {
                    ApprovalBridge.complete(true)
                    finish()
                },
                onReject = {
                    ApprovalBridge.complete(false)
                    finish()
                },
            )
        }
    }

    override fun onDestroy() {
        // If destroyed without a decision (e.g., back button), reject
        ApprovalBridge.cancel()
        super.onDestroy()
    }
}
