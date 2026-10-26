package com.midnight.kuira.core.compact

/**
 * Result of deploying a contract to the blockchain.
 *
 * @property txHash Midnight hash of the deploy transaction, or null when a connector wallet
 *  reported only an identifier the indexer never resolved
 * @property txIdentifier The transaction identifier a connector wallet reported, when it did
 * @property blockHeight Height of the finalized block, null while pending or when unknown
 */
data class DeployResult(
    val contractAddress: String,
    val txHash: String?,
    val txIdentifier: String?,
    val status: TransactionStatus,
    val blockHeight: Long?,
    val timings: DeployTimings,
)

data class DeployTimings(
    val executeMs: Long,
    val proveMs: Long,
    val balanceMs: Long,
)
