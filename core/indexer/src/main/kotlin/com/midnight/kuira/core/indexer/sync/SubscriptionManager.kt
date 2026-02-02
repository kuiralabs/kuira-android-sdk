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
     * Check if a full resync is needed (e.g., after destructive database migration).
     * If so, clears the sync state so the subscription replays all history.
     *
     * **Edge Cases Handled:**
     * 1. Explicit flag from destructive migration callback
     * 2. Database is empty but sync state indicates we've processed transactions
     *    (e.g., user cleared app data manually, or database was wiped without callback)
     */
    private suspend fun checkAndHandleResyncNeeded(address: String) {
        val prefs = context.getSharedPreferences("utxo_db_flags", android.content.Context.MODE_PRIVATE)

        // Case 1: Explicit flag from destructive migration
        if (prefs.getBoolean("needs_full_resync", false)) {
            Log.w(TAG, "Full resync needed (database was recreated) - clearing sync state for $address")
            syncStateManager.clearSyncState(address)
            utxoManager.clearUtxos(address)
            prefs.edit().putBoolean("needs_full_resync", false).apply()
            Log.i(TAG, "Sync state and UTXOs cleared - will replay all history")
            return
        }

        // Case 2: Sync state exists but database is empty (data cleared without callback)
        // This catches cases where user clears app data, adb clear, etc.
        val lastProcessedId = syncStateManager.getLastProcessedTransactionId(address)
        val hasUtxos = utxoManager.hasAnyUtxos(address)
        val hasAvailableUtxos = utxoManager.hasAvailableUtxos(address)
        Log.d(TAG, "checkAndHandleResyncNeeded: lastProcessedId=$lastProcessedId, hasAnyUtxos=$hasUtxos, hasAvailableUtxos=$hasAvailableUtxos")

        // Debug: Dump current database state
        utxoManager.debugDumpAllUtxos(address, "SYNC_START")

        if (lastProcessedId != null && lastProcessedId > 0) {
            if (!hasUtxos) {
                Log.w(TAG, "Database is empty but sync state shows tx ID $lastProcessedId - clearing sync state")
                syncStateManager.clearSyncState(address)
                Log.i(TAG, "Sync state cleared - will replay all history to repopulate UTXOs")
            } else if (!hasAvailableUtxos) {
                // Case 3: Have UTXOs but ALL are SPENT - suspicious state!
                // This can happen if we prematurely marked UTXOs as SPENT but the sync
                // was already at the latest TX, so no correction occurred.
                // Force full resync to replay all history and restore correct state.
                Log.w(TAG, "⚠️ ALL UTXOs are SPENT (balance=0) - suspicious state, forcing full resync")
                syncStateManager.clearSyncState(address)
                utxoManager.clearUtxos(address)
                Log.i(TAG, "Sync state and UTXOs cleared - will replay all history to restore correct balance")
            } else {
                Log.d(TAG, "Database has available UTXOs, no resync needed")
            }
        }
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
     * @return Flow of sync states (Syncing, Synced, Error)
     */
    fun startSubscription(address: String): Flow<SyncState> {
        return createSubscriptionFlow(address)
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

    private fun createSubscriptionFlow(address: String): Flow<SyncState> = channelFlow {
        var processedCount = 0
        var latestTransactionId: Int? = null // Track latest for final save
        var syncTimeoutJob: Job? = null // Job for auto-sync timeout

        // Check if full resync is needed (e.g., after destructive database migration)
        checkAndHandleResyncNeeded(address)

        // Get resume point before starting subscription
        val lastId = syncStateManager.getLastProcessedTransactionId(address)
        if (lastId == null) {
            Log.i(TAG, "🔄 FULL SYNC: Starting subscription for $address from transaction ID: null (will replay ALL history)")
        } else {
            Log.i(TAG, "⏩ RESUME SYNC: Starting subscription for $address from transaction ID: $lastId")
        }
        send(SyncState.Connecting)

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
