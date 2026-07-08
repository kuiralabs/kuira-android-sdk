package com.midnight.kuira.core.indexer.api

import com.midnight.kuira.core.indexer.model.BlockInfo
import com.midnight.kuira.core.indexer.model.DustSyncResult
import com.midnight.kuira.core.indexer.model.NetworkState
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import kotlinx.coroutines.flow.Flow

/**
 * Client for the Midnight indexer GraphQL API.
 *
 * Provides access to ledger events, blocks, and network state for light wallet implementation.
 *
 * **Light Wallet Architecture:**
 * - Subscribe to transaction events via WebSocket
 * - Track UTXOs locally
 * - Calculate balances from UTXO set
 * - No full node required
 *
 * **GraphQL Endpoints** (composed from `NetworkConfig.forNetwork(...).indexerBaseUrl`):
 * - HTTP Queries: `{baseUrl}/graphql`
 * - WebSocket Subscriptions: `{baseUrl}/graphql/ws` (scheme switched to `ws(s)://`)
 *
 * @see IndexerClientImpl for implementation details
 * @see com.midnight.kuira.core.network.NetworkConfig for the canonical URL source
 */
interface IndexerClient {

    // ==================== UTXO TRACKING ====================

    /**
     * Subscribe to unshielded transactions for an address.
     *
     * Returns a Flow of updates containing:
     * - Transaction details (created/spent UTXOs)
     * - Progress updates (highest transaction ID)
     *
     * **GraphQL Subscription:**
     * ```graphql
     * subscription UnshieldedTransactions($address: UnshieldedAddress!, $transactionId: Int) {
     *   unshieldedTransactions(address: $address, transactionId: $transactionId) {
     *     ... on UnshieldedTransaction {
     *       transaction { id, hash, timestamp }
     *       createdUtxos { owner, value, tokenType, outputIndex }
     *       spentUtxos { owner, value, tokenType, outputIndex }
     *     }
     *     ... on UnshieldedTransactionsProgress {
     *       highestTransactionId
     *     }
     *   }
     * }
     * ```
     *
     * @param address Unshielded address to track
     * @param transactionId Start from this transaction ID (use last processed ID to resume)
     * @return Flow of transaction updates (both transactions and progress)
     */
    fun subscribeToUnshieldedTransactions(
        address: String,
        transactionId: Int? = null
    ): Flow<UnshieldedTransactionUpdate>

    // ==================== SYNC ENGINE ====================

    /**
     * Subscribe to zswap ledger events.
     *
     * **GraphQL Subscription:**
     * ```graphql
     * subscription {
     *   zswapLedgerEvents {
     *     id
     *     raw
     *     maxId
     *   }
     * }
     * ```
     *
     * @param fromId Start from this event ID (inclusive). Use null to start from latest.
     * @return Flow of raw ledger events
     */
    fun subscribeToZswapEvents(fromId: Long? = null): Flow<RawLedgerEvent>

    /**
     * Subscribe to new blocks.
     *
     * **GraphQL Subscription:**
     * ```graphql
     * subscription {
     *   blocks {
     *     height
     *     hash
     *     timestamp
     *   }
     * }
     * ```
     *
     * @return Flow of block metadata
     */
    fun subscribeToBlocks(): Flow<BlockInfo>

    /**
     * Get current network synchronization state.
     *
     * **GraphQL Query:**
     * ```graphql
     * query {
     *   networkState {
     *     currentBlock
     *     maxBlock
     *   }
     * }
     * ```
     *
     * @return Current network state
     */
    suspend fun getNetworkState(): NetworkState

    /**
     * Get current block with ledger parameters.
     *
     * **GraphQL Query:**
     * ```graphql
     * query {
     *   block {
     *     height
     *     hash
     *     ledgerParameters
     *     timestamp
     *   }
     * }
     * ```
     *
     * @return Current block info with ledger parameters hex
     */
    suspend fun getCurrentBlockWithParams(): BlockInfo

    /**
     * The hash of the block at [height] — the discriminator for detecting a chain RESET (a localnet
     * `docker` down/up replaces the chain). [ChainResetGuard] pins a CHECKPOINT block above genesis
     * and re-looks-it-up here on each sync: after a reset the fresh chain is shorter (the pinned
     * height returns [BlockHashLookup.NotOnChain]) or, once it re-climbs, that height carries a
     * different hash ([BlockHashLookup.Found] with a new value).
     *
     * The GENESIS (height-0) hash is deliberately NOT usable for this: a localnet booting from a
     * fixed chain spec reproduces the same genesis hash across a reset (verified live), so a
     * height-0 compare can't tell a reset from a healthy chain. Any block above genesis carries a
     * per-instance timestamp, so its hash IS chain-instance-specific.
     *
     * **GraphQL Query:** `query { block(offset: { height: $height }) { hash } }`
     *
     * The three-way result keeps the two "can't-read" cases apart, because they mean opposite things:
     *  - [BlockHashLookup.Found] — the block exists; compare its hash.
     *  - [BlockHashLookup.NotOnChain] — a VALID response with no block at that height (the chain is
     *    shorter than the checkpoint) → the caller treats this as a reset.
     *  - [BlockHashLookup.Unavailable] — the query itself failed (network / GraphQL / parse) → the
     *    caller MUST skip the reset check, so a transient indexer failure can't wipe a healthy wallet.
     *
     * Defaults to [BlockHashLookup.Unavailable] so in-memory test doubles needn't implement it (a
     * double that doesn't override it can never trigger a wipe).
     */
    suspend fun getBlockHashAtHeight(height: Long): BlockHashLookup = BlockHashLookup.Unavailable

    /**
     * Get historical events in range.
     *
     * **GraphQL Query:**
     * ```graphql
     * query {
     *   zswapLedgerEvents(fromId: $from, toId: $to) {
     *     id
     *     raw
     *     maxId
     *   }
     * }
     * ```
     *
     * @param fromId Start event ID (inclusive)
     * @param toId End event ID (inclusive)
     * @return List of events in range
     */
    suspend fun getEventsInRange(fromId: Long, toId: Long): List<RawLedgerEvent>

    /**
     * Subscribe to dust ledger events via WebSocket.
     *
     * Streams ALL dust events from the given event ID. Events are global
     * (not filtered by address) — DustLocalState.replayEvents() filters
     * internally using the dust seed.
     *
     * **GraphQL Subscription:**
     * ```graphql
     * subscription DustLedgerEvents($id: Int) {
     *   dustLedgerEvents(id: $id) {
     *     id
     *     raw
     *     maxId
     *   }
     * }
     * ```
     *
     * @param fromId Start from this event ID (cursor for resume). Null starts from beginning.
     * @return Flow of raw dust ledger events
     */
    fun subscribeToDustEvents(fromId: Long? = null): Flow<RawLedgerEvent>

    /**
     * Query all dust events via WebSocket subscription.
     *
     * Subscribes to dustLedgerEvents, collects all events until caught up to chain tip,
     * then returns combined SCALE-encoded hex that can be replayed into DustLocalState.
     *
     * @param maxBlocks Unused (kept for backward compatibility). All events are streamed.
     * @return Combined SCALE hex string of all dust events, sorted by ID
     */
    suspend fun queryDustEvents(maxBlocks: Int = 100): String

    /**
     * Query dust events starting from a specific event ID (delta sync).
     *
     * Like [queryDustEvents] but subscribes from [fromId] instead of the beginning.
     * Returns structured result with the combined hex, last event ID, and count.
     * If no new events exist (already caught up), returns immediately with empty hex.
     *
     * @param fromId First event ID to include (inclusive)
     * @return Delta sync result with events hex, last applied ID, and event count
     */
    suspend fun queryDustEventsDelta(fromId: Long, timeoutMs: Long = DELTA_TIMEOUT_MS): DustSyncResult

    companion object {
        /** Default timeout for delta sync (500ms, sufficient for local + remote delta). */
        const val DELTA_TIMEOUT_MS = 500L
        /** Timeout for full sync from genesis (120s, PREPROD has 250k+ events). */
        const val FULL_SYNC_TIMEOUT_MS = 120_000L
    }

    /**
     * Query all zswap (shielded) events from the blockchain via subscription.
     *
     * Subscribes, collects all events until caught up, returns combined hex.
     * Same pattern as [queryDustEvents] but for shielded coin events.
     *
     * @return Combined SCALE hex string of all zswap events, sorted by ID
     */
    suspend fun queryZswapEvents(): String

    // ==================== CONTRACT STATE ====================

    /**
     * Query the current on-chain state of a deployed contract.
     *
     * Returns the SCALE-encoded contract state as a hex string,
     * suitable for passing to `ContractRuntime.stateCreate()`.
     *
     * **GraphQL Query:**
     * ```graphql
     * query ContractState($address: HexEncoded!) {
     *   contractAction(address: $address) {
     *     state
     *   }
     * }
     * ```
     *
     * @param address Hex-encoded contract address (64 chars)
     * @return SCALE-encoded contract state as hex string
     * @throws InvalidResponseException if contract not found or response malformed
     */
    suspend fun queryContractState(address: String): String

    /**
     * Check if indexer is healthy and reachable.
     *
     * @return true if indexer is responding, false otherwise
     */
    suspend fun isHealthy(): Boolean

    /**
     * Close the client and release resources.
     */
    fun close()

    /**
     * Reset the WebSocket connection (close all active subscriptions).
     *
     * Use this when switching addresses or cleaning up subscriptions.
     * Next subscription will automatically reconnect.
     */
    suspend fun resetConnection()
}
