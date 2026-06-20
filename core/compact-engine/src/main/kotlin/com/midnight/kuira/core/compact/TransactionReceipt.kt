package com.midnight.kuira.core.compact

/**
 * Result of a successful [MidnightContract.call].
 *
 * Contains the transaction hash (if submitted), status, and timing breakdown.
 */
data class TransactionReceipt(
    val txHash: String?,
    val status: TransactionStatus,
    val timings: PipelineTimings,
    val provenTxHex: String,
)

enum class TransactionStatus {
    /** Transaction submitted to the blockchain. */
    SUBMITTED,
    /** Transaction rejected by the node. */
    FAILED,
}

/** Timing breakdown for each pipeline stage (milliseconds). */
data class PipelineTimings(
    val fetchStateMs: Long = 0,
    val executeMs: Long = 0,
    val proveMs: Long = 0,
    val balanceMs: Long = 0,
    val submitMs: Long = 0,
) {
    val totalMs: Long get() = fetchStateMs + executeMs + proveMs + balanceMs + submitMs
}

/**
 * Result of [MidnightContract.prepare] — a proven transaction
 * ready for later submission via [MidnightConfig.submit].
 */
data class PreparedTransaction(
    val provenTxHex: String,
    val contractAddress: String,
    val circuitName: String,
    val timings: PipelineTimings,
    /**
     * The on-chain contract state the circuit ran against. [MidnightContract.call] uses it as the
     * "before" snapshot to confirm the call has been indexed before returning, so a rapid follow-up
     * call reads the updated state instead of rebuilding the identical intent (which the chain's
     * replay guard rejects as `IntentAlreadyExists` / custom error 182).
     */
    val onChainStateHex: String? = null,
)
