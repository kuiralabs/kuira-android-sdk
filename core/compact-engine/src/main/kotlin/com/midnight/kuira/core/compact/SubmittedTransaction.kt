package com.midnight.kuira.core.compact

/**
 * A transaction the node accepted, as [TransactionBalancer.submitTransaction] reports it. A
 * transaction the node dropped, invalidated or replaced is never one of these: it is thrown as
 * [SubmissionNotAcceptedException].
 *
 * @property txHash Midnight transaction hash, or null when the wallet reported only an identifier
 * @property txIdentifier The transaction identifier a connector wallet reported, when it did. A
 *  wallet built on Midnight's wallet SDK names a submission by identifier, not by hash
 * @property blockHeight Height of the finalized block, null while pending or when unknown
 */
data class SubmittedTransaction(
    val txHash: String?,
    val txIdentifier: String?,
    val status: TransactionStatus,
    val blockHeight: Long?,
)

/** The node took the transaction and then dropped, invalidated or replaced it: it will not land. */
class SubmissionNotAcceptedException(val reason: String) : Exception("Transaction not accepted: $reason")
