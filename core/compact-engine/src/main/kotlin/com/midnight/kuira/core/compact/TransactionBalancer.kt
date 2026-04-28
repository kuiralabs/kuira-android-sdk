package com.midnight.kuira.core.compact

/**
 * Abstracts the wallet operations needed to finalize a proven transaction:
 * adding dust fee inputs (balancing) and submitting to the blockchain.
 *
 * Two implementations exist:
 * - [DAppConnectorClient]: delegates to an external wallet process via WebSocket JSON-RPC
 * - `MidnightWallet` (in sdk:midnight-sdk): handles everything on-device
 *
 * [MidnightContract.call] uses this interface so the contract pipeline is
 * agnostic to whether the wallet is local or remote.
 */
interface TransactionBalancer {

    /**
     * Balance a proven transaction by adding dust fee inputs.
     *
     * @param provenTxHex Hex-encoded proven (but unsealed) transaction
     * @return Hex-encoded balanced transaction ready for submission
     */
    suspend fun balanceTransaction(provenTxHex: String): String

    /**
     * Submit a balanced transaction to the blockchain.
     *
     * @param balancedTxHex Hex-encoded balanced transaction
     */
    suspend fun submitTransaction(balancedTxHex: String)

    /**
     * Balance and submit a proven transaction in one operation.
     *
     * Implementations can override this to add retry logic for recoverable
     * errors (e.g., error 170 InvalidDustSpendProof due to stale Merkle root).
     * The default implementation simply calls [balanceTransaction] then [submitTransaction].
     *
     * @param provenTxHex Hex-encoded proven (but unsealed) transaction
     * @param onProgress Optional callback for granular progress updates.
     *   DApp developers use this to show users what's happening during the
     *   long balance+submit pipeline. See [BalanceProgress] for available stages.
     */
    suspend fun balanceAndSubmit(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)? = null,
    ) {
        val balanced = balanceTransaction(provenTxHex)
        onProgress?.invoke(BalanceProgress.Submitting)
        submitTransaction(balanced)
    }
}
