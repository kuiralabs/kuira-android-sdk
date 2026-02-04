package com.midnight.kuira.feature.send

import java.math.BigInteger

/**
 * UI state for Send screen.
 *
 * **Pattern:** Sealed class for exhaustive when() handling
 * **States:**
 * - Idle: Initial state, ready to send
 * - Building: Building transaction
 * - Signing: Signing transaction
 * - Submitting: Submitting to blockchain
 * - Success: Transaction submitted successfully
 * - Error: Transaction failed
 *
 * **Why this design:**
 * - Explicit states make UI logic clear
 * - Intermediate states (Building, Signing, Submitting) show progress to user
 * - Success and Error are terminal states
 *
 * **Usage:**
 * ```kotlin
 * when (state) {
 *     is SendUiState.Idle -> ShowSendForm()
 *     is SendUiState.Building -> ShowLoading("Building transaction...")
 *     is SendUiState.Signing -> ShowLoading("Signing...")
 *     is SendUiState.Submitting -> ShowLoading("Submitting...")
 *     is SendUiState.Success -> ShowSuccess(state.txHash)
 *     is SendUiState.Error -> ShowError(state.message)
 * }
 * ```
 */
sealed class SendUiState {

    /**
     * Idle state - ready to send.
     *
     * @property availableBalance Current balance available to send
     */
    data class Idle(
        val availableBalance: BigInteger = BigInteger.ZERO
    ) : SendUiState()

    /**
     * Building transaction from inputs.
     *
     * Shows "Building transaction..." to user.
     */
    data object Building : SendUiState()

    /**
     * Signing transaction with private key.
     *
     * Shows "Signing transaction..." to user.
     */
    data object Signing : SendUiState()

    /**
     * Submitting transaction to blockchain.
     *
     * Shows "Submitting to blockchain..." to user.
     */
    data object Submitting : SendUiState()

    /**
     * Syncing wallet after stale UTXO error, will auto-retry transaction.
     *
     * Shows "Syncing wallet... Will retry automatically" to user.
     * This happens when error 115 occurs (UTXO already spent).
     *
     * @property retryAttempt Current retry attempt (1, 2, 3...)
     * @property maxRetries Maximum retries before giving up
     */
    data class SyncingAndRetrying(
        val retryAttempt: Int = 1,
        val maxRetries: Int = MAX_AUTO_RETRIES
    ) : SendUiState() {
        companion object {
            const val MAX_AUTO_RETRIES = 3
        }
    }

    /**
     * Transaction submitted successfully.
     *
     * @property txHash Transaction hash (64 hex chars)
     * @property recipientAddress Recipient's address (for display)
     * @property amountSent Amount sent in smallest units
     */
    data class Success(
        val txHash: String,
        val recipientAddress: String,
        val amountSent: BigInteger
    ) : SendUiState()

    /**
     * Transaction failed.
     *
     * @property message User-friendly error message
     * @property throwable Original exception (for debugging)
     */
    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : SendUiState()

}
