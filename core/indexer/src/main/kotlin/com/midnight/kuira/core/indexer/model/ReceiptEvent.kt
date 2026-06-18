package com.midnight.kuira.core.indexer.model

import java.math.BigInteger

/**
 * A genuine inbound NIGHT receipt, identified per-transaction by UTXO-set provenance (#284):
 * a transaction that created one or more unshielded NIGHT outputs owned by us while spending
 * NONE of our UTXOs.
 *
 * Our own sends spend our inputs and return change to us; that change is deliberately NOT a
 * receipt and never produces a [ReceiptEvent] — so a user is never told they "received" their
 * own NIGHT back. This replaces the old scalar balance-delta detection, which could not tell
 * change from a payment and (because the balance is a non-atomic aggregate during a spend)
 * would occasionally announce the change as a receipt.
 *
 * @property transactionId Monotonic indexer transaction id. The dedup key: a re-sync or replay
 *   of an already-announced receipt carries the same id, so it is not re-announced.
 * @property transactionHash The transaction hash (for display / deep-linking).
 * @property amount Total unshielded NIGHT created to us IN THIS transaction (smallest unit) —
 *   the true per-transaction received amount, not a balance delta.
 */
data class ReceiptEvent(
    val transactionId: Int,
    val transactionHash: String,
    val amount: BigInteger,
)
