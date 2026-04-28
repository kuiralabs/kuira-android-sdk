package com.midnight.kuira.core.ledger.api

/**
 * Client for Midnight node JSON-RPC API.
 *
 * Submits transactions to the Midnight blockchain via the node's RPC interface.
 *
 * **JSON-RPC 2.0 Protocol:**
 * - Endpoint: HTTP POST to node RPC (default: http://localhost:9944)
 * - Method: `author_submitExtrinsic` (standard Substrate RPC)
 * - Params: Hex-encoded serialized transaction (SCALE codec)
 * - Response: Transaction hash (32 bytes hex)
 *
 * **Transaction Lifecycle (with WebSocket):**
 * ```
 * 1. Submit via WebSocket → Get subscription
 * 2. Receive status events: ready → broadcast → inBlock → finalized
 * 3. Return when finalized (typically 6-12 seconds)
 * ```
 *
 * **Error Handling:**
 * - Network errors: Retry with exponential backoff
 * - Server errors (5xx): Retry with backoff
 * - Client errors (4xx): Don't retry
 * - Transaction rejected: Don't retry (fix transaction)
 *
 * @see NodeRpcClientImpl for implementation details
 */
interface NodeRpcClient {

    /**
     * Submit a serialized transaction to the Midnight node (fire-and-forget).
     *
     * **WARNING:** This method returns immediately after submission.
     * Use [submitAndWaitForFinalization] for proper confirmation.
     *
     * **Process:**
     * 1. Send JSON-RPC request: `author_submitExtrinsic`
     * 2. Node validates transaction (signature, format, UTXOs)
     * 3. Node adds to mempool if valid
     * 4. Returns transaction hash (32 bytes hex)
     *
     * @param serializedTxHex Hex-encoded serialized transaction (without "0x" prefix)
     * @return Transaction hash (32 bytes hex, without "0x" prefix)
     * @throws NodeNetworkException if network connectivity fails
     * @throws NodeHttpException if HTTP request fails
     * @throws NodeTimeoutException if request times out
     * @throws NodeRpcError if node returns JSON-RPC error
     * @throws TransactionRejected if transaction is invalid
     * @throws NodeInvalidResponseException if response is malformed
     */
    suspend fun submitTransaction(serializedTxHex: String): String

    /**
     * Submit a transaction and wait for it to be finalized on-chain.
     *
     * **This is the correct method for transaction submission.**
     * Uses WebSocket subscription to wait for the node's finalized status.
     *
     * **Process:**
     * 1. Connect to node via WebSocket
     * 2. Send `author_submitAndWatchExtrinsic` request
     * 3. Subscribe to transaction status events
     * 4. Wait for `finalized` status from node
     * 5. Return finalization result
     *
     * **Transaction Status Flow:**
     * ```
     * ready → broadcast → inBlock → finalized
     * ```
     *
     * **Why this is important:**
     * - `submitTransaction` returns immediately (fire-and-forget)
     * - The transaction may not yet be in a block
     * - Only `finalized` status guarantees the transaction is permanent
     * - Prevents race conditions with UTXO state management
     *
     * @param serializedTxHex Hex-encoded serialized transaction (without "0x" prefix)
     * @param timeoutMs Maximum time to wait for finalization (default 60s)
     * @return Finalization result with block info
     * @throws NodeNetworkException if network connectivity fails
     * @throws NodeTimeoutException if finalization times out
     * @throws NodeRpcError if node returns JSON-RPC error
     * @throws TransactionRejected if transaction is invalid (error 115 = stale UTXO)
     * @throws NodeInvalidResponseException if response is malformed
     */
    suspend fun submitAndWaitForFinalization(
        serializedTxHex: String,
        timeoutMs: Long = 60_000L,
        onStage: (suspend (SubmissionStage) -> Unit)? = null,
    ): TransactionFinalizationResult

    /** Stages during transaction submission, for progress UX. */
    enum class SubmissionStage {
        /** Transaction sent to node, waiting for broadcast. */
        SUBMITTED,
        /** Node has broadcast the transaction to peers. */
        BROADCAST,
        /** Transaction included in a block, waiting for finalization. */
        IN_BLOCK,
    }

    /**
     * Get the hash of the latest finalized block.
     *
     * Standard Substrate RPC: `chain_getFinalizedHead`.
     * Returns a 32-byte block hash as 64-char hex (without 0x prefix).
     *
     * Used as a tip fingerprint for cache validity: if the finalized head
     * hasn't changed since last sync, dust state is guaranteed current.
     *
     * @return Block hash (64 hex chars)
     * @throws NodeNetworkException if network connectivity fails
     * @throws NodeTimeoutException if request times out
     * @throws NodeRpcError if node returns JSON-RPC error
     */
    suspend fun getFinalizedHead(): String

    /**
     * Check if node is healthy and reachable.
     *
     * Performs a lightweight RPC call to verify connectivity.
     *
     * @return true if node is responding, false otherwise
     */
    suspend fun isHealthy(): Boolean

    /**
     * Close the client and release resources.
     */
    fun close()
}

/**
 * Result of transaction finalization.
 */
sealed class TransactionFinalizationResult {
    /**
     * Transaction was finalized successfully.
     *
     * @property txHash Extrinsic hash (Substrate)
     * @property blockHash Block hash where transaction was finalized
     * @property blockHeight Block height (if available)
     */
    data class Finalized(
        val txHash: String,
        val blockHash: String,
        val blockHeight: Long? = null
    ) : TransactionFinalizationResult()

    /**
     * Transaction was included in a block but not yet finalized.
     * This is a temporary state - finalization should follow.
     *
     * @property txHash Extrinsic hash
     * @property blockHash Block hash
     */
    data class InBlock(
        val txHash: String,
        val blockHash: String
    ) : TransactionFinalizationResult()

    /**
     * Transaction was dropped from the pool.
     * Likely due to mempool congestion.
     *
     * @property txHash Extrinsic hash
     * @property reason Reason for dropping
     */
    data class Dropped(
        val txHash: String,
        val reason: String
    ) : TransactionFinalizationResult()

    /**
     * Transaction was replaced by another (usurped).
     *
     * @property txHash Original extrinsic hash
     * @property replacedBy Hash of the replacing transaction
     */
    data class Usurped(
        val txHash: String,
        val replacedBy: String?
    ) : TransactionFinalizationResult()

    /**
     * Transaction is invalid.
     *
     * @property txHash Extrinsic hash
     * @property reason Reason for invalidity
     */
    data class Invalid(
        val txHash: String,
        val reason: String
    ) : TransactionFinalizationResult()

    /**
     * Finalization timed out.
     * Transaction may still be processing.
     *
     * @property txHash Extrinsic hash
     */
    data class Timeout(
        val txHash: String
    ) : TransactionFinalizationResult()
}
