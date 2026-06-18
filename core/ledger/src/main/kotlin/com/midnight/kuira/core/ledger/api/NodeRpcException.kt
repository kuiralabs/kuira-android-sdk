package com.midnight.kuira.core.ledger.api

/**
 * Base exception for all node RPC errors.
 */
sealed class NodeRpcException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Network connectivity error (no internet, DNS failure, connection refused).
 *
 * **Recovery:** Retry with exponential backoff
 */
class NodeNetworkException(message: String, cause: Throwable? = null) : NodeRpcException(message, cause)

/**
 * HTTP error from node RPC (4xx or 5xx status).
 *
 * **Recovery:**
 * - 4xx: Don't retry (client error - malformed request)
 * - 5xx: Retry with exponential backoff (server error)
 */
class NodeHttpException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : NodeRpcException("HTTP $statusCode: $message", cause) {
    val isClientError: Boolean get() = statusCode in 400..499
    val isServerError: Boolean get() = statusCode in 500..599
}

/**
 * Request timeout error.
 *
 * **Recovery:** Retry with longer timeout
 */
class NodeTimeoutException(message: String, cause: Throwable? = null) : NodeRpcException(message, cause)

/**
 * Invalid or malformed response from node RPC.
 *
 * **Recovery:** Don't retry (protocol error)
 */
class NodeInvalidResponseException(message: String, cause: Throwable? = null) : NodeRpcException(message, cause)

/**
 * JSON-RPC error from node (negative error code in response).
 *
 * **Common Error Codes:**
 * - `-32700`: Parse error (invalid JSON)
 * - `-32600`: Invalid request (malformed JSON-RPC)
 * - `-32601`: Method not found
 * - `-32602`: Invalid params
 * - `-32603`: Internal error
 * - `1010`: Invalid transaction (rejected by node)
 *
 * **Recovery:**
 * - Parse/protocol errors: Don't retry
 * - Internal errors: Retry once
 * - Invalid transaction: Don't retry (fix transaction)
 */
class NodeRpcError(
    val code: Int,
    message: String,
    val data: String? = null
) : NodeRpcException("JSON-RPC error $code: $message${data?.let { " ($it)" } ?: ""}") {
    val isParseError: Boolean get() = code == -32700
    val isInvalidRequest: Boolean get() = code == -32600
    val isMethodNotFound: Boolean get() = code == -32601
    val isInvalidParams: Boolean get() = code == -32602
    val isInternalError: Boolean get() = code == -32603
    val isInvalidTransaction: Boolean get() = code == 1010

    /**
     * Check if this error is retryable.
     */
    val isRetryable: Boolean get() = isInternalError && !isInvalidTransaction
}

/**
 * Transaction rejected by node (validation failed).
 *
 * **Reasons:**
 * - Invalid signature
 * - Double-spend (UTXO already spent)
 * - Insufficient balance
 * - TTL expired
 * - Invalid format
 *
 * **Custom Error Codes (authoritative, from midnight-node):**
 * - 115: InvalidProof (the ZK proof failed verification — regenerate the proof /
 *   check the proving-key & contract verifier-key versions match)
 * - 186: EffectsCheckFailure
 * - 195: InputNotInUtxos (an unshielded UTXO input is already spent or missing)
 *
 * **Recovery:** Don't retry (fix transaction)
 */
class TransactionRejected(
    val reason: String,
    val txHash: String? = null,
    val customErrorCode: Int? = null
) : NodeRpcException("Transaction rejected: $reason${txHash?.let { " (hash: $it)" } ?: ""}") {

    companion object {
        /** The ZK proof failed verification (wrong/stale public inputs or key mismatch). */
        const val ERROR_INVALID_PROOF = 115

        /**
         * The dust spend proof is invalid because the dust commitment root it commits
         * to isn't one the node accepts — a stale/lagging local root, or a `ctime` that
         * resolved to a different root via the node's `dust.root_history.get(ctime)`
         * predecessor lookup. Recovery: re-sync dust to the chain tip and retry. See #287.
         */
        const val ERROR_INVALID_DUST_SPEND_PROOF = 170

        /** A contract effects check failed. */
        const val ERROR_EFFECTS_CHECK_FAILURE = 186

        /** An unshielded UTXO input doesn't exist on-chain (already spent or never existed). */
        const val ERROR_INPUT_NOT_IN_UTXOS = 195
    }

    /** True if the node rejected the transaction's ZK proof (node error 115). */
    val isInvalidProof: Boolean get() = customErrorCode == ERROR_INVALID_PROOF

    /** True if the dust spend's root was stale / rejected (node error 170). */
    val isDustSpendProof: Boolean get() = customErrorCode == ERROR_INVALID_DUST_SPEND_PROOF

    /** True if an unshielded UTXO input was already spent / missing (node error 195). */
    val isStaleUtxo: Boolean get() = customErrorCode == ERROR_INPUT_NOT_IN_UTXOS
}
