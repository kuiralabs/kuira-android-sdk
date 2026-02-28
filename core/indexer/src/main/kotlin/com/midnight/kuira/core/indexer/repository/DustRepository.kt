package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.database.DustDao
import com.midnight.kuira.core.indexer.database.DustTokenEntity
import com.midnight.kuira.core.indexer.di.DustStateDataStore
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
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

        // Key format: "dust_state_{address}"
        private fun dustStateKey(address: String) = stringPreferencesKey("dust_state_$address")

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
            // Step 1: Query dust events from indexer
            android.util.Log.d(TAG, "Querying dust events (scanning last $maxBlocks blocks)...")
            val eventsHex = indexerClient.queryDustEvents(maxBlocks)

            if (eventsHex.isEmpty()) {
                android.util.Log.d(TAG, "No dust events found - dust not registered yet")
                return false
            }

            android.util.Log.d(TAG, "Retrieved ${eventsHex.length / 2} bytes of dust events")

            // Step 2: Create fresh DustLocalState
            // Always start fresh because queryDustEvents() returns ALL events from genesis.
            // The Merkle tree requires sequential insertion — replaying from 0 into a state
            // that already has events causes NonLinearInsertion errors.
            // Incremental sync (reusing saved state) is planned for Phase 2.
            android.util.Log.d(TAG, "Creating fresh dust state for full replay")
            val initialState = DustLocalState.create()

            if (initialState == null) {
                android.util.Log.e(TAG, "Failed to create/load DustLocalState")
                return false
            }

            try {
                // Step 3: Replay events into state
                android.util.Log.d(TAG, "Replaying dust events into state...")
                val restoredState = initialState.replayEvents(dustSeed, eventsHex)

                if (restoredState == null) {
                    android.util.Log.e(TAG, "Failed to replay dust events")
                    return false
                }

                try {
                    val utxoCount = restoredState.getUtxoCount()
                    android.util.Log.d(TAG, "Replay complete: $utxoCount UTXOs in state")

                    // Step 4: Sync tokens from state to database
                    syncTokensFromState(address, restoredState)

                    // Step 5: Save updated state
                    saveState(address, restoredState)

                    android.util.Log.d(TAG, "✅ Dust sync complete for $address ($utxoCount UTXOs)")
                    return utxoCount > 0

                } finally {
                    restoredState.close()
                }

            } finally {
                initialState.close()
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to sync dust from blockchain", e)
            return false
        }
    }

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
        val serialized = state.serialize() ?: return
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
        val key = dustStateKey(address)
        dustStateDataStore.edit { prefs ->
            prefs.remove(key)
        }
        dustDao.deleteTokensForAddress(address)
        android.util.Log.d(TAG, "Deleted dust state for $address")
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
