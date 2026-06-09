// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.indexer.sync

import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.DustEvent
import com.midnight.kuira.core.indexer.repository.DustRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages subscription to dust-related blockchain events and syncs them to DustLocalState.
 *
 * **Workflow:**
 * ```
 * Indexer (GraphQL) → Subscribe to events → Filter dust events → Replay into DustLocalState → Update database cache
 * ```
 *
 * **Event Processing:**
 * 1. Subscribe to zswap ledger events (all blockchain events)
 * 2. Filter for dust-specific events (DustInitialUtxo, DustSpendProcessed, etc.)
 * 3. Batch events and replay into DustLocalState
 * 4. Save updated state to persistent storage
 * 5. Sync UTXOs to database cache
 *
 * **Usage:**
 * ```kotlin
 * @Inject lateinit var dustSubscriptionManager: DustSubscriptionManager
 *
 * dustSubscriptionManager.startDustSync(
 *     address = userAddress,
 *     seed = userSeed
 * ).collect { state ->
 *     when (state) {
 *         is DustSyncState.Syncing -> updateUI("Syncing dust: ${state.progress}%")
 *         is DustSyncState.Synced -> updateUI("Dust synced: ${state.balance}")
 *         is DustSyncState.Error -> showError(state.message)
 *     }
 * }
 * ```
 *
 * @see `/midnight-wallet/packages/dust-wallet/src/Syncing.ts` (TypeScript SDK reference)
 */
@Singleton
class DustSubscriptionManager @Inject constructor(
    private val indexerClient: IndexerClient,
    private val dustRepository: DustRepository
) {

    companion object {
        private const val TAG = "DustSubscriptionManager"

        /**
         * Batch size for event replay (events processed per batch).
         * Larger batches = fewer state serializations, but more memory usage.
         */
        private const val EVENT_BATCH_SIZE = 100
    }

    /**
     * State of dust synchronization.
     */
    sealed class DustSyncState {
        /**
         * Currently syncing dust events.
         *
         * @property lastEventId Last event ID processed
         * @property totalEvents Total events to sync (if known)
         * @property balance Current dust balance in Specks
         */
        data class Syncing(
            val lastEventId: Long,
            val totalEvents: Long?,
            val balance: java.math.BigInteger
        ) : DustSyncState() {
            /**
             * Calculates sync progress as percentage (0-100).
             */
            fun getProgress(): Int {
                return if (totalEvents != null && totalEvents > 0) {
                    ((lastEventId.toDouble() / totalEvents) * 100).toInt().coerceIn(0, 100)
                } else {
                    0
                }
            }
        }

        /**
         * Dust sync completed.
         *
         * @property lastEventId Last event ID processed
         * @property balance Final dust balance in Specks
         */
        data class Synced(
            val lastEventId: Long,
            val balance: java.math.BigInteger
        ) : DustSyncState()

        /**
         * Dust sync error.
         *
         * @property message Error message
         * @property cause Exception that caused the error
         */
        data class Error(
            val message: String,
            val cause: Throwable?
        ) : DustSyncState()
    }

    /**
     * Starts dust event synchronization for an address.
     *
     * **Process:**
     * 1. Subscribe to zswap ledger events from indexer
     * 2. Filter for dust events only
     * 3. Batch events into groups of EVENT_BATCH_SIZE
     * 4. Replay batches into DustLocalState
     * 5. Save state and sync to database cache
     * 6. Emit progress updates
     *
     * **Resumable:**
     * Uses last processed event ID to resume from where it left off.
     * Pass `fromEventId` to start from specific event (for manual sync).
     *
     * **Error Handling:**
     * - Network errors: Automatically retries (handled by IndexerClient)
     * - Deserialization errors: Skips invalid events, logs error
     * - State errors: Emits Error state, stops sync
     *
     * @param address Wallet address to sync dust for
     * @param seed 32-byte seed for deriving DustSecretKey (required for replay)
     * @param fromEventId Start from this event ID (null = last synced or latest)
     * @return Flow of sync state updates
     */
    fun startDustSync(
        address: String,
        seed: ByteArray,
        fromEventId: Long? = null
    ): Flow<DustSyncState> {
        android.util.Log.d(TAG, "Starting dust sync for $address from event ${fromEventId ?: "latest"}")

        return indexerClient.subscribeToZswapEvents(fromEventId)
            .map { rawEvent ->
                android.util.Log.d(TAG, "Received event ${rawEvent.id}, max=${rawEvent.maxId}")

                // Filter for dust events
                if (DustEvent.isDustEvent(rawEvent)) {
                    DustEvent.fromRawEvent(rawEvent)
                } else {
                    null
                }
            }
            .onEach { dustEvent ->
                if (dustEvent != null) {
                    // Replay event into DustLocalState
                    try {
                        replayEvent(address, seed, dustEvent)
                        android.util.Log.d(TAG, "Replayed dust event ${dustEvent.eventId}")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to replay event ${dustEvent.eventId}", e)
                        throw e
                    }
                }
            }
            .map { dustEvent ->
                // Get current balance after event replay
                val balance = dustRepository.getCurrentBalance(address)

                if (dustEvent != null) {
                    DustSyncState.Syncing(
                        lastEventId = dustEvent.eventId,
                        totalEvents = null, // TODO: Get from rawEvent.maxId
                        balance = balance
                    )
                } else {
                    // Non-dust event, return current state
                    DustSyncState.Synced(
                        lastEventId = 0, // TODO: Track last event ID
                        balance = balance
                    )
                }
            }
            .catch { error ->
                android.util.Log.e(TAG, "Dust sync error", error)
                emit(DustSyncState.Error(
                    message = "Failed to sync dust: ${error.message}",
                    cause = error
                ))
            }
    }

    /**
     * Replays a single dust event into DustLocalState.
     *
     * **Process:**
     * 1. Load current DustLocalState for address
     * 2. Call replayEvents() with event hex
     * 3. Save updated state
     * 4. Sync UTXOs to database cache
     *
     * @param address Wallet address
     * @param seed 32-byte seed for deriving DustSecretKey
     * @param event Dust event to replay
     */
    private suspend fun replayEvent(
        address: String,
        seed: ByteArray,
        event: DustEvent
    ) {
        // Load current state
        val state = dustRepository.loadState(address)
        if (state == null) {
            android.util.Log.w(TAG, "No dust state found for $address, initializing")
            dustRepository.initializeIfNeeded(address)
            return
        }

        try {
            // Replay event
            val newState = state.replayEvents(seed, event.rawHex)
            if (newState == null) {
                android.util.Log.e(TAG, "Failed to replay event ${event.eventId}")
                return
            }

            // Save updated state + its resume cursor atomically. Advancing the
            // state without advancing the cursor (the old saveState-only call)
            // left the persisted pair torn — a frontier ahead of the cursor that
            // implies it — which delta resume rejects as NonLinearInsertion.
            dustRepository.saveCheckpoint(address, newState, event.eventId)

            // Sync to database cache
            dustRepository.syncTokensToCache(address)

            // Close states
            state.close()
            newState.close()

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error replaying event ${event.eventId}", e)
            state.close()
            throw e
        }
    }

    /**
     * Stops dust synchronization (cancels flow collection).
     *
     * **Note:**
     * Simply stop collecting the Flow returned by startDustSync().
     * Kotlin Flow handles cancellation automatically.
     */
    fun stopDustSync() {
        android.util.Log.d(TAG, "Dust sync stopped (cancel flow collection)")
    }
}
