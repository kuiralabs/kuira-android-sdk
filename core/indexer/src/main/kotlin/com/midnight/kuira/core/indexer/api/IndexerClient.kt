package com.midnight.kuira.core.indexer.api

import com.midnight.kuira.core.indexer.model.BlockInfo
import com.midnight.kuira.core.indexer.model.NetworkState
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import kotlinx.coroutines.flow.Flow

/**
 * Client for Midnight indexer GraphQL API (v3).
 *
 * Provides access to ledger events, blocks, and network state for light wallet implementation.
 *
 * **Light Wallet Architecture:**
 * - Subscribe to transaction events via WebSocket (Phase 4B)
 * - Track UTXOs locally (Phase 4A sync engine)
 * - Calculate balances from UTXO set
 * - No full node required
 *
 * **GraphQL Endpoints:**
 * - HTTP Queries: `https://indexer.testnet-02.midnight.network/api/v3/graphql`
 * - WebSocket Subscriptions: `wss://indexer.testnet-02.midnight.network/api/v3/graphql/ws`
 *
 * @see IndexerClientImpl for implementation details
 */
interface IndexerClient {

    // ==================== UTXO TRACKING (Phase 4B) ====================

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

    // ==================== SYNC ENGINE (Phase 4A) ====================

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
     * Query all zswap (shielded) events from the blockchain via subscription.
     *
     * Subscribes, collects all events until caught up, returns combined hex.
     * Same pattern as [queryDustEvents] but for shielded coin events.
     *
     * @return Combined SCALE hex string of all zswap events, sorted by ID
     */
    suspend fun queryZswapEvents(): String

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
