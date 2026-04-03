package com.midnight.kuira.core.connector

import kotlinx.serialization.json.JsonObject

/**
 * Categorizes the risk/impact of a dApp operation.
 *
 * The app-layer UI uses this to show appropriate visual cues:
 * - READ: no approval needed, auto-approved
 * - TRANSFER: moving tokens — show amount, recipient, privacy status
 * - SIGN: signing arbitrary data — warn about potential risks
 * - TRANSACTION: balancing or submitting a transaction — show fee impact
 * - INTENT: creating an intent (swap) — show inputs and outputs
 */
enum class ApprovalCategory {
    READ,
    TRANSFER,
    SIGN,
    TRANSACTION,
    INTENT,
}

/**
 * Data describing a write operation awaiting user approval.
 *
 * Designed to carry enough info for the app-layer UI to render
 * a rich approval screen (amounts, recipients, privacy status, risk level)
 * without coupling the SDK to specific UI decisions.
 *
 * @property method The JSON-RPC method name (e.g., "makeTransfer", "signData")
 * @property category The risk/impact category for UI treatment
 * @property params The raw JSON-RPC params — UI can extract display details
 */
data class ApprovalRequest(
    val method: String,
    val category: ApprovalCategory,
    val params: JsonObject,
)

/**
 * Gate for write operations that require user consent before execution.
 *
 * Implemented by the app module to show confirmation UI (dialogs, biometric
 * prompts, or policy-based auto-approval for agents).
 *
 * The core:connector module defines only this interface — no UI code.
 *
 * The suspend function allows the implementation to show a dialog and
 * wait for the user's response without blocking.
 */
fun interface ApprovalManager {
    suspend fun requestApproval(request: ApprovalRequest): Boolean
}
