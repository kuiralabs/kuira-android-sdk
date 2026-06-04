package com.midnight.kuira.sdk

/**
 * Thrown when a balance can't proceed because the wallet has no spendable dust —
 * specifically, every dust UTXO in the synced state is one the wallet already spent
 * but whose spend the indexer's event stream hasn't reflected yet (so it's correctly
 * excluded to avoid node error 115).
 *
 * This is a clean, actionable failure: the dust is genuinely tied up awaiting
 * reconfirmation, so retrying immediately or forcing a full re-sync won't help —
 * the caller should surface "waiting for dust to reconfirm" and retry later.
 */
class InsufficientDustException(message: String) : IllegalStateException(message)
