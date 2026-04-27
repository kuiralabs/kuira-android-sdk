package com.midnight.kuira.core.indexer.repository

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
import kotlinx.coroutines.flow.map
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

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
    private val indexerClient: com.midnight.kuira.core.indexer.api.IndexerClient
) {
    companion object {
        private const val TAG = "DustRepository"

        /** Events per chunk for streaming sync. Balances memory vs checkpoint overhead. */
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
        android.util.Log.d(TAG, "Marked ${nullifiers.size} dust tokens as pending")
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
        android.util.Log.d(TAG, "Marked ${nullifiers.size} dust tokens as spent")
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
        android.util.Log.d(TAG, "Marked ${nullifiers.size} dust tokens as available")
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
        maxBlocks: Int = 100
    ): Boolean {
        android.util.Log.d(TAG, "Syncing dust from blockchain for $address")

        try {
            // Try delta sync first: load existing state + last event ID
            val existingState = loadState(address)
            val lastEventId = getLastAppliedEventId(address)

            if (existingState != null && lastEventId != null) {
                existingState.close()
                // Delta sync: stream only new events from checkpoint
                android.util.Log.d(TAG, "Delta sync: resuming from event $lastEventId")
                val result = streamDustEvents(address, dustSeed, fromId = lastEventId + 1)
                if (result) return true

                // Streaming returned false (no events or replay failed). Fall through to full sync.
                android.util.Log.w(TAG, "Delta sync returned no results, falling back to full sync")
                deleteState(address)
            } else {
                existingState?.close()
            }

            // Streaming sync: process events in chunks to keep memory flat.
            // PREPROD has 250k+ global dust events. Collecting into a List OOMs.
            // Instead: subscribe, buffer chunks of 500, replay into Rust state,
            // checkpoint to disk, discard hex. Matches wallet-cli pattern.
            android.util.Log.d(TAG, "Streaming sync from genesis")

            val result = streamDustEvents(address, dustSeed, fromId = null)
            if (!result) {
                android.util.Log.d(TAG, "No dust events found, dust not registered yet")
            }
            return result

        } catch (e: CancellationException) {
            throw e // Never swallow cancellation in suspend functions
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to sync dust from blockchain", e)
            return false
        }
    }

    /**
     * Stream dust events from the indexer in chunks, replaying each chunk into
     * the Rust DustLocalState immediately. Keeps memory flat regardless of event count.
     *
     * @param address Wallet address (for checkpoint persistence)
     * @param dustSeed 32-byte dust seed
     * @param fromId Start event ID (null = from genesis, non-null = delta)
     * @return true if at least one UTXO exists after sync
     */
    private suspend fun streamDustEvents(
        address: String,
        dustSeed: ByteArray,
        fromId: Long?,
    ): Boolean {
        var state = if (fromId != null) loadState(address) else null
        if (state == null) {
            state = DustLocalState.create()
                ?: run {
                    android.util.Log.e(TAG, "Failed to create DustLocalState")
                    return false
                }
        }

        val chunk = mutableListOf<String>()
        var totalEvents = 0
        var latestEventId = getLastAppliedEventId(address) ?: -1L

        try {
            // For delta (fromId != null): wait up to DELTA_FIRST_EVENT_TIMEOUT_MS
            // for the first event. If nothing arrives, we're truly caught up.
            // Once the first event arrives, stream with no timeout until caught up.
            // For full sync (fromId == null): no initial timeout, stream everything.
            val flow = indexerClient.subscribeToDustEvents(fromId = fromId)
            val isDelta = fromId != null

            if (isDelta) {
                // Wait for first event with timeout
                var firstEvent: com.midnight.kuira.core.indexer.model.RawLedgerEvent? = null
                kotlinx.coroutines.withTimeoutOrNull(DELTA_FIRST_EVENT_TIMEOUT_MS) {
                    flow.collect { event ->
                        firstEvent = event
                        throw StreamingCompleteSignal() // Got first event, break out
                    }
                }

                if (firstEvent == null) {
                    // No events in timeout window: truly caught up
                    state!!.close()
                    return fromId != null
                }

                // Process first event
                val event = firstEvent!!
                chunk.add(event.rawHex)
                latestEventId = event.id
                totalEvents++

                if (event.id >= event.maxId) {
                    // First event was also the last
                    state = flushChunk(state!!, dustSeed, chunk, address, latestEventId)
                    chunk.clear()
                } else {
                    // More events to come. Stream the rest with no timeout.
                    // Need a new subscription since we broke out of the first one.
                    val resumeFlow = indexerClient.subscribeToDustEvents(fromId = event.id + 1)
                    resumeFlow.collect { nextEvent ->
                        chunk.add(nextEvent.rawHex)
                        latestEventId = nextEvent.id
                        totalEvents++

                        if (chunk.size >= CHUNK_SIZE) {
                            state = flushChunk(state!!, dustSeed, chunk, address, latestEventId)
                            if (totalEvents % 5000 == 0) {
                                android.util.Log.d(TAG, "Streaming progress: $totalEvents events (id=$latestEventId/${nextEvent.maxId})")
                            }
                        }

                        if (nextEvent.id >= nextEvent.maxId) {
                            throw StreamingCompleteSignal()
                        }
                    }
                }
            } else {
                // Full sync: no timeout, stream everything
                flow.collect { event ->
                    chunk.add(event.rawHex)
                    latestEventId = event.id
                    totalEvents++

                    if (chunk.size >= CHUNK_SIZE) {
                        state = flushChunk(state!!, dustSeed, chunk, address, latestEventId)
                        if (totalEvents % 5000 == 0) {
                            android.util.Log.d(TAG, "Streaming progress: $totalEvents events (id=$latestEventId/${event.maxId})")
                        }
                    }

                    if (event.id >= event.maxId) {
                        throw StreamingCompleteSignal()
                    }
                }
            }
        } catch (_: StreamingCompleteSignal) {
            // Normal completion
        }

        // Flush remaining partial chunk
        if (chunk.isNotEmpty()) {
            state = flushChunk(state!!, dustSeed, chunk, address, latestEventId)
        }

        if (totalEvents == 0) {
            state!!.close()
            // Delta with 0 new events = already caught up (success).
            // Full sync with 0 events = dust not registered (failure).
            return fromId != null
        }

        // Final save + sync to database
        try {
            val utxoCount = state!!.getUtxoCount()
            syncTokensFromState(address, state!!)
            saveState(address, state!!)
            saveLastAppliedEventId(address, latestEventId)
            android.util.Log.d(TAG, "Streaming sync complete: $utxoCount UTXOs, $totalEvents events, last ID=$latestEventId")
            return utxoCount > 0
        } finally {
            state!!.close()
        }
    }

    /** Replay a chunk of events into the state, clear the chunk buffer, checkpoint. */
    private suspend fun flushChunk(
        currentState: DustLocalState,
        dustSeed: ByteArray,
        chunk: MutableList<String>,
        address: String,
        latestEventId: Long,
    ): DustLocalState {
        val chunkHex = chunk.joinToString("")
        val newState = currentState.replayEvents(dustSeed, chunkHex)
            ?: throw IllegalStateException("Dust replay failed at event $latestEventId")
        currentState.close()
        chunk.clear()

        // Checkpoint to disk so a crash doesn't lose progress
        try {
            saveState(address, newState)
            saveLastAppliedEventId(address, latestEventId)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Checkpoint save failed (best-effort): ${e.message}")
        }

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
            android.util.Log.d(TAG, "No dust state to sync for $address")
            return
        }

        // Deserialize DustLocalState
        val state = DustLocalState.deserialize(serialized) ?: run {
            android.util.Log.e(TAG, "Failed to deserialize dust state for $address")
            return
        }

        try {
            syncTokensFromState(address, state)
        } finally {
            // Always close state
            state.close()
        }

        android.util.Log.d(TAG, "Synced dust tokens for $address")
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
        android.util.Log.d(TAG, "Syncing $utxoCount UTXOs to database")

        if (utxoCount == 0) {
            android.util.Log.d(TAG, "No UTXOs to sync")
            return
        }

        try {
            // Create simplified token entities
            val tokens = mutableListOf<DustTokenEntity>()
            val currentTime = currentTimeMillis()

            for (i in 0 until utxoCount) {
                // Create placeholder token with minimal info
                // Use index as nullifier for now (will improve in Phase 2F)
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

            android.util.Log.d(TAG, "✅ Synced $utxoCount placeholder tokens to database")
            android.util.Log.d(TAG, "⚠️  Using simplified sync - actual values calculated from DustLocalState")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to sync tokens to database", e)
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
    private suspend fun getSerializedState(address: String): ByteArray? {
        val key = dustStateKey(address)
        val hexString = dustStateDataStore.data.first()[key] ?: return null
        return hexStringToBytes(hexString)
    }

    /**
     * Save serialized DustLocalState for an address.
     *
     * @param address Wallet address
     * @param serialized Serialized state bytes
     */
    private suspend fun saveSerializedState(address: String, serialized: ByteArray) {
        val key = dustStateKey(address)
        val hexString = bytesToHexString(serialized)
        dustStateDataStore.edit { prefs ->
            prefs[key] = hexString
        }
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
        val stateKey = dustStateKey(address)
        val eventKey = lastEventIdKey(address)
        dustStateDataStore.edit { prefs ->
            prefs.remove(stateKey)
            prefs.remove(eventKey)
        }
        dustDao.deleteTokensForAddress(address)
        android.util.Log.d(TAG, "Deleted dust state + event ID for $address")
    }

    /** Get last applied dust event ID for delta sync resume. Null if never synced. */
    suspend fun getLastAppliedEventId(address: String): Long? {
        val key = lastEventIdKey(address)
        return dustStateDataStore.data.first()[key]
    }

    /** Save last applied dust event ID (for delta sync resume). */
    suspend fun saveLastAppliedEventId(address: String, eventId: Long) {
        val key = lastEventIdKey(address)
        dustStateDataStore.edit { prefs ->
            prefs[key] = eventId
        }
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
    private fun hexStringToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
