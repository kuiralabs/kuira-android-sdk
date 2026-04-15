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
import kotlinx.coroutines.flow.emitAll
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
     * Wipe sync state + UTXOs for a given address and start over from genesis.
     *
     * **When this runs:**
     * - `forceFullResync = true` (e.g. Developer options "Force re-sync" button)
     * - Round 2+: reorg detected, or indexer reports our cached txId is unknown
     * - NOT on every app launch — that was the old behavior (Phase 8B.3 T1-21 fix).
     *
     * **What gets cleared:**
     * - `syncStateManager.lastProcessedTransactionId[address]` → null (replay all)
     * - `utxoManager` UTXOs for this address
     * - Migration flag `needs_full_resync`
     *
     * **What DOESN'T get cleared (and shouldn't):**
     * - Other addresses' state (per-address keying is preserved)
     * - Dust state (separate repo, separate replay model)
     * - WalletAddressCache (biometric-gated, orthogonal)
     */
    private suspend fun performFullResync(address: String) {
        val prefs = context.getSharedPreferences("utxo_db_flags", android.content.Context.MODE_PRIVATE)

        Log.i(TAG, "🔄 FULL RESYNC: Clearing local cache for $address - will rebuild from indexer")

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
     * **Incremental by default (Phase 8B.3 T1-21).** Previous behavior wiped the
     * local UTXO cache and sync state on every subscription start, causing a
     * visible zero-balance flicker at every app launch. Now the default path
     * resumes from the last saved `transactionId` — full resync only happens
     * when [forceFullResync] is true (Developer options) or a later round detects
     * a reorg / unknown-txId signal from the indexer.
     *
     * **Flow:**
     * 1. Read saved `lastProcessedTransactionId` from SyncStateManager.
     * 2. Subscribe from that ID (null = new address, replay from genesis).
     * 3. Process each update via UtxoManager.
     * 4. Save progress after each Progress update (throttled).
     * 5. On error: retry with exponential backoff.
     *
     * **Resumption:**
     * - First sync for an address: saved ID is null → replay all history.
     * - Warm launch: saved ID present → resume from it, no wipe, no flicker.
     *
     * **Reconnection:**
     * - Network errors: retry with exponential backoff (1s, 2s, 4s, 8s, 16s, 32s max).
     * - After max retries: emit error state and stop (caller must restart subscription).
     * - Retryable errors: IOException, connection failures, WebSocket errors.
     * - Non-retryable errors: CancellationException (user cancelled).
     *
     * **Force re-sync entry point (T1-17 Developer Options).** A UI-level
     * "Force re-sync" button should cancel the currently-collecting Flow
     * first, then re-call `startSubscription(address, forceFullResync = true)`.
     * That wipes local state once (not on every retry) and re-subscribes from
     * genesis. No separate public `forceFullResync(address)` method — the
     * flag on this function is the single authoritative entry point.
     *
     * @param address Unshielded address to sync.
     * @param forceFullResync When true, clear sync state + UTXOs for [address]
     *        before subscribing, forcing a replay from genesis. Default false
     *        (incremental). Intended for explicit recovery paths (Developer
     *        Options button, user-initiated "reset wallet cache" action) —
     *        NOT for normal launches. Reorg / indexer-wipe detection is
     *        automatic and doesn't need this flag.
     * @return Flow of sync states (Syncing, Synced, Error).
     */
    fun startSubscription(address: String, forceFullResync: Boolean = false): Flow<SyncState> = flow {
        // One-shot wipe BEFORE entering the retry loop. If we wiped inside
        // createSubscriptionFlow, every transient WebSocket error → retryWhen
        // would re-wipe and discard progress already replayed during this
        // attempt. Running it once out here means "force resync" means exactly
        // one clean-slate pass, regardless of network flakiness.
        if (forceFullResync) {
            performFullResync(address)
        }

        emitAll(
            createSubscriptionFlow(address)
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
        )
    }

    private fun createSubscriptionFlow(address: String): Flow<SyncState> = channelFlow {
        var processedCount = 0
        var latestTransactionId: Int? = null // Track latest for final save
        var syncTimeoutJob: Job? = null // Job for auto-sync timeout

        // Resume-point lookup. Any wipe that needed to happen was already done
        // by startSubscription (forceFullResync=true path). Here we simply read
        // whatever sync state exists and pass it to the indexer.
        val lastId = syncStateManager.getLastProcessedTransactionId(address)
        // Snapshot at subscription start — used by the reorg check below to
        // detect when the indexer reports a max txId lower than what we have
        // locally (reorg, indexer-wipe, dev localnet reset).
        var highestSeenBySession: Int = lastId ?: 0
        if (lastId == null) {
            Log.i(TAG, "🆕 FIRST SYNC: No saved tx id for $address — subscribing from genesis")
        } else {
            Log.i(TAG, "⏩ INCREMENTAL: Resuming $address from transaction id $lastId")
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
                        highestSeenBySession = maxOf(highestSeenBySession, result.transactionId)

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
                        val serverMax = result.highestTransactionId

                        // Reorg / indexer-wipe detection (T1-21 Round 2):
                        // The indexer never returns an error for an unknown cursor —
                        // empirically verified by poisoning the cursor with INT_MAX
                        // and observing only ordinary Progress updates in response.
                        // The only reliable signal is the Progress payload going
                        // *backwards* relative to what we've seen locally: either
                        // the saved resume point, or the highest id we've
                        // observed in this session (catches mid-subscription reorgs).
                        if (serverMax < highestSeenBySession) {
                            Log.w(
                                TAG,
                                "🔁 REORG detected for $address: " +
                                    "indexer highestTransactionId=$serverMax but we expected ≥$highestSeenBySession. " +
                                    "Wiping local state and restarting from genesis."
                            )
                            performFullResync(address)
                            syncTimeoutJob?.cancel()
                            // Prevent the `finally` block below from saving the
                            // now-stale latestTransactionId back into the sync
                            // state we just wiped. Race: performFullResync
                            // clears, `finally` would re-save otherwise.
                            latestTransactionId = null
                            // Throwing propagates through `retryWhen`, which
                            // restarts the subscription. Because sync state was
                            // just wiped, the restart subscribes from null
                            // (genesis) against the server's current truth.
                            throw ReorgDetectedException(
                                address = address,
                                localHighest = highestSeenBySession,
                                serverMax = serverMax
                            )
                        }
                        highestSeenBySession = maxOf(highestSeenBySession, serverMax)

                        // Track latest transaction ID for final save
                        latestTransactionId = serverMax

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
 * Raised from inside the subscription flow when the indexer reports a
 * `highestTransactionId` lower than what we have locally. Means the server's
 * view of the chain went backwards (reorg, indexer DB wiped, dev localnet
 * reset from genesis). [SubscriptionManager] catches this, wipes local state
 * via `performFullResync`, and lets [kotlinx.coroutines.flow.retryWhen]
 * restart the subscription cleanly from the new server truth.
 */
class ReorgDetectedException(
    val address: String,
    val localHighest: Int,
    val serverMax: Int,
) : RuntimeException(
    "Reorg detected for $address: local=$localHighest > server=$serverMax"
)

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
