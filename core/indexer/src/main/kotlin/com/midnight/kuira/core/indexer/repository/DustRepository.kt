package com.midnight.kuira.core.indexer.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.database.DustDao
import com.midnight.kuira.core.indexer.database.DustTokenEntity
import com.midnight.kuira.core.indexer.di.DustStateDataStore
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex

/**
 * Decode a hex string to bytes, streaming directly into the output array.
 *
 * The previous `hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()` allocated a List
 * of ~N/2 two-char Strings plus a List of boxed Bytes for an N-char hex string — tens of MB
 * of churn for a multi-MB dust checkpoint. This allocates only the result array. Top-level +
 * internal so it's unit-testable (decode correctness gates the dust-state deserialize). Two
 * hex chars per byte; assumes even length + valid hex digits.
 */
internal fun decodeHexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < out.size) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        out[i] = ((hi shl 4) or lo).toByte()
        i++
    }
    return out
}

/**
 * Repository for dust wallet operations.
 *
 * **Responsibilities:**
 * - Manage DustLocalState lifecycle (FFI bridge to Rust midnight-ledger)
 * - Sync dust tokens from DustLocalState to database (cache layer)
 * - Calculate current dust balance (time-based generation)
 * - Provide available dust tokens for fee payment
 * - Handle token state transitions (AVAILABLE → PENDING → SPENT)
 * - Persist DustLocalState to encrypted storage
 *
 * **Architecture:**
 * ```
 * DustRepository
 *  ├─ DustLocalState (FFI) - Source of truth, in-memory Rust state
 *  ├─ DustDao (Database) - Cache layer for fast queries
 *  └─ DataStore (Persistence) - Serialized state backup
 * ```
 *
 * **Usage:**
 * ```kotlin
 * @Inject lateinit var dustRepository: DustRepository
 *
 * // Initialize dust wallet
 * dustRepository.initializeIfNeeded(address)
 *
 * // Get current balance
 * val balance = dustRepository.getCurrentBalance(address)
 *
 * // Get tokens for fee payment
 * val tokens = dustRepository.getAvailableTokens(address)
 *
 * // Observe balance live
 * dustRepository.observeBalance(address).collect { balance ->
 *     updateUI(balance)
 * }
 * ```
 *
 * **Thread Safety:**
 * All methods are thread-safe (suspend functions use coroutine context).
 * DustLocalState instances are NOT thread-safe - use one per coroutine scope.
 */
@Singleton
class DustRepository @Inject constructor(
    private val dustDao: DustDao,
    @DustStateDataStore private val dustStateDataStore: DataStore<Preferences>,
    private val balanceCalculator: DustBalanceCalculator,
    private val indexerClient: com.midnight.kuira.core.indexer.api.IndexerClient,
    @com.midnight.kuira.core.indexer.di.DustSpillDir private val spillDirectory: java.io.File,
) {
    /**
     * The live in-memory state from the last successful sync.
     * Avoids serialize/deserialize which corrupts Merkle roots (SDK-001).
     * Callers should use [getLastSyncedState] instead of [loadState].
     */
    @Volatile
    private var lastSyncedState: DustLocalState? = null

    /** Get the live in-memory state from the last sync (no deserialization). */
    fun getLastSyncedState(): DustLocalState? = lastSyncedState

    /** Clear AND close the live in-memory state (force-resync / chain-reset wipe). */
    fun clearLastSyncedState() {
        lastSyncedState?.close()
        lastSyncedState = null
    }

    /**
     * Adopt [state] as the live in-memory cache, closing the one it replaces, so
     * [getLastSyncedState] stays warm without a deserialize. Takes ownership of [state].
     */
    private fun retainLastSyncedState(state: DustLocalState) {
        lastSyncedState?.close()
        lastSyncedState = state
    }

    companion object {
        private const val TAG = "DustRepository"

        /** How often the long replay reports progress, in events. */
        private const val PROGRESS_LOG_EVERY = 5000

        /** Cursor sentinel for "nothing streamed yet" — event ids start at 0. */
        private const val NO_EVENT_ID = -1L

        /** Bytes of the address digest used to name a spill file; 8 is far past collision risk. */
        private const val SPILL_NAME_BYTES = 8

        /** Replay spill file, written one hex event per line and read back natively. */
        private const val SPILL_PREFIX = "dust_events_"
        private const val SPILL_SUFFIX = ".hex"

        private const val SPILL_CURSOR_PREFIX = "dust_spill_cursor_"

        /** One checkpoint file per address: an 8-byte cursor followed by the serialized state. */
        private const val CHECKPOINT_PREFIX = "dust_checkpoint_"
        private const val CHECKPOINT_SUFFIX = ".bin"
        private const val CHECKPOINT_STAGING_SUFFIX = ".writing"

        /** A spill untouched for this long cannot belong to a sync that is still streaming. */
        private const val ABANDONED_SPILL_AGE_MS = 60 * 60 * 1000L

        /** Events per chunk — matches WASM SDK's chunk size. */
        private const val CHUNK_SIZE = 500

        /**
         * Timeout waiting for the first event in a delta sync.
         * Must be long enough for slow remote indexers (PREPROD) to start streaming.
         * If no event arrives within this window, we conclude "truly caught up."
         */
        private const val DELTA_FIRST_EVENT_TIMEOUT_MS = 30_000L

        // Key format: "dust_state_{address}"
        private fun dustStateKey(address: String) = stringPreferencesKey("dust_state_$address")

        // Key format: "dust_last_event_{address}" (for delta sync resume)
        private fun lastEventIdKey(address: String) = longPreferencesKey("dust_last_event_$address")

        // How far the genesis spill file has been streamed, so a restarted sync resumes the
        // (slow, network-bound) streaming phase instead of repeating it.
        private fun spillCursorKey(address: String) = longPreferencesKey("$SPILL_CURSOR_PREFIX$address")

        /** Bytes of the spill the cursor actually covers; anything past this is unaccounted for. */
        private fun spillLengthKey(address: String) = longPreferencesKey("dust_spill_bytes_$address")

        // Time for balance calculations
        private fun currentTimeMillis(): Long = System.currentTimeMillis()
    }

    // ========== Initialization ==========

    /**
     * Initialize dust wallet for an address if not already initialized.
     *
     * Creates new DustLocalState and persists it to storage.
     * If state already exists, does nothing (idempotent).
     *
     * **When to call:**
     * - On first wallet creation
     * - When user wants to start using dust features
     * - Before any other dust operations
     *
     * @param address Wallet address to initialize dust for
     * @return true if initialized, false if already exists
     */
    suspend fun initializeIfNeeded(address: String): Boolean {
        // Check if state already exists
        val existing = getSerializedState(address)
        if (existing != null) {
            return false
        }

        // Create new DustLocalState
        val state = DustLocalState.create()
            ?: error("Failed to create DustLocalState - native library not loaded")

        try {
            // Serialize and persist
            val serialized = state.serialize()
                ?: error("Failed to serialize initial dust state")

            saveSerializedState(address, serialized)

            return true
        } finally {
            state.close()
        }
    }

    // ========== Balance Queries ==========

    /**
     * Get current dust balance for an address.
     *
     * **Calculation:**
     * - Loads DustLocalState from persistent storage
     * - Calls native getBalance() with current time
     * - Returns total dust in Specks
     *
     * **Time-based Generation:**
     * Balance increases over time as dust generates from registered Night UTXOs.
     * Calling this method multiple times with different timestamps returns different values.
     *
     * **Units:**
     * - Returns: Specks (smallest dust unit)
     * - 1 Dust = 1,000,000 Specks
     * - To convert to Dust: balance / 1_000_000
     *
     * @param address Wallet address
     * @return Current balance in Specks (BigInteger to support u128)
     */
    suspend fun getCurrentBalance(address: String): BigInteger {
        // Use DustLocalState (source of truth) for balance calculation.
        // DustLocalState.getBalance() computes time-based dust generation natively.
        val state = loadState(address) ?: return BigInteger.ZERO
        try {
            return state.getBalance(currentTimeMillis())
        } finally {
            state.close()
        }
    }

    /**
     * Calculate balance from cached database tokens.
     *
     * **Fallback Method:**
     * Used when DustLocalState deserialization is not yet implemented.
     * Queries database for available tokens and calculates current value.
     *
     * **Limitations:**
     * - Less accurate than DustLocalState (database cache may be stale)
     * - Should be replaced with proper deserialization later
     *
     * @param address Wallet address
     * @return Estimated balance in Specks
     */
    private suspend fun calculateBalanceFromCache(address: String): BigInteger {
        val tokens = dustDao.getAvailableTokens(address)
        val currentTime = currentTimeMillis()

        return balanceCalculator.calculateTotalBalance(tokens, currentTime)
    }

    /**
     * Observe dust balance for an address (live updates).
     *
     * **Reactive Balance:**
     * Returns Flow that emits new balance whenever:
     * - Database cache is updated (new tokens, state changes)
     * - Time passes (dust generates)
     *
     * **Note on Time Updates:**
     * This Flow does NOT automatically emit every second as dust generates.
     * To show live generation, collect this Flow and also poll periodically:
     *
     * ```kotlin
     * combine(
     *     dustRepository.observeBalance(address),
     *     ticker(1000) // Emit every second
     * ) { balance, _ -> balance }
     * ```
     *
     * @param address Wallet address
     * @return Flow of balance in Specks
     */
    fun observeBalance(address: String): Flow<BigInteger> {
        return dustDao.observeAvailableTokens(address)
            .map { tokens ->
                val currentTime = currentTimeMillis()
                balanceCalculator.calculateTotalBalance(tokens, currentTime)
            }
    }

    // ========== Token Queries ==========

    /**
     * Get all available dust tokens for an address.
     *
     * **Used for:**
     * - Fee payment coin selection
     * - Displaying dust UTXO list to user
     *
     * **Current Value:**
     * Token values in database are static (initial value).
     * Call calculateCurrentValue() on each token to get time-adjusted value.
     *
     * @param address Wallet address
     * @return List of available dust tokens
     */
    suspend fun getAvailableTokens(address: String): List<DustTokenEntity> {
        return dustDao.getAvailableTokens(address)
    }

    /**
     * Get available dust tokens sorted by value (smallest first).
     *
     * **Coin Selection:**
     * Smallest-first selection minimizes UTXO fragmentation and improves privacy.
     * This is the strategy used by midnight-ledger.
     *
     * **Note:**
     * Database sorts by initial_value. For exact sorting by current value,
     * calculate current value on each token and sort in Kotlin.
     *
     * @param address Wallet address
     * @return List of available tokens sorted by value
     */
    suspend fun getAvailableTokensSorted(address: String): List<DustTokenEntity> {
        return dustDao.getAvailableTokensSorted(address)
    }

    /**
     * Get dust token count for an address.
     *
     * @param address Wallet address
     * @return Number of available dust tokens
     */
    suspend fun getTokenCount(address: String): Int {
        return dustDao.countAvailable(address)
    }

    // ========== State Transitions ==========

    /**
     * Mark dust tokens as pending (lock for transaction).
     *
     * **When to call:**
     * Before creating transaction with dust fee payment.
     * Prevents tokens from being double-spent.
     *
     * **State transition:**
     * AVAILABLE → PENDING
     *
     * @param nullifiers List of hex-encoded nullifiers to lock
     */
    suspend fun markTokensAsPending(nullifiers: List<String>) {
        if (nullifiers.isEmpty()) return
        dustDao.markAsPending(nullifiers)
        Log.d(TAG, "Marked ${nullifiers.size} dust tokens as pending")
    }

    /**
     * Mark dust tokens as spent (transaction confirmed).
     *
     * **When to call:**
     * After transaction with dust fee payment is confirmed successful.
     *
     * **State transition:**
     * PENDING → SPENT
     *
     * @param nullifiers List of hex-encoded nullifiers to mark spent
     */
    suspend fun markTokensAsSpent(nullifiers: List<String>) {
        if (nullifiers.isEmpty()) return
        dustDao.markAsSpent(nullifiers)
        Log.d(TAG, "Marked ${nullifiers.size} dust tokens as spent")
    }

    /**
     * Mark dust tokens as available (unlock after transaction failure).
     *
     * **When to call:**
     * After transaction with dust fee payment fails or is cancelled.
     * Unlocks tokens so they can be used in future transactions.
     *
     * **State transition:**
     * PENDING → AVAILABLE
     *
     * @param nullifiers List of hex-encoded nullifiers to unlock
     */
    suspend fun markTokensAsAvailable(nullifiers: List<String>) {
        if (nullifiers.isEmpty()) return
        dustDao.markAsAvailable(nullifiers)
        Log.d(TAG, "Marked ${nullifiers.size} dust tokens as available")
    }

    // ========== Synchronization ==========

    /**
     * Sync dust from blockchain (query events and replay into local state).
     *
     * **Production Method:**
     * This is the main entry point for dust synchronization.
     * Call this when loading balances to ensure dust is up-to-date.
     *
     * **Process:**
     * 1. Query dust events from indexer (scans recent blocks)
     * 2. Create or load DustLocalState
     * 3. Replay events into state (using dust secret key)
     * 4. Sync tokens from state to database cache
     * 5. Save updated state to persistent storage
     *
     * **Requirements:**
     * - Dust must be registered on-chain (via Lace or dApp)
     * - Dust secret key must be derived from wallet seed at m/44'/2400'/0'/2/0
     *
     * @param address Wallet address to sync dust for
     * @param dustSeed 32-byte dust secret key (derived at role 2, index 0)
     * @param maxBlocks Number of recent blocks to scan (default: 100)
     * @return true if sync succeeded, false if no dust registered or sync failed
     */
    suspend fun syncFromBlockchain(
        address: String,
        dustSeed: ByteArray,
        maxBlocks: Int = 100,
        onProgress: (suspend (eventsProcessed: Int, totalEvents: Int) -> Unit)? = null,
    ): Boolean = syncMutexFor(address).withLock {
        Log.d(TAG, "Syncing dust from blockchain for $address")
        discardAbandonedSpills(address)

        try {
            // Try delta sync first: load the checkpoint (state + cursor) as ONE
            // atomic snapshot. Reading the two halves with separate DataStore
            // emissions could observe a torn pair — DustSubscriptionManager's
            // atomic saveCheckpoint landing between the reads would pair an old
            // state frontier with a new cursor, so the delta would insert at the
            // wrong index and fail NonLinearInsertion. loadCheckpoint reads both
            // keys from a single emission, so the pair is always self-consistent.
            val checkpoint = loadCheckpoint(address)

            if (checkpoint != null) {
                val existingState = checkpoint.state
                val lastEventId = checkpoint.lastEventId
                // Reorg guard: if the chain's dust-event tip is now BELOW our
                // checkpoint, the chain reset under us (deep reorg / localnet
                // re-genesis). A delta resume from lastEventId+1 would then find no
                // events and silently keep the stale checkpoint — the bug behind a
                // dust balance that survives a chain reset (mirrors the UTXO side's
                // `serverMax < expected` reorg detection). `maxId` is the indexer's
                // current dust tip; -1 means the chain has no dust events at all
                // (so our checkpoint can't be valid). A timeout leaves `tip` null →
                // fall through to the normal delta, behavior unchanged.
                val tip = withTimeoutOrNull(DELTA_FIRST_EVENT_TIMEOUT_MS) {
                    indexerClient.subscribeToDustEvents(fromId = null).firstOrNull()?.maxId ?: -1L
                }
                when (dustSyncPlan(tip = tip, lastEventId = lastEventId)) {
                    DustSyncPlan.REORG -> {
                        Log.w(TAG, "Dust reorg detected: chain tip $tip < checkpoint $lastEventId — rebuilding from genesis")
                        existingState.close()
                        deleteState(address)
                        // fall through to full sync below
                    }
                    DustSyncPlan.AT_TIP -> {
                        // The checkpoint already covers every dust event (`maxId`, fetched
                        // above, equals our cursor), so a delta from lastEventId+1 has nothing
                        // to fetch. Calling streamDustEvents here would block the full
                        // DELTA_FIRST_EVENT_TIMEOUT_MS waiting on a live subscription that never
                        // emits (the chain hasn't advanced past our cursor) — the dominant
                        // "slow even at the tip" stall. Short-circuit instead. The dust *value*
                        // is unaffected: getCurrentBalance recomputes it from this state + the
                        // current time on every read. Retain the loaded state as the live cache
                        // (mirrors streamDustEvents' no-new-events path), taking ownership of the
                        // caller-owned checkpoint state.
                        Log.d(TAG, "At tip (event $lastEventId) — no new dust events, checkpoint current")
                        retainLastSyncedState(existingState)
                        return true
                    }
                    DustSyncPlan.DELTA -> {
                        // New events to apply (tip > cursor), or tip unknown (reorg-guard timed
                        // out) → try a delta from the checkpoint cursor. Replays only events
                        // after the checkpoint ON TOP of the loaded state (streamDustEvents takes
                        // ownership of it).
                        Log.d(TAG, "Delta sync: resuming from event $lastEventId")
                        val applied = try {
                            streamDustEvents(
                                address, dustSeed, fromId = lastEventId + 1, baseState = existingState, onProgress = onProgress,
                            )
                        } catch (e: CheckpointRejectedDelta) {
                            Log.w(
                                TAG,
                                "Checkpoint cannot apply event ${e.eventId} — its state is behind its own cursor; " +
                                    "rebuilding from genesis",
                            )
                            null
                        }
                        if (applied == true) return true
                        if (applied == false) Log.w(TAG, "Delta replay produced no state — rebuilding from genesis")
                        deleteState(address)
                    }
                }
            }

            // Full sync: stream all events, then replay in a single pass.
            // Single-pass replay ensures Merkle tree collapses and rehash happen
            // exactly once, producing roots that match the node's root history.
            Log.d(TAG, "Full sync from genesis")

            val result = streamDustEvents(address, dustSeed, fromId = null, onProgress = onProgress)
            if (!result) {
                Log.d(TAG, "No dust events found, dust not registered yet")
            }
            return result

        } catch (e: CancellationException) {
            throw e // Never swallow cancellation in suspend functions
        } catch (e: Exception) {
            // A FAILED sync says nothing about the checkpoint, so it must not be reported as
            // `false` — that is this function's word for "synced fine, this address has no dust",
            // and the caller's recovery for that is to delete the checkpoint and rebuild from
            // genesis. One dropped WebSocket ping then destroys a multi-minute replay and starts
            // it over, which on PreProd means it can never finish.
            Log.e(TAG, "Failed to sync dust from blockchain", e)
            throw e
        }
    }

    /**
     * Stream dust events and replay in 500-event chunks, keeping state in memory.
     *
     * This matches EXACTLY how the WASM SDK processes events:
     * - 500 events per replayEvents call
     * - State pointer kept alive between chunks (never serialized)
     * - mn transfer works on PREPROD with this pattern
     *
     * CRITICAL: Do NOT serialize/deserialize the state between chunks.
     * Do NOT use single-pass replay (produces different roots at scale).
     */
    private suspend fun streamDustEvents(
        address: String,
        dustSeed: ByteArray,
        fromId: Long?,
        baseState: DustLocalState? = null,
        onProgress: (suspend (eventsProcessed: Int, totalEvents: Int) -> Unit)? = null,
    ): Boolean {
        // Delta resume: when baseState is provided, the streamed events are only
        // the delta since the last checkpoint and are replayed ON TOP of it
        // (replayEventsFromFile clones the receiver). Ownership of baseState
        // transfers here — it is closed after replay, or kept as lastSyncedState
        // if there were no new events. When null, a fresh state is built (full
        // sync from genesis).
        // Write events to a temp file (one hex per line). Rust reads the file
        // in native memory and replays in 500-event chunks — proven identical
        // to WASM at full PREPROD scale (253k events, roots match byte-for-byte).
        //
        // CRITICAL: Do NOT use hex-concatenation + tag-prefix splitting.
        // The tag prefix can appear in SCALE binary data, causing false splits
        // that corrupt events and produce wrong Merkle roots.
        // A genesis sync resumes its own spill file when one survived an earlier attempt; a
        // delta sync always starts a fresh one, since it is bounded and cheap by definition.
        val tempFile = spillFileFor(address)
        var resumeFrom = if (fromId == null) loadSpillCursor(address)?.takeIf { tempFile.exists() } else null
        if (resumeFrom == null) {
            discardSpill(address)
        } else if (!trimSpillToCursor(address, tempFile)) {
            // The file reaches past what the cursor accounts for and we cannot tell by how much,
            // so appending would duplicate events. Streaming 1.7M again costs minutes; replaying a
            // file with events out of order costs the sync and says so only at the very end.
            //
            // Dropping the cursor matters as much as dropping the file: left set, the stream
            // resumes from it onto the empty state the discard just created, and the few thousand
            // events from the tail replay against a tree that starts at zero — MerkleTreeUpdate
            // (BadUpdatePath), after a restart that only restarted the file.
            Log.w(TAG, "Spill cannot be reconciled with its cursor — restarting the stream from genesis")
            discardSpill(address)
            resumeFrom = null
        }
        var totalEvents = 0
        var latestEventId = resumeFrom ?: (getLastAppliedEventId(address) ?: NO_EVENT_ID)
        var reachedTheEnd = false
        if (resumeFrom != null) {
            Log.i(TAG, "Resuming the genesis spill at event $resumeFrom (${tempFile.length() / 1024 / 1024}MB already streamed)")
        }

        try {
            try {
                java.io.BufferedWriter(java.io.FileWriter(tempFile, resumeFrom != null)).use { writer ->
                    val flow = indexerClient.subscribeToDustEvents(fromId = resumeFrom?.plus(1) ?: fromId)
                    val isDelta = fromId != null

                    // Stream into the writer, reconnecting when the transport drops, for as long
                    // as each reconnect keeps making forward progress. A PreProd genesis replay
                    // is ~1.7M events over many minutes and the WebSocket's pong timeout fires
                    // every 20-60s; failing the whole sync on a drop discarded every event
                    // received so far, so the next attempt restarted at zero and the sync could
                    // never converge. A reconnect that yields nothing new still fails, so a
                    // genuinely dead indexer is not retried forever.
                    suspend fun streamInto(initial: Flow<RawLedgerEvent>) {
                        var stream = initial
                        var resumedPast = NO_EVENT_ID
                        while (true) {
                            try {
                                stream.collect { event ->
                                    writer.write(event.rawHex)
                                    writer.newLine()
                                    latestEventId = event.id
                                    totalEvents++

                                    if (totalEvents % PROGRESS_LOG_EVERY == 0) {
                                        Log.d(TAG, "Streaming progress: $totalEvents events (id=$latestEventId/${event.maxId})")
                                        onProgress?.invoke(totalEvents, event.maxId.toInt())
                                        // Genesis only: the cursor is what makes a restart cheap.
                                        if (fromId == null) saveSpillCursor(address, writer, latestEventId)
                                    }

                                    if (event.id >= event.maxId) {
                                        throw StreamingCompleteSignal()
                                    }
                                }
                                return
                            } catch (e: StreamingCompleteSignal) {
                                throw e
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (latestEventId <= resumedPast) throw e
                                resumedPast = latestEventId
                                if (fromId == null) saveSpillCursor(address, writer, latestEventId)
                                Log.w(
                                    TAG,
                                    "Dust stream dropped after $totalEvents events (id=$latestEventId) — resuming: ${e.message}",
                                )
                                stream = indexerClient.subscribeToDustEvents(fromId = latestEventId + 1)
                            }
                        }
                    }

                    if (isDelta) {
                        // Wait up to the timeout for the first new event. firstOrNull
                        // returns null if the subscription completes with nothing new;
                        // withTimeoutOrNull returns null if none arrives in time — either
                        // way the checkpoint is already current, totalEvents stays 0, and
                        // the totalEvents == 0 block below keeps the loaded state.
                        //
                        // This previously collected the first event then threw
                        // StreamingCompleteSignal, which unwound straight to the outer
                        // catch — skipping the write + resume, so every delta applied zero
                        // events and the checkpoint never advanced past its seed.
                        val firstEvent = withTimeoutOrNull(DELTA_FIRST_EVENT_TIMEOUT_MS) {
                            flow.firstOrNull()
                        }

                        if (firstEvent != null) {
                            writer.write(firstEvent.rawHex)
                            writer.newLine()

                            // One event is enough to know whether this checkpoint can take the
                            // delta at all: a state that sits behind its own cursor rejects the
                            // very first insertion. Finding that out here costs a serialize and
                            // one replay; finding it out after the stream costs the whole gap —
                            // 368k events on a PreProd device — and then the genesis rebuild
                            // anyway.
                            if (baseState != null) {
                                writer.flush()
                                if (!checkpointAccepts(baseState, dustSeed, tempFile)) {
                                    baseState.close()
                                    throw CheckpointRejectedDelta(firstEvent.id)
                                }
                            }

                            latestEventId = firstEvent.id
                            totalEvents++

                            if (firstEvent.id < firstEvent.maxId) {
                                streamInto(indexerClient.subscribeToDustEvents(fromId = firstEvent.id + 1))
                            }
                        }
                    } else {
                        streamInto(flow)
                    }
                }
            } catch (_: StreamingCompleteSignal) {
                // Normal completion
            }

            if (totalEvents == 0) {
                if (baseState != null) {
                    // Delta resume with nothing new on chain — the loaded checkpoint
                    // is already current. Keep it live for callers.
                    retainLastSyncedState(baseState)
                    reachedTheEnd = true
                    return true
                }
                reachedTheEnd = true
                return fromId != null
            }

            // Signal: streaming done, now replaying (this is the long part)
            onProgress?.invoke(totalEvents, totalEvents) // 100% streaming
            // Use negative totalEvents as sentinel for "replaying" phase
            onProgress?.invoke(-1, totalEvents)

            // Delta resume replays onto the loaded checkpoint; full sync onto a
            // fresh empty state.
            val state = baseState ?: (DustLocalState.create()
                ?: run {
                    Log.e(TAG, "Failed to create DustLocalState")
                    return false
                })

            val replayKind = if (baseState != null) "delta onto checkpoint" else "full from genesis"
            Log.d(TAG, "Replaying $totalEvents events ($replayKind, ${tempFile.length() / 1024}KB file)")
            val newState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                state.replayEventsFromFile(dustSeed, tempFile.absolutePath)
            }
            state.close()

            if (newState == null) {
                Log.e(TAG, "Dust replay failed after $totalEvents events")
                return false
            }

            // Save checkpoint (best-effort) but keep state alive for caller
            val utxoCount = newState.getUtxoCount()
            syncTokensFromState(address, newState)
            try {
                saveCheckpoint(address, newState, latestEventId)
            } catch (e: Exception) {
                Log.w(TAG, "Checkpoint save failed (non-fatal): ${e.message}")
            }
            Log.d(TAG, "Sync complete: $utxoCount UTXOs, $totalEvents events, last ID=$latestEventId")

            // Keep the live state so same-process callers skip a re-deserialize.
            // (Serialization is root-lossless — proven by the compact-engine
            // serialize_deserialize_reproduces_root test — so this is a perf
            // shortcut, not a correctness workaround; the saved checkpoint above
            // is authoritative across process restarts.)
            retainLastSyncedState(newState)

            // Success = the sync completed and the checkpoint is saved, NOT "has
            // dust". Returning utxoCount > 0 here made a 0-UTXO wallet look like a
            // failed delta sync, so syncFromBlockchain deleted the checkpoint and
            // re-synced from genesis on every refresh. UTXO count is a separate
            // query (getUtxoCount / getBalance).
            reachedTheEnd = true
            return true
        } finally {
            // A genesis spill that did NOT reach the end is the resume point for the next
            // attempt — the ~200MB of events already streamed, which is the expensive part.
            // Everything else is dead weight in a cache dir Android reclaims when over quota.
            if (reachedTheEnd || fromId != null || latestEventId == NO_EVENT_ID) {
                discardSpill(address)
            } else {
                Log.i(TAG, "Keeping the genesis spill at event $latestEventId for the next attempt")
            }
        }
    }

    /**
     * One sync at a time per address.
     *
     * DustSyncManager holds a mutex, but MidnightSdk's shielded-send path and
     * TransactionSubmitter's error-196 recovery call this directly and bypass it. Two syncs on one
     * address share a spill file, a checkpoint and a live state, so the lock belongs on the
     * resource rather than on one of the callers.
     */
    private fun syncMutexFor(address: String): Mutex =
        syncMutexes.getOrPut(address) { Mutex() }

    private val syncMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * This address's genesis spill file — one deterministic path, not a fresh temp file per
     * attempt, so an interrupted sync can resume it instead of streaming 1.7M events again.
     *
     * Lives in [spillDirectory] rather than the cache dir `java.io.tmpdir` resolves to on
     * Android: a PreProd spill reaches ~1.5GB, the platform reclaims app caches under storage
     * pressure, and it took one mid-replay — leaving the native reader with "No such file or
     * directory" after eight minutes of streaming.
     */
    private fun spillFileFor(address: String): java.io.File =
        java.io.File(spillDirectory, "$SPILL_PREFIX${addressDigest(address)}$SPILL_SUFFIX")

    /** Short, stable, filesystem-safe stand-in for an address. */
    private fun addressDigest(address: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(address.toByteArray())
            .take(SPILL_NAME_BYTES)
            .joinToString("") { "%02x".format(it) }

    /** How far [spillFileFor] has been streamed, or null when there is nothing to resume. */
    private suspend fun loadSpillCursor(address: String): Long? =
        dustStateDataStore.data.first()[spillCursorKey(address)]

    /**
     * Record how far the spill file is good for — as an event id AND as a byte count.
     *
     * The flush MUST precede the write: the cursor is a promise that those events are on disk, and
     * a resume that trusts a buffered cursor would skip events that never landed.
     *
     * The byte count matters just as much. The cursor is saved every few thousand events while the
     * writer keeps going, so when a sync dies the file reaches further than the cursor admits.
     * Appending from the cursor then writes those events a SECOND time, and the replay meets an
     * event it has already passed — `EventForPastTime`, eight hours of chain time out of order,
     * after a 1.4GB stream. Truncating to this length on resume keeps the two in step.
     */
    private suspend fun saveSpillCursor(address: String, writer: java.io.Writer, cursor: Long) {
        writer.flush()
        val bytes = spillFileFor(address).length()
        dustStateDataStore.edit { prefs ->
            prefs[spillCursorKey(address)] = cursor
            prefs[spillLengthKey(address)] = bytes
        }
    }

    /**
     * Cut the spill back to exactly the bytes its cursor covers.
     *
     * Returns false when the two cannot be reconciled — no recorded length (a spill written before
     * this was tracked) or a file shorter than claimed — leaving the caller to start over rather
     * than append onto an unknown tail.
     */
    private suspend fun trimSpillToCursor(address: String, spill: java.io.File): Boolean {
        val accounted = dustStateDataStore.data.first()[spillLengthKey(address)] ?: return false
        val actual = spill.length()
        if (actual < accounted) return false
        if (actual == accounted) return true
        Log.i(TAG, "Trimming ${actual - accounted} unaccounted bytes off the spill before resuming")
        return runCatching {
            java.io.RandomAccessFile(spill, "rw").use { it.setLength(accounted) }
        }.isSuccess
    }

    private suspend fun discardSpill(address: String) {
        spillFileFor(address).delete()
        dustStateDataStore.edit { prefs ->
            prefs.remove(spillCursorKey(address))
            prefs.remove(spillLengthKey(address))
        }
    }

    /**
     * Reclaim spill files that no sync can ever read again.
     *
     * Before the spill was named per address it was named per attempt, so every failed genesis
     * sync left its stream behind — nine files and 1.0GB on one device here. Nothing collects
     * those: the current code only knows the name it would write itself.
     *
     * A file is kept when it is this address's spill, when it is the retained spill of an address
     * that still has a resume cursor, or when it was written recently enough to belong to a sync
     * that is still running.
     */
    private suspend fun discardAbandonedSpills(currentAddress: String) {
        val directory = spillDirectory
        val spillsInUse = dustStateDataStore.data.first().asMap().keys
            .map { it.name }
            .filter { it.startsWith(SPILL_CURSOR_PREFIX) }
            .map { spillFileFor(it.removePrefix(SPILL_CURSOR_PREFIX)).name }
            .toSet() + spillFileFor(currentAddress).name
        val writtenBefore = currentTimeMillis() - ABANDONED_SPILL_AGE_MS

        directory.listFiles { file ->
            file.name.startsWith(SPILL_PREFIX) && file.name.endsWith(SPILL_SUFFIX)
        }?.forEach { file ->
            if (file.name in spillsInUse || file.lastModified() >= writtenBefore) return@forEach
            val megabytes = file.length() / 1024 / 1024
            if (file.delete()) Log.i(TAG, "Deleted an abandoned spill: ${file.name} (${megabytes}MB)")
        }
    }

    /**
     * Can [baseState] take the delta that starts in [firstEvents]?
     *
     * The probe runs on a copy, through the same file replay the real sync uses, so a pass here
     * means the same thing a pass there does. Serialization is root-lossless, so the copy's
     * commitment frontier is the checkpoint's own.
     */
    private fun checkpointAccepts(
        baseState: DustLocalState,
        dustSeed: ByteArray,
        firstEvents: java.io.File,
    ): Boolean {
        val serialized = baseState.serialize() ?: return true
        val copy = DustLocalState.deserialize(serialized) ?: return true
        return copy.use { probe ->
            val applied = probe.replayEventsFromFile(dustSeed, firstEvents.absolutePath)
            applied?.close()
            applied != null
        }
    }

    /**
     * This address's checkpoint file: its cursor and its serialized state, in that order.
     *
     * One file per address, because they used to share a single Preferences file. DataStore
     * rewrites that file whole on every edit, so each delta for one wallet also rewrote every
     * other wallet's ~870KB blob — and the blob was stored as hex, doubling it. Two wallets on
     * one device already cost 1.74MB a write, several seconds apart, for a few events.
     */
    private fun checkpointFileFor(address: String): java.io.File =
        java.io.File(spillDirectory, "$CHECKPOINT_PREFIX${addressDigest(address)}$CHECKPOINT_SUFFIX")

    /**
     * Read this address's cursor and state, migrating a Preferences-era checkpoint on the way.
     *
     * Devices synced before this change keep their state in the old keys; re-syncing 1.7M events
     * to move house is not a migration anyone should pay for, so the first read rewrites it as a
     * file and drops the old keys.
     */
    private suspend fun readCheckpoint(address: String): Pair<Long, ByteArray>? {
        val file = checkpointFileFor(address)
        if (file.exists()) {
            return runCatching {
                val bytes = file.readBytes()
                if (bytes.size < Long.SIZE_BYTES) return@runCatching null
                val cursor = java.nio.ByteBuffer.wrap(bytes, 0, Long.SIZE_BYTES).long
                cursor to bytes.copyOfRange(Long.SIZE_BYTES, bytes.size)
            }.getOrNull()
        }

        val prefs = dustStateDataStore.data.first()
        val legacyState = prefs[dustStateKey(address)]
        val legacyCursor = prefs[lastEventIdKey(address)]
        if (legacyState == null && legacyCursor == null) return null

        val serialized = legacyState?.let { hexStringToBytes(it) } ?: ByteArray(0)
        val cursor = legacyCursor ?: NO_EVENT_ID
        Log.i(TAG, "Migrating this address's checkpoint out of the shared preferences file")
        writeCheckpoint(address, cursor, serialized)
        dustStateDataStore.edit { editable ->
            editable.remove(dustStateKey(address))
            editable.remove(lastEventIdKey(address))
        }
        return cursor to serialized
    }

    /**
     * Write the pair as one file, atomically.
     *
     * Both halves land together or neither does: a state at one frontier paired with a cursor
     * implying another is the torn checkpoint that produces `NonLinearInsertion` and sends the
     * sync back to genesis. A temp file plus a rename gives that for free, where two keys in a
     * shared store needed a transaction.
     */
    private fun writeCheckpoint(address: String, lastEventId: Long, serialized: ByteArray) {
        val file = checkpointFileFor(address)
        file.parentFile?.mkdirs()
        val staging = java.io.File(file.parentFile, "${file.name}$CHECKPOINT_STAGING_SUFFIX")
        staging.outputStream().use { out ->
            out.write(java.nio.ByteBuffer.allocate(Long.SIZE_BYTES).putLong(lastEventId).array())
            out.write(serialized)
        }
        if (!staging.renameTo(file)) {
            staging.delete()
            throw java.io.IOException("Could not replace the dust checkpoint for $address")
        }
    }

    /** A checkpoint whose state sits behind its own cursor, so the delta can never apply. */
    private class CheckpointRejectedDelta(val eventId: Long) : Exception()

    /**
     * Replay a chunk of events into the state IN MEMORY. No serialization.
     * Returns the new state; closes the old one.
     */
    private fun replayChunkInMemory(
        currentState: DustLocalState,
        dustSeed: ByteArray,
        chunk: MutableList<String>,
    ): DustLocalState {
        val chunkHex = chunk.joinToString("")
        val newState = currentState.replayEvents(dustSeed, chunkHex)
            ?: throw IllegalStateException("Dust chunk replay failed")
        currentState.close()
        chunk.clear()
        return newState
    }

    /** Signal to break out of Flow.collect when caught up to chain tip. */
    private class StreamingCompleteSignal : Exception()

    /**
     * Sync dust tokens from DustLocalState to database cache.
     *
     * **When to call:**
     * - After blockchain events that affect dust (registrations, spends)
     * - After deserializing DustLocalState from backup
     * - Periodically to ensure cache is fresh
     *
     * **Process:**
     * 1. Load DustLocalState from persistent storage
     * 2. Get all UTXOs from DustLocalState via FFI
     * 3. Parse and insert into database
     * 4. Close DustLocalState
     *
     * @param address Wallet address to sync
     */
    suspend fun syncTokensToCache(address: String) {
        val serialized = getSerializedState(address) ?: run {
            Log.d(TAG, "No dust state to sync for $address")
            return
        }

        // Deserialize DustLocalState
        val state = DustLocalState.deserialize(serialized) ?: run {
            Log.e(TAG, "Failed to deserialize dust state for $address")
            return
        }

        try {
            syncTokensFromState(address, state)
        } finally {
            // Always close state
            state.close()
        }

        Log.d(TAG, "Synced dust tokens for $address")
    }

    /**
     * Sync tokens from DustLocalState to database (internal helper).
     *
     * **Simplified MVP Implementation:**
     * Creates placeholder entries in database for each UTXO.
     * Actual values are calculated from DustLocalState when needed for fee payment.
     *
     * **Why simplified:**
     * - DustLocalState UTXOs are SCALE-encoded (complex to parse)
     * - DustTokenEntity requires many fields not easily extracted
     * - For fee payment, DustActionsBuilder uses DustLocalState directly anyway
     * - Database is only for checking if dust exists
     *
     * **What we store:**
     * - Placeholder entries with UTXO index as nullifier
     * - Minimal required fields to satisfy database schema
     * - Marks as AVAILABLE for coin selection
     *
     * @param address Wallet address
     * @param state DustLocalState instance (already open)
     */
    private suspend fun syncTokensFromState(address: String, state: DustLocalState) {
        val utxoCount = state.getUtxoCount()
        Log.d(TAG, "Syncing $utxoCount UTXOs to database")

        if (utxoCount == 0) {
            Log.d(TAG, "No UTXOs to sync")
            return
        }

        try {
            // Create simplified token entities
            val tokens = mutableListOf<DustTokenEntity>()
            val currentTime = currentTimeMillis()

            for (i in 0 until utxoCount) {
                // Create placeholder token with minimal info
                // Use index as nullifier for now
                val token = DustTokenEntity(
                    nullifier = "utxo_$i",  // Placeholder nullifier
                    address = address,
                    initialValue = "0",  // Will calculate from state when needed
                    creationTimeMillis = currentTime,
                    nightUtxoId = "unknown",  // Not extracted from UTXO yet
                    nightValueStars = "0",
                    dustCapacitySpecks = "0",
                    generationRatePerSecond = "0",
                    state = com.midnight.kuira.core.indexer.database.UtxoState.AVAILABLE,
                    lastUpdatedMillis = currentTime
                )
                tokens.add(token)
            }

            // Clear old tokens and insert new ones
            dustDao.deleteTokensForAddress(address)
            dustDao.insertTokens(tokens)

            Log.d(TAG, "✅ Synced $utxoCount placeholder tokens to database")
            Log.d(TAG, "⚠️  Using simplified sync - actual values calculated from DustLocalState")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync tokens to database", e)
        }
    }

    /**
     * Deserializes DustLocalState for use in fee payment.
     *
     * **Use Case:**
     * Called by DustActionsBuilder to get state pointer for creating DustSpend actions.
     *
     * **IMPORTANT:**
     * Caller MUST call saveState() after creating spends to persist the updated state.
     * Caller MUST call state.close() when done.
     *
     * @param address Wallet address
     * @return DustLocalState instance, or null if not found
     */
    suspend fun loadState(address: String): DustLocalState? {
        val serialized = getSerializedState(address) ?: return null
        return DustLocalState.deserialize(serialized)
    }

    /**
     * Saves updated DustLocalState after creating spends.
     *
     * **When to call:**
     * After calling state.spend() or other state-modifying operations.
     *
     * @param address Wallet address
     * @param state DustLocalState instance
     */
    suspend fun saveState(address: String, state: DustLocalState) {
        val serialized = state.serialize()
            ?: throw IllegalStateException("Failed to serialize dust state for $address")
        // Size drives the cross-device backup transport choice (Block Store caps
        // at 4KB/entry; larger needs Drive/file). Logged so we can measure it at
        // real PREPROD scale before wiring backup.
        Log.d(TAG, "Dust state serialized: ${serialized.size} bytes")
        saveSerializedState(address, serialized)
    }

    /**
     * Check if cached dust state exists for an address.
     *
     * **Use Case:**
     * Before doing expensive blockchain sync, check if we already have dust state.
     * This avoids blocking transactions with 5-10 minute sync operations.
     *
     * @param address Wallet address
     * @return true if cached state exists, false otherwise
     */
    suspend fun hasCachedState(address: String): Boolean {
        val key = dustStateKey(address)
        val hexString = dustStateDataStore.data.first()[key]
        return hexString != null
    }

    // ========== Persistence ==========

    /**
     * Get serialized DustLocalState for an address.
     *
     * @param address Wallet address
     * @return Serialized state bytes (hex-encoded), or null if not found
     */
    private suspend fun getSerializedState(address: String): ByteArray? =
        readCheckpoint(address)?.second

    /**
     * Save serialized DustLocalState for an address.
     *
     * @param address Wallet address
     * @param serialized Serialized state bytes
     */
    private suspend fun saveSerializedState(address: String, serialized: ByteArray) {
        writeCheckpoint(address, readCheckpoint(address)?.first ?: NO_EVENT_ID, serialized)
    }

    /**
     * Delete serialized DustLocalState for an address.
     *
     * **When to call:**
     * - Wallet reset
     * - Deep chain reorg
     *
     * @param address Wallet address
     */
    suspend fun deleteState(address: String) {
        checkpointFileFor(address).delete()
        val stateKey = dustStateKey(address)
        val eventKey = lastEventIdKey(address)
        dustStateDataStore.edit { prefs ->
            prefs.remove(stateKey)
            prefs.remove(eventKey)
        }
        dustDao.deleteTokensForAddress(address)
        // Drop the live in-memory pointer too — otherwise a chain-reset wipe clears the persisted
        // checkpoint but leaves the OLD-chain DustLocalState warm, which the fee balancer would then
        // spend against (stale dust root → error-170 family). "State is gone" must mean all of it.
        clearLastSyncedState()
        Log.d(TAG, "Deleted dust state + event ID for $address")
    }

    /** Get last applied dust event ID for delta sync resume. Null if never synced. */
    suspend fun getLastAppliedEventId(address: String): Long? =
        readCheckpoint(address)?.first?.takeIf { it != NO_EVENT_ID }

    /** Save last applied dust event ID (for delta sync resume). */
    suspend fun saveLastAppliedEventId(address: String, eventId: Long) {
        writeCheckpoint(address, eventId, readCheckpoint(address)?.second ?: ByteArray(0))
    }

    /**
     * A dust checkpoint = the serialized [DustLocalState] plus the resume cursor
     * ([lastEventId]) it was built up to. The two MUST describe the same chain
     * point: the commitment-tree frontier carried inside the state has to equal
     * the commitment index the event after [lastEventId] will try to insert at,
     * or delta replay fails with `NonLinearInsertion`. [state] is owned by the
     * caller and must be closed.
     */
    class DustCheckpoint(val state: DustLocalState, val lastEventId: Long)

    /**
     * Persist [state] and its resume cursor [lastEventId] as ONE atomic unit.
     *
     * The state and cursor live under two DataStore keys but are written in a
     * single [edit] transaction, so a process kill or a concurrent reader can
     * never observe (or back up to the cloud) a torn pair — a state at frontier
     * F alongside a cursor that implies a different frontier. That torn pairing
     * is what produced the `NonLinearInsertion` genesis-resync loop; saving the
     * pair atomically is the cure. Prefer this over separate [saveState] +
     * [saveLastAppliedEventId] calls everywhere a checkpoint is written.
     */
    suspend fun saveCheckpoint(address: String, state: DustLocalState, lastEventId: Long) {
        val serialized = state.serialize()
            ?: throw IllegalStateException("Failed to serialize dust state for $address")
        Log.d(TAG, "Checkpoint saved: ${serialized.size} bytes, lastEventId=$lastEventId")
        writeCheckpoint(address, lastEventId, serialized)
    }

    /**
     * Load the checkpoint (state + cursor) as ONE consistent snapshot. Reads both
     * keys from a single DataStore emission, so the returned pair can never be
     * torn across a concurrent write. Returns null if EITHER half is missing or
     * the state fails to deserialize — callers then fall back to genesis rather
     * than resuming on a half-checkpoint. The returned [DustCheckpoint.state] is
     * owned by the caller and must be closed.
     */
    suspend fun loadCheckpoint(address: String): DustCheckpoint? {
        val (lastEventId, serialized) = readCheckpoint(address) ?: return null
        if (lastEventId == NO_EVENT_ID || serialized.isEmpty()) return null
        val state = DustLocalState.deserialize(serialized) ?: return null
        return DustCheckpoint(state, lastEventId)
    }

    /**
     * True only when BOTH halves of a checkpoint are present — cheaper than
     * [loadCheckpoint] (no deserialize) for a presence test. A half-written
     * checkpoint reads as absent, forcing a clean genesis sync.
     */
    suspend fun hasCheckpoint(address: String): Boolean {
        val (lastEventId, serialized) = readCheckpoint(address) ?: return false
        return lastEventId != NO_EVENT_ID && serialized.isNotEmpty()
    }

    // ========== Utility Functions ==========

    /**
     * Convert bytes to hex string for DataStore storage.
     */
    private fun bytesToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Convert hex string to bytes.
     */
    private fun hexStringToBytes(hex: String): ByteArray = decodeHexToBytes(hex)
}

/** What a delta-sync attempt should do, given the chain dust-event tip vs our checkpoint cursor. */
internal enum class DustSyncPlan {
    /** Chain tip is below our cursor — the chain reset under us; rebuild from genesis. */
    REORG,

    /** Chain tip equals our cursor — already caught up; keep the checkpoint, skip the network. */
    AT_TIP,

    /** Chain tip is ahead, or unknown (tip probe timed out) — attempt a delta from the cursor. */
    DELTA,
}

/**
 * Pure decision seam (unit-tested) for [DustRepository.syncFromBlockchain]'s delta path.
 *
 * [tip] is the chain's current highest dust-event id (the reorg-guard `maxId` probe); null
 * means the probe timed out and the tip is unknown. [lastEventId] is our checkpoint cursor
 * (the last applied event id).
 *
 *  - `tip < lastEventId` → [DustSyncPlan.REORG] (chain reset below us).
 *  - `tip == lastEventId` → [DustSyncPlan.AT_TIP] (caught up; the old behavior here would
 *    block the full `DELTA_FIRST_EVENT_TIMEOUT_MS` on a subscription that never emits).
 *  - `tip > lastEventId` OR `tip == null` → [DustSyncPlan.DELTA] (real events to fetch, or
 *    unknown tip → fall back to the delta probe, preserving the prior behavior).
 */
internal fun dustSyncPlan(tip: Long?, lastEventId: Long): DustSyncPlan = when {
    tip == null -> DustSyncPlan.DELTA
    tip < lastEventId -> DustSyncPlan.REORG
    tip == lastEventId -> DustSyncPlan.AT_TIP
    else -> DustSyncPlan.DELTA
}
