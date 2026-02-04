package com.midnight.kuira.core.indexer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for unshielded UTXO operations.
 *
 * Provides methods to:
 * - Insert UTXOs (created by transactions)
 * - Update UTXO state (AVAILABLE → PENDING → SPENT)
 * - Query available UTXOs (for balance calculation)
 * - Handle transaction failures (PENDING → AVAILABLE)
 */
@Dao
interface UnshieldedUtxoDao {
    /**
     * Insert UTXOs into database.
     *
     * Uses REPLACE strategy so re-inserting same UTXO updates it.
     * This is useful when handling chain reorgs.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUtxos(utxos: List<UnshieldedUtxoEntity>)

    /**
     * Mark UTXO as spent (from sync - not local transaction).
     *
     * State transition: PENDING → SPENT (on transaction SUCCESS).
     * Sets spentByLocalTx=false since this came from sync.
     */
    @Query("UPDATE unshielded_utxos SET state = 'SPENT', spent_by_local_tx = 0 WHERE id = :utxoId")
    suspend fun markAsSpent(utxoId: String)

    /**
     * Mark multiple UTXOs as spent (from sync - not local transaction).
     *
     * More efficient than calling markAsSpent multiple times.
     * State transition: PENDING → SPENT (on transaction SUCCESS).
     * Sets spentByLocalTx=false since this came from sync.
     */
    @Query("UPDATE unshielded_utxos SET state = 'SPENT', spent_by_local_tx = 0 WHERE id IN (:utxoIds)")
    suspend fun markAsSpent(utxoIds: List<String>)

    /**
     * Mark UTXO as spent by LOCAL transaction.
     *
     * Sets spentByLocalTx=true to prevent restoration during sync.
     * Used when OUR transaction is accepted by the node (might be ahead of indexer).
     */
    @Query("UPDATE unshielded_utxos SET state = 'SPENT', spent_by_local_tx = 1 WHERE id = :utxoId")
    suspend fun markAsSpentByLocalTx(utxoId: String)

    /**
     * Mark multiple UTXOs as spent by LOCAL transaction.
     *
     * Sets spentByLocalTx=true to prevent restoration during sync.
     * Used when OUR transaction is accepted by the node (might be ahead of indexer).
     */
    @Query("UPDATE unshielded_utxos SET state = 'SPENT', spent_by_local_tx = 1 WHERE id IN (:utxoIds)")
    suspend fun markAsSpentByLocalTx(utxoIds: List<String>)

    /**
     * Mark UTXO as pending (locked for transaction).
     *
     * State transition: AVAILABLE → PENDING (when creating transaction).
     * Prevents double-spending by locking UTXO during transaction.
     */
    @Query("UPDATE unshielded_utxos SET state = 'PENDING' WHERE id = :utxoId")
    suspend fun markAsPending(utxoId: String)

    /**
     * Mark multiple UTXOs as pending.
     */
    @Query("UPDATE unshielded_utxos SET state = 'PENDING' WHERE id IN (:utxoIds)")
    suspend fun markAsPending(utxoIds: List<String>)

    /**
     * Mark UTXO as available (unlock).
     *
     * State transition: PENDING → AVAILABLE (on transaction FAILURE).
     * Unlocks UTXO so it can be used in future transactions.
     */
    @Query("UPDATE unshielded_utxos SET state = 'AVAILABLE' WHERE id = :utxoId")
    suspend fun markAsAvailable(utxoId: String)

    /**
     * Mark multiple UTXOs as available.
     */
    @Query("UPDATE unshielded_utxos SET state = 'AVAILABLE' WHERE id IN (:utxoIds)")
    suspend fun markAsAvailable(utxoIds: List<String>)

    /**
     * Get all available UTXOs for an address.
     *
     * Used for balance calculation. Only returns AVAILABLE UTXOs.
     * Excludes PENDING (locked) and SPENT UTXOs.
     */
    @Query("SELECT * FROM unshielded_utxos WHERE owner = :address AND state = 'AVAILABLE'")
    suspend fun getUnspentUtxos(address: String): List<UnshieldedUtxoEntity>

    /**
     * Get all available UTXOs for an address, grouped by token type.
     *
     * Returns Flow for reactive updates. Only returns AVAILABLE UTXOs.
     * Excludes PENDING (locked) and SPENT UTXOs.
     */
    @Query("SELECT * FROM unshielded_utxos WHERE owner = :address AND state = 'AVAILABLE' ORDER BY token_type, ctime")
    fun observeUnspentUtxos(address: String): Flow<List<UnshieldedUtxoEntity>>

    /**
     * Get all pending UTXOs for an address (locked in pending transactions).
     *
     * Returns Flow for reactive updates. Only returns PENDING UTXOs.
     * Excludes AVAILABLE and SPENT UTXOs.
     *
     * Matches Midnight SDK: Observable for pending UTXOs
     */
    @Query("SELECT * FROM unshielded_utxos WHERE owner = :address AND state = 'PENDING' ORDER BY token_type, ctime")
    fun observePendingUtxos(address: String): Flow<List<UnshieldedUtxoEntity>>

    /**
     * Get available UTXOs for a specific token type.
     *
     * Useful for displaying single token balance. Only returns AVAILABLE UTXOs.
     * Excludes PENDING (locked) and SPENT UTXOs.
     */
    @Query("SELECT * FROM unshielded_utxos WHERE owner = :address AND token_type = :tokenType AND state = 'AVAILABLE'")
    suspend fun getUnspentUtxosForToken(address: String, tokenType: String): List<UnshieldedUtxoEntity>

    /**
     * Get UTXO by ID (transactionHash:outputIndex).
     *
     * Used to check if UTXO already exists before inserting.
     */
    @Query("SELECT * FROM unshielded_utxos WHERE id = :utxoId")
    suspend fun getUtxoById(utxoId: String): UnshieldedUtxoEntity?

    /**
     * Get UTXO by intentHash and outputIndex.
     *
     * Used when processing spentUtxos from the subscription, which returns
     * intentHash instead of transactionHash. We need to find the UTXO's id
     * (which is transactionHash:outputIndex) to mark it as spent.
     */
    @Query("SELECT * FROM unshielded_utxos WHERE intent_hash = :intentHash AND output_index = :outputIndex")
    suspend fun getUtxoByIntentHash(intentHash: String, outputIndex: Int): UnshieldedUtxoEntity?

    /**
     * Delete all UTXOs for an address.
     *
     * Used when handling deep chain reorgs or wallet reset.
     */
    @Query("DELETE FROM unshielded_utxos WHERE owner = :address")
    suspend fun deleteUtxosForAddress(address: String)

    /**
     * Delete all UTXOs.
     *
     * Used for testing or complete wallet reset.
     */
    @Query("DELETE FROM unshielded_utxos")
    suspend fun deleteAll()

    /**
     * Count total UTXOs (for debugging).
     */
    @Query("SELECT COUNT(*) FROM unshielded_utxos")
    suspend fun count(): Int

    /**
     * Count available UTXOs for an address (for debugging).
     *
     * Only counts AVAILABLE UTXOs (not PENDING or SPENT).
     */
    @Query("SELECT COUNT(*) FROM unshielded_utxos WHERE owner = :address AND state = 'AVAILABLE'")
    suspend fun countUnspent(address: String): Int

    /**
     * Count ALL UTXOs for an address (any state).
     *
     * Used to check if database has any data for this address.
     * Includes AVAILABLE, PENDING, and SPENT UTXOs.
     */
    @Query("SELECT COUNT(*) FROM unshielded_utxos WHERE owner = :address")
    suspend fun countAllForAddress(address: String): Int

    /**
     * Get ALL UTXOs for an address (any state).
     *
     * Used for debugging to see full database state.
     */
    @Query("SELECT * FROM unshielded_utxos WHERE owner = :address ORDER BY state, id")
    suspend fun getAllUtxosForAddress(address: String): List<UnshieldedUtxoEntity>

    // ========== Phase 2B: Coin Selection Methods ==========

    /**
     * Get available UTXOs for a specific token, sorted by value (smallest first).
     *
     * Used for smallest-first coin selection algorithm.
     * Returns only AVAILABLE UTXOs, excludes PENDING and SPENT.
     *
     * **Sorting:** Ascending by value (smallest first) for privacy optimization.
     * Source: midnight-wallet Balancer.ts:143 (chooseCoin function)
     *
     * @param address Owner address
     * @param tokenType Token type identifier
     * @return List of available UTXOs sorted by value (smallest first)
     */
    @Query("""
        SELECT * FROM unshielded_utxos
        WHERE owner = :address
        AND token_type = :tokenType
        AND state = 'AVAILABLE'
        ORDER BY CAST(value AS INTEGER) ASC
    """)
    suspend fun getUnspentUtxosForTokenSorted(
        address: String,
        tokenType: String
    ): List<UnshieldedUtxoEntity>

    // ========== Self-Healing Methods ==========

    /**
     * Reset ALL SPENT UTXOs to AVAILABLE for healing during full resync.
     *
     * **Purpose:** Self-healing corrupted UTXO state.
     *
     * **Why reset ALL (including spentByLocalTx=true)?**
     * During a FULL resync, we replay ALL transactions from the beginning.
     * The sync will correctly re-mark spent UTXOs as SPENT based on actual
     * blockchain history. So it's safe to reset everything.
     *
     * This fixes issues where UTXOs were incorrectly marked with
     * spentByLocalTx=true (e.g., old error 115 handling bug).
     *
     * **Flow:**
     * 1. Reset ALL SPENT → AVAILABLE
     * 2. Full sync replays all transactions
     * 3. Spending transactions re-mark UTXOs as SPENT (correct)
     * 4. Unspent UTXOs stay AVAILABLE (healed!)
     *
     * @param address Owner address
     * @return Number of UTXOs reset
     */
    @Query("UPDATE unshielded_utxos SET state = 'AVAILABLE', spent_by_local_tx = 0 WHERE owner = :address AND state = 'SPENT'")
    suspend fun resetSpentToAvailable(address: String): Int
}
