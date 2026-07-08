package com.midnight.kuira.core.indexer.api

/**
 * The outcome of asking the indexer for the block hash at a specific height —
 * [IndexerClient.getBlockHashAtHeight]. The three cases are kept apart because [ChainResetGuard]
 * must react to them differently: a block that isn't on the chain is a reset signal, while a query
 * that simply failed is a "can't tell, skip" signal. Collapsing both to `null` would let a transient
 * indexer failure look identical to a genuinely-shorter chain.
 */
sealed interface BlockHashLookup {
    /** The block exists at the queried height; [hash] is its hex hash. */
    data class Found(val hash: String) : BlockHashLookup

    /** A valid indexer response with no block at that height — the chain is shorter than the query. */
    data object NotOnChain : BlockHashLookup

    /** The query could not be answered (network / GraphQL error / parse failure). */
    data object Unavailable : BlockHashLookup
}
