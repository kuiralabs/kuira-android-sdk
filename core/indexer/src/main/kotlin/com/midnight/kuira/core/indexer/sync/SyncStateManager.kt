package com.midnight.kuira.core.indexer.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Manages subscription sync state for resuming after app restarts.
 *
 * **Purpose:**
 * - Persist last processed transaction ID per address
 * - Enable subscription resumption (don't replay all history on restart)
 * - Track sync progress
 *
 * **Storage:**
 * - Uses DataStore (shared preferences alternative)
 * - Key format: "last_tx_id_{address}"
 * - Value: Last processed transaction ID (Int)
 *
 * **Resume Flow:**
 * 1. App starts
 * 2. Get last processed ID: `getLastProcessedTransactionId(address)`
 * 3. Subscribe from that ID: `subscribeToUnshieldedTransactions(address, lastId)`
 * 4. Process updates
 * 5. Save progress: `saveLastProcessedTransactionId(address, newId)`
 *
 * **Example:**
 * ```kotlin
 * // On app start
 * val lastId = syncStateManager.getLastProcessedTransactionId(address)
 * // lastId = 42 (from previous session)
 *
 * // Subscribe from last ID (skip already processed txs)
 * indexerClient.subscribeToUnshieldedTransactions(address, fromTransactionId = lastId)
 *     .collect { update ->
 *         utxoManager.processUpdate(update)
 *
 *         if (update is Progress) {
 *             syncStateManager.saveLastProcessedTransactionId(address, update.highestTransactionId)
 *         }
 *     }
 * ```
 */
class SyncStateManager(private val context: Context) {

    companion object {
        // Singleton DataStore to prevent multiple instances error
        private val Context.syncStateDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "sync_state"
        )
    }

    private val dataStore: DataStore<Preferences> = context.syncStateDataStore

    /**
     * Get last processed transaction ID for an address.
     *
     * Returns null if no previous sync (first time syncing).
     *
     * @param address Unshielded address
     * @return Last processed transaction ID, or null if never synced
     */
    suspend fun getLastProcessedTransactionId(address: String): Int? {
        val key = lastTxIdKey(address)
        val preferences = dataStore.data.first()
        return preferences[key]
    }

    /**
     * Save last processed transaction ID for an address.
     *
     * Call this after processing Progress updates to track sync state.
     *
     * @param address Unshielded address
     * @param transactionId Last processed transaction ID
     */
    suspend fun saveLastProcessedTransactionId(address: String, transactionId: Int) {
        val key = lastTxIdKey(address)
        dataStore.edit { preferences ->
            preferences[key] = transactionId
        }
    }

    /**
     * Observe last processed transaction ID for an address.
     *
     * Returns Flow that emits whenever sync progress is saved.
     * Useful for displaying sync status in UI.
     *
     * @param address Unshielded address
     * @return Flow of last processed transaction ID (null if never synced)
     */
    fun observeLastProcessedTransactionId(address: String): Flow<Int?> {
        val key = lastTxIdKey(address)
        return dataStore.data.map { preferences ->
            preferences[key]
        }
    }

    /**
     * Clear sync state for an address.
     *
     * Used when handling deep reorgs or wallet reset.
     * Next sync will start from beginning.
     *
     * @param address Unshielded address
     */
    suspend fun clearSyncState(address: String) {
        val key = lastTxIdKey(address)
        dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    /**
     * Clear all sync state (for all addresses).
     *
     * Used for full wallet reset.
     *
     * **⚠️ WARNING:** This clears the ENTIRE "sync_state" DataStore.
     * Never store non-sync-related data in this DataStore, as it will be deleted by this method.
     * The DataStore is dedicated to sync progress tracking only.
     */
    suspend fun clearAllSyncState() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * The GENESIS block hash the persisted checkpoints were built against — a per-chain identity
     * for detecting a chain RESET (a fresh localnet has a different genesis hash). Lives in the
     * same "sync_state" DataStore as the tx cursor but is NOT touched by [clearSyncState] (which
     * clears only the cursor), so [com.midnight.kuira.core.indexer.sync.ChainResetGuard] can re-pin
     * it to the new chain after wiping the stale caches. [clearAllSyncState] does clear it.
     *
     * @return the pinned genesis hash, or null if this address has never been pinned.
     */
    suspend fun getPinnedGenesisHash(address: String): String? {
        return dataStore.data.first()[genesisHashKey(address)]
    }

    /** Pin [genesisHash] as the chain identity the checkpoints for [address] were built against. */
    suspend fun setPinnedGenesisHash(address: String, genesisHash: String) {
        dataStore.edit { preferences ->
            preferences[genesisHashKey(address)] = genesisHash
        }
    }

    private fun lastTxIdKey(address: String): Preferences.Key<Int> {
        return intPreferencesKey("last_tx_id_$address")
    }

    private fun genesisHashKey(address: String): Preferences.Key<String> {
        return stringPreferencesKey("genesis_hash_$address")
    }
}
