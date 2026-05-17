package com.midnight.kuira.core.indexer.model

import kotlinx.serialization.Serializable

/**
 * Unshielded transaction from Midnight blockchain.
 *
 * Represents a transaction affecting unshielded (transparent) addresses.
 * Matches Midnight SDK's `UnshieldedTransactionSchema`.
 *
 * @property id Unique transaction ID from indexer
 * @property hash Transaction hash (hex string)
 * @property type Transaction type (Regular or System)
 * @property protocolVersion Midnight protocol version
 * @property identifiers Optional array of identifiers (for regular transactions)
 * @property block Block information
 * @property fees Optional fee information (for regular transactions)
 * @property transactionResult Optional transaction result (for regular transactions)
 */
@Serializable
data class UnshieldedTransaction(
    val id: Int,
    val hash: String,
    val type: TransactionType,
    val protocolVersion: Int,
    val identifiers: List<String>? = null,
    // Nullable because SystemTransaction variants from the indexer omit `block`
    // (regression observed against PREPROD: SubscriptionManager kept retrying
    // with MissingFieldException). Same shape rationale as `fees` /
    // `transactionResult` — those are also Regular-only. No consumer currently
    // reads `block`; if one ever needs it, decide then whether to default to
    // the surrounding subscription tip or restrict reads to Regular variants.
    val block: BlockInfo? = null,
    val fees: FeeInfo? = null,
    val transactionResult: TransactionResult? = null
) {
    init {
        require(id >= 0) { "Transaction ID must be non-negative" }
        require(hash.isNotBlank()) { "Hash cannot be blank" }
        require(protocolVersion >= 0) { "Protocol version must be non-negative" }
    }

    /**
     * Block information for a transaction.
     */
    @Serializable
    data class BlockInfo(
        val timestamp: Long // Unix timestamp in milliseconds
    ) {
        init {
            require(timestamp >= 0) { "Timestamp must be non-negative" }
        }
    }

    /**
     * Fee information for a transaction.
     */
    @Serializable
    data class FeeInfo(
        val paidFees: String, // BigInteger as string
        val estimatedFees: String // BigInteger as string
    ) {
        init {
            require(paidFees.isNotBlank()) { "PaidFees cannot be blank" }
            require(estimatedFees.isNotBlank()) { "EstimatedFees cannot be blank" }
        }
    }

    /**
     * Transaction execution result.
     */
    @Serializable
    data class TransactionResult(
        val status: TransactionStatus,
        val segments: List<SegmentResult>?
    ) {
        @Serializable
        data class SegmentResult(
            val id: Int,
            val success: Boolean
        ) {
            init {
                require(id >= 0) { "Segment ID must be non-negative" }
            }
        }
    }
}

/**
 * Type of transaction.
 */
@Serializable
enum class TransactionType {
    /**
     * Regular user transaction.
     */
    RegularTransaction,

    /**
     * System transaction (e.g., coinbase, governance).
     */
    SystemTransaction
}
