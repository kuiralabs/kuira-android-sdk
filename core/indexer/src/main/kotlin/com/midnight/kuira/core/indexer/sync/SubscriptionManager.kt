package com.midnight.kuira.core.indexer.sync

import android.util.Log
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages subscription lifecycle for UTXO syncing.
 *
 * **This is the missing piece that connects everything:**
 * - Starts subscription via IndexerClient
 * - Collects updates from subscription Flow
 * - Passes updates to UtxoManager for processing
 * - Persists sync progress via SyncStateManager
 * - Handles reconnection with exponential backoff
 *
 * **Why this was missing:**
 * All the pieces existed (IndexerClient, UtxoManager, WebSocket client),
 * but nothing ever called `subscribeToUnshieldedTransactions()` and collected from it.
 * This class fixes that critical gap.
 *
 * **Usage:**
 * ```kotlin
 * // In ViewModel or background service
 * val subscriptionManager = SubscriptionManager(indexerClient, utxoManager, syncStateManager)
 *
 * // Start syncing (will resume from last processed transaction)
 * viewModelScope.launch {
 *     subscriptionManager.startSubscription(address)
 *         .collect { state ->
 *             when (state) {
 *                 is SyncState.Syncing -> updateUI("Syncing: ${state.processedCount} txs")
 *                 is SyncState.Synced -> updateUI("Synced up to block ${state.blockHeight}")
 *                 is SyncState.Error -> showError(state.message)
 *             }
 *         }
 * }
 * ```
 */
class SubscriptionManager(
    private val context: android.content.Context,
    private val indexerClient: IndexerClient,
    private val utxoManager: UtxoManager,
    private val syncStateManager: SyncStateManager
) {
    companion object {
        private const val TAG = "SubscriptionManager"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 32000L

        // Throttle DataStore writes to reduce battery drain and disk I/O
        // Only save progress if this many milliseconds have passed since last save
        private const val PROGRESS_SAVE_THROTTLE_MS = 5000L // 5 seconds

        // Auto-sync timeout: If no new transactions arrive for this duration,
        // assume we're synced (don't wait for server's slow Progress updates)
        private const val SYNC_TIMEOUT_MS = 5000L // 5 seconds
    }

    // Track last save time per address for throttling
    private val lastSaveTimestamps = mutableMapOf<String, Long>()

    /**
     * Prepare for sync by clearing unverified local cache.
     *
     * **CRITICAL DESIGN DECISION:**
     * The indexer/blockchain is the ONLY source of truth. Local database is just a cache.
     * We ALWAYS clear and rebuild from indexer to prevent showing stale/incorrect data.
     *
     * **Why this is correct:**
     * 1. Previous approach trusted local DB and showed stale data if sync failed
     * 2. Users saw balances that didn't exist on blockchain
     * 3. Now: Clear cache → Sync from indexer → Show only verified data
     *
     * **Trade-off:**
     * - Requires re-sync on every app start
     * - But guarantees we NEVER show incorrect balances
     * - This matches industry standard wallet behavior
     */
    private suspend fun checkAndHandleResyncNeeded(address: String) {
        val prefs = context.getSharedPreferences("utxo_db_flags", android.content.Context.MODE_PRIVATE)

        // Always clear local cache and sync fresh from indexer
        // This is the ONLY way to guarantee we show correct balances
        Log.i(TAG, "🔄 FRESH SYNC: Clearing local cache for $address - will rebuild from indexer")

        // Clear sync state to replay all transactions
        syncStateManager.clearSyncState(address)

        // Clear all UTXOs - they will be rebuilt from indexer
        utxoManager.clearUtxos(address)

        // Clear any migration flags
        prefs.edit().putBoolean("needs_full_resync", false).apply()

        Log.i(TAG, "✓ Cache cleared - balance will be 0 until sync rebuilds from indexer")
    }

    /**
     * Start subscription for an address with automatic reconnection.
     *
     * **Flow:**
     * 1. Get last processed transaction ID from SyncStateManager
     * 2. Subscribe from that ID (null = start from beginning)
     * 3. Process each update via UtxoManager
     * 4. Save progress after each Progress update
     * 5. On error: retry with exponential backoff
     *
     * **Resumption:**
     * - First sync: fromTransactionId = null (replay all history)
     * - Subsequent syncs: fromTransactionId = last saved ID (skip already processed)
     *
     * **Reconnection:**
     * - Network errors: retry with exponential backoff (1s, 2s, 4s, 8s, 16s, 32s max)
     * - After max retries: emit error state and stop (caller must restart subscription)
     * - Retryable errors: IOException, connection failures, WebSocket errors
     * - Non-retryable errors: CancellationException (user cancelled)
     *
     * @param address Unshielded address to sync
     * @param skipCacheClear If true, don't clear local UTXO cache before syncing.
     *        Used during error 115 recovery to preserve UTXOs marked as SPENT by the node.
     *        Default: false (fresh sync clears cache to prevent stale data).
     * @return Flow of sync states (Syncing, Synced, Error)
     */
    fun startSubscription(address: String, skipCacheClear: Boolean = false): Flow<SyncState> {
        // NOTE: retryWhen re-creates the flow on each retry, so skipCacheClear is preserved
        return createSubscriptionFlow(address, skipCacheClear)
            .retryWhen { cause, attempt ->
                // Retry all errors except cancellation (user-initiated stop)
                when {
                    cause is CancellationException -> {
                        // User cancelled - don't retry
                        Log.d(TAG, "Subscription cancelled by user")
                        false
                    }
                    attempt < MAX_RETRY_ATTEMPTS -> {
                        // Network error or connection failure - retry with exponential backoff
                        // Catches: IOException, WebSocket errors, connection timeouts, etc.
                        val delayMs = calculateRetryDelay(attempt)
                        Log.w(TAG, "Subscription error (${cause.javaClass.simpleName}), retrying in ${delayMs}ms (attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS)", cause)
                        delay(delayMs)
                        true
                    }
                    else -> {
                        // Max retries reached - stop retrying
                        Log.e(TAG, "Subscription failed after $attempt attempts: ${cause.message}", cause)
                        false
                    }
                }
            }
            .catch { error ->
                // Emit error state after retries exhausted
                Log.e(TAG, "Subscription failed permanently", error)
                emit(SyncState.Error(error.message ?: "Unknown error"))
            }
    }

    private fun createSubscriptionFlow(address: String, skipCacheClear: Boolean = false): Flow<SyncState> = channelFlow {
        var processedCount = 0
        var latestTransactionId: Int? = null // Track latest for final save
        var syncTimeoutJob: Job? = null // Job for auto-sync timeout

        // Check if full resync is needed (e.g., after destructive database migration)
        // SKIP if skipCacheClear=true (used during error 115 recovery to preserve SPENT UTXOs)
        if (!skipCacheClear) {
            checkAndHandleResyncNeeded(address)
        } else {
            Log.i(TAG, "⚡ INCREMENTAL SYNC: Skipping cache clear for $address (preserving SPENT UTXOs)")
        }

        // Get resume point before starting subscription
        val lastId = syncStateManager.getLastProcessedTransactionId(address)
        if (lastId == null) {
            Log.i(TAG, "🔄 FULL SYNC: Starting subscription for $address from transaction ID: null (will replay ALL history)")
        } else {
            Log.i(TAG, "⏩ RESUME SYNC: Starting subscription for $address from transaction ID: $lastId")
        }
        send(SyncState.Connecting)

        // Start initial sync timeout: if no transactions arrive at all
        // (e.g. 0-balance address with no history), emit Synced after timeout
        syncTimeoutJob = launch {
            delay(SYNC_TIMEOUT_MS)
            send(SyncState.Synced(lastId ?: 0))
        }

        try {
            // Collect from subscription and map to sync states
            indexerClient.subscribeToUnshieldedTransactions(
                address = address,
                transactionId = lastId
            )
                .onCompletion { error ->
                    if (error != null) Log.e(TAG, "Subscription error", error)
                    syncTimeoutJob?.cancel()
                }
                .catch { error ->
                    syncTimeoutJob?.cancel()
                    throw error // Re-throw for retryWhen to handle
                }
                .collect { update ->
                // Process update
                val result = utxoManager.processUpdate(update)

                when (result) {
                    is UtxoManager.ProcessingResult.TransactionProcessed -> {
                        processedCount++
                        latestTransactionId = result.transactionId

                        // Emit syncing state
                        send(SyncState.Syncing(processedCount, result.transactionId))

                        // Cancel previous timeout job (new transaction arrived)
                        syncTimeoutJob?.cancel()

                        // Start new timeout: if no transactions arrive for SYNC_TIMEOUT_MS,
                        // assume we're synced (don't wait for server's slow Progress updates)
                        syncTimeoutJob = launch {
                            delay(SYNC_TIMEOUT_MS)
                            send(SyncState.Synced(result.transactionId))
                        }
                    }
                    is UtxoManager.ProcessingResult.ProgressUpdate -> {
                        // Track latest transaction ID for final save
                        latestTransactionId = result.highestTransactionId

                        // Save sync progress (throttled to reduce battery drain)
                        val now = System.currentTimeMillis()
                        val lastSave = lastSaveTimestamps[address] ?: 0L
                        val shouldSave = (now - lastSave) >= PROGRESS_SAVE_THROTTLE_MS

                        if (shouldSave) {
                            syncStateManager.saveLastProcessedTransactionId(address, result.highestTransactionId)
                            lastSaveTimestamps[address] = now
                        }

                        // Only emit Synced if we've processed at least one transaction
                        // This prevents a race condition where Progress arrives before
                        // transaction data, causing the collector to stop prematurely
                        if (processedCount > 0) {
                            send(SyncState.Synced(result.highestTransactionId))
                        } else {
                            // No transactions processed yet - wait for actual data or timeout
                            syncTimeoutJob?.cancel()
                            syncTimeoutJob = launch {
                                delay(SYNC_TIMEOUT_MS)
                                send(SyncState.Synced(result.highestTransactionId))
                            }
                        }
                    }
                }
            }
        } finally {
            // Clean up timeout job
            syncTimeoutJob?.cancel()

            // Save final progress on completion/cancellation (if we have any)
            latestTransactionId?.let { txId ->
                syncStateManager.saveLastProcessedTransactionId(address, txId)
                lastSaveTimestamps.remove(address)
            }
        }
    }

    /**
     * Calculate retry delay with exponential backoff.
     *
     * Formula: min(INITIAL_DELAY * 2^attempt, MAX_DELAY)
     * Example: 1s, 2s, 4s, 8s, 16s, 32s (max)
     */
    private fun calculateRetryDelay(attempt: Long): Long {
        val exponentialDelay = INITIAL_RETRY_DELAY_MS * 2.0.pow(attempt.toDouble()).toLong()
        return min(exponentialDelay, MAX_RETRY_DELAY_MS)
    }
}

/**
 * Sync state for UI/logging.
 */
sealed class SyncState {
    /**
     * Connecting to indexer.
     */
    object Connecting : SyncState()

    /**
     * Syncing transactions.
     *
     * @param processedCount Number of transactions processed so far
     * @param currentTransactionId Current transaction being processed
     */
    data class Syncing(
        val processedCount: Int,
        val currentTransactionId: Int
    ) : SyncState()

    /**
     * Synced up to highest available transaction.
     *
     * @param highestTransactionId Highest transaction ID available
     */
    data class Synced(
        val highestTransactionId: Int
    ) : SyncState()

    /**
     * Sync error (after retries exhausted).
     *
     * @param message Error message
     */
    data class Error(val message: String) : SyncState()
}
