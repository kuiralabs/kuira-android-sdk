package com.midnight.kuira.core.compact

/**
 * Result of a [MidnightContract.call] the chain accepted.
 *
 * @property txHash Midnight transaction hash, or null when a connector wallet reported only an
 *  identifier the indexer never resolved
 * @property txIdentifier The transaction identifier a connector wallet reported, when it did
 * @property status [TransactionStatus.FINALIZED], or [TransactionStatus.PENDING] when finality was
 *  not observed in time
 * @property blockHeight Height of the finalized block, null while pending or when unknown
 */
data class TransactionReceipt(
    val txHash: String?,
    val txIdentifier: String?,
    val status: TransactionStatus,
    val blockHeight: Long?,
    val timings: PipelineTimings,
    val provenTxHex: String,
)

enum class TransactionStatus {
    /** In a finalized block. */
    FINALIZED,

    /** Accepted by the node, finality not yet observed. It may still fail to land. */
    PENDING,
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
