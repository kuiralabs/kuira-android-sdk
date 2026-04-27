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
}
