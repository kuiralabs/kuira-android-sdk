package com.midnight.kuira.core.indexer.utxo

import android.util.Log
import androidx.room.Transaction
import com.midnight.kuira.core.indexer.database.UnshieldedUtxoDao
import com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity
import com.midnight.kuira.core.indexer.database.UtxoState
import com.midnight.kuira.core.indexer.model.TransactionStatus
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigInteger

/**
 * Manages UTXO state from transaction updates.
 *
 * Handles:
 * - Processing transaction updates (created/spent UTXOs)
 * - Three-state UTXO lifecycle (AVAILABLE → PENDING → SPENT)
 * - Transaction failure handling (unlock UTXOs)
 * - Calculating balances from available UTXOs only
 *
 * **Transaction Status Handling:**
 * - SUCCESS/PARTIAL_SUCCESS: Create UTXOs (AVAILABLE), mark spent as SPENT
 * - FAILURE: Don't create UTXOs, unlock spent UTXOs (PENDING → AVAILABLE)
 *
 * Thread-safe: All operations are suspend functions using Room's built-in thread safety.
 */
class UtxoManager(
    private val utxoDao: UnshieldedUtxoDao
) {
    /**
     * Process transaction update from subscription.
     *
     * Handles both transaction updates (with UTXO changes) and progress updates.
     *
     * For transaction updates (based on status):
     * - SUCCESS/PARTIAL_SUCCESS:
     *   1. Insert created UTXOs (state = AVAILABLE)
     *   2. Mark spent UTXOs as SPENT (permanent)
     * - FAILURE:
     *   1. Don't insert created UTXOs (never created on-chain)
     *   2. Unlock spent UTXOs (PENDING → AVAILABLE) so they can be reused
     *
     * For progress updates:
     * - Just return (nothing to do with UTXOs)
     *
     * @param update Update from subscription
     * @return ProcessingResult indicating what was done
     */
    suspend fun processUpdate(update: UnshieldedTransactionUpdate): ProcessingResult {
        return when (update) {
            is UnshieldedTransactionUpdate.Transaction -> {
                processTransaction(update)
            }
            is UnshieldedTransactionUpdate.Progress -> {
                ProcessingResult.ProgressUpdate(update.highestTransactionId)
            }
        }
    }

    private suspend fun processTransaction(
        update: UnshieldedTransactionUpdate.Transaction
    ): ProcessingResult {
        val createdCount = update.createdUtxos.size
        val spentCount = update.spentUtxos.size
        val status = update.status()
        val txHash = update.transaction.hash

        // Set transactionHash on CREATED UTXOs for local storage primary key.
        // For created UTXOs, txHash is THIS transaction's hash.
        val createdUtxosWithTxHash = update.createdUtxos.map { it.withTransactionHash(txHash) }

        // Log transaction summary (reduced verbosity)
        Log.d("UtxoManager", "Processing tx ${txHash.take(16)}... (id=${update.transaction.id}): +$createdCount -$spentCount ($status)")

        // Handle based on transaction status
        when (status) {
            TransactionStatus.SUCCESS, TransactionStatus.PARTIAL_SUCCESS -> {
                // STEP 1: Insert created UTXOs as AVAILABLE
                //
                // IMPORTANT: Do NOT restore SPENT UTXOs to AVAILABLE!
                // If we locally marked a UTXO as SPENT (after node accepted our transaction),
                // we should NOT restore it just because the indexer replayed the creating TX.
                // The indexer may be behind the blockchain - it hasn't seen our spending TX yet.
                //
                // Trust local SPENT state over stale indexer data. This prevents error 115
                // loops where we keep trying to spend already-spent UTXOs.
                //
                // The subscription delivers transactions in order, so for NEW UTXOs:
                // - TX 52 creates UTXO A → INSERT as AVAILABLE
                // - TX 53 spends A, creates B → We mark A as SPENT (step 2), INSERT B as AVAILABLE
                if (createdCount > 0) {
                    // Log RAW UTXO data from subscription for debugging
                    createdUtxosWithTxHash.forEach { utxo ->
                        Log.d("UtxoManager", "[SYNC] Created UTXO from subscription:")
                        Log.d("UtxoManager", "       intentHash=${utxo.intentHash} (len=${utxo.intentHash.length})")
                        Log.d("UtxoManager", "       outputIndex=${utxo.outputIndex}, value=${utxo.value}")
                        Log.d("UtxoManager", "       transactionHash=${utxo.transactionHash}")
                    }

                    // UTXO state handling during sync:
                    //
                    // - SPENT = Already spent (by indexer OR by our local tx) → KEEP
                    // - PENDING = Our transaction is in flight → KEEP
                    // - AVAILABLE = Normal state → UPDATE with latest data
                    // - Not in DB = New UTXO → INSERT as AVAILABLE
                    //
                    // We mark SPENT immediately when node accepts our transaction
                    // (with spentByLocalTx=true). This prevents issues when indexer
                    // lags behind the blockchain.
                    val entitiesToInsert = createdUtxosWithTxHash.mapNotNull { utxo ->
                        val entity = UnshieldedUtxoEntity.fromUtxo(utxo, state = UtxoState.AVAILABLE)
                        val existing = utxoDao.getUtxoById(entity.id)

                        when {
                            existing == null -> {
                                Log.d("UtxoManager", "INSERT: New UTXO ${entity.id} value=${entity.value}")
                                entity // Insert new UTXO
                            }
                            existing.state == UtxoState.SPENT -> {
                                // SPENT by indexer = confirmed spent by a later transaction
                                // A later tx in the sync will mark this as spent, which is correct
                                Log.d("UtxoManager", "SKIP: UTXO ${entity.id} already SPENT (confirmed by indexer)")
                                null // Keep SPENT - it was legitimately spent
                            }
                            existing.state == UtxoState.PENDING -> {
                                // PENDING = our transaction is in flight
                                // Don't overwrite - our tx might still confirm
                                Log.d("UtxoManager", "SKIP: UTXO ${entity.id} is PENDING (tx in flight)")
                                null // Keep PENDING - wait for our tx to confirm or fail
                            }
                            else -> {
                                // AVAILABLE - update with latest data from indexer
                                Log.d("UtxoManager", "UPDATE: UTXO ${entity.id} state=${existing.state}")
                                entity
                            }
                        }
                    }

                    // Insert/update UTXOs
                    if (entitiesToInsert.isNotEmpty()) {
                        utxoDao.insertUtxos(entitiesToInsert)
                    }
                }

                // STEP 2: Mark spent UTXOs as SPENT (permanent)
                // This runs AFTER inserting created UTXOs, so order is correct:
                // If a UTXO was both created and spent in different TXs, we first insert it
                // as AVAILABLE (from the creating TX), then mark it SPENT (from the spending TX).
                // For spent UTXOs, we need to find them by intentHash (what the subscription returns)
                // then mark them by their id (which is transactionHash:outputIndex)
                if (spentCount > 0) {
                    val utxoIds = update.spentUtxos.mapNotNull { spentUtxo ->
                        utxoDao.getUtxoByIntentHash(spentUtxo.intentHash, spentUtxo.outputIndex)?.id
                    }
                    if (utxoIds.isNotEmpty()) {
                        Log.d("UtxoManager", "SPENT: Marking ${utxoIds.size} UTXOs as SPENT: ${utxoIds.take(3)}...")
                        utxoDao.markAsSpent(utxoIds)
                    }
                }
            }

            TransactionStatus.FAILURE -> {
                // Don't create new UTXOs for failed transactions
                // (they were never actually created on-chain)

                // If there were spent UTXOs, unlock them (PENDING → AVAILABLE)
                // This allows them to be used in future transactions
                if (spentCount > 0) {
                    val utxoIds = update.spentUtxos.mapNotNull { spentUtxo ->
                        utxoDao.getUtxoByIntentHash(spentUtxo.intentHash, spentUtxo.outputIndex)?.id
                    }
                    if (utxoIds.isNotEmpty()) {
                        utxoDao.markAsAvailable(utxoIds)
                    }
                }
            }
        }

        return ProcessingResult.TransactionProcessed(
            transactionId = update.transaction.id,
            transactionHash = update.transaction.hash,
            createdCount = createdCount,
            spentCount = spentCount,
            status = status
        )
    }

    /**
     * Calculate balance for an address.
     *
     * Sums all AVAILABLE UTXOs grouped by token type.
     * Excludes PENDING (locked) and SPENT UTXOs.
     *
     * @param address Unshielded address
     * @return Map of tokenType → balance (as BigInteger)
     */
    suspend fun calculateBalance(address: String): Map<String, BigInteger> {
        val unspentUtxos = utxoDao.getUnspentUtxos(address)

        return unspentUtxos
            .groupBy { it.tokenType }
            .mapValues { (_, utxos) ->
                utxos.fold(BigInteger.ZERO) { acc, utxo ->
                    acc + BigInteger(utxo.value)
                }
            }
    }

    /**
     * Observe balance changes for an address (available UTXOs only).
     *
     * Returns Flow that emits new balance map whenever AVAILABLE UTXOs change.
     * Only includes AVAILABLE UTXOs, excludes PENDING and SPENT.
     *
     * Matches Midnight SDK: `getAvailableBalances()`
     *
     * @param address Unshielded address
     * @return Flow of balance maps (tokenType → balance)
     */
    fun observeBalance(address: String): Flow<Map<String, BigInteger>> {
        return utxoDao.observeUnspentUtxos(address).map { utxos ->
            utxos
                .groupBy { it.tokenType }
                .mapValues { (_, utxoList) ->
                    utxoList.fold(BigInteger.ZERO) { acc, utxo ->
                        acc + BigInteger(utxo.value)
                    }
                }
        }
    }

    /**
     * Observe pending balance for an address (locked in pending transactions).
     *
     * Returns Flow that emits balance map for UTXOs in PENDING state.
     * These are UTXOs locked for pending transactions.
     *
     * Matches Midnight SDK: `getPendingBalances()`
     *
     * @param address Unshielded address
     * @return Flow of pending balance maps (tokenType → balance)
     */
    fun observePendingBalance(address: String): Flow<Map<String, BigInteger>> {
        return utxoDao.observePendingUtxos(address).map { utxos ->
            utxos
                .groupBy { it.tokenType }
                .mapValues { (_, utxoList) ->
                    utxoList.fold(BigInteger.ZERO) { acc, utxo ->
                        acc + BigInteger(utxo.value)
                    }
                }
        }
    }

    /**
     * Observe UTXO counts per token type (available UTXOs only).
     *
     * Returns Flow that emits UTXO counts grouped by token type.
     * Used for displaying "X UTXOs" in balance UI.
     *
     * Matches Midnight SDK: `.length` on `getAvailableCoins()` result
     *
     * @param address Unshielded address
     * @return Flow of UTXO count maps (tokenType → count)
     */
    fun observeUtxoCounts(address: String): Flow<Map<String, Int>> {
        return utxoDao.observeUnspentUtxos(address).map { utxos ->
            utxos
                .groupBy { it.tokenType }
                .mapValues { (_, utxoList) -> utxoList.size }
        }
    }

    /**
     * Get detailed UTXO list for an address.
     *
     * Returns only AVAILABLE UTXOs (not PENDING or SPENT).
     * Useful for debugging or transaction building.
     *
     * @param address Unshielded address
     * @return List of available UTXOs
     */
    suspend fun getUnspentUtxos(address: String): List<UnshieldedUtxoEntity> {
        return utxoDao.getUnspentUtxos(address)
    }

    /**
     * Delete all UTXOs for an address.
     *
     * Used when handling deep reorgs or wallet reset.
     */
    suspend fun clearUtxos(address: String) {
        utxoDao.deleteUtxosForAddress(address)
    }

    /**
     * Check if database has ANY UTXOs for this address (any state).
     *
     * Used to determine if database needs full resync.
     * Returns true if there are UTXOs (even if all SPENT).
     */
    suspend fun hasAnyUtxos(address: String): Boolean {
        return utxoDao.countAllForAddress(address) > 0
    }

    /**
     * Check if database has AVAILABLE UTXOs for this address.
     *
     * Used to detect suspicious state where all UTXOs are SPENT.
     * If hasAnyUtxos is true but hasAvailableUtxos is false, that means
     * all UTXOs are SPENT/PENDING which is suspicious - likely corrupted state.
     */
    suspend fun hasAvailableUtxos(address: String): Boolean {
        return utxoDao.countUnspent(address) > 0
    }

    /**
     * Debug: Dump all UTXOs for an address to logs.
     */
    suspend fun debugDumpAllUtxos(address: String, tag: String) {
        val allUtxos = utxoDao.getAllUtxosForAddress(address)
        Log.d("UtxoManager", "[$tag] All UTXOs for address (${allUtxos.size} total):")
        allUtxos.forEach { utxo ->
            Log.d("UtxoManager", "  [${utxo.state}] id=${utxo.id}, intentHash=${utxo.intentHash}:${utxo.outputIndex}, value=${utxo.value}")
        }
    }

    /**
     * Reset ALL SPENT UTXOs to AVAILABLE for self-healing.
     *
     * **Purpose:** Self-heal corrupted UTXO state during full resync.
     *
     * **Why reset ALL?** During a full resync, we replay ALL transactions
     * from the beginning. The sync will correctly re-mark spent UTXOs
     * as SPENT based on actual blockchain history. So it's safe to reset
     * everything and let the sync rebuild the correct state.
     *
     * @param address Owner address
     * @return Number of UTXOs reset from SPENT to AVAILABLE
     */
    suspend fun resetSpentUtxosForHealing(address: String): Int {
        val resetCount = utxoDao.resetSpentToAvailable(address)
        if (resetCount > 0) {
            Log.i("UtxoManager", "🔄 HEALING: Reset ALL $resetCount SPENT UTXOs to AVAILABLE for full resync")
        }
        return resetCount
    }

    // ========== Coin Selection & Atomic Locking ==========

    /**
     * Select and lock UTXOs for transaction (atomic operation).
     *
     * **Critical: Prevents Double-Spend Race Condition**
     *
     * This method performs selection and locking in a SINGLE database transaction:
     * 1. SELECT available UTXOs (sorted by value, smallest first)
     * 2. Perform coin selection (smallest-first algorithm)
     * 3. UPDATE selected UTXOs to PENDING state
     *
     * **Atomicity:** Room's @Transaction ensures this is a single SQLite transaction.
     * No other thread can select the same UTXOs between steps 1-3.
     *
     * **Why Atomic?**
     * ```
     * // ❌ WITHOUT @Transaction (RACE CONDITION):
     * Thread A: SELECT utxos WHERE state = AVAILABLE  → [utxo1, utxo2]
     * Thread B: SELECT utxos WHERE state = AVAILABLE  → [utxo1, utxo2]  // SAME UTXOs!
     * Thread A: UPDATE utxos SET state = PENDING
     * Thread B: UPDATE utxos SET state = PENDING
     * Result: DOUBLE-SPEND! Both threads use same UTXOs
     *
     * // ✅ WITH @Transaction (SAFE):
     * Thread A: [SELECT + UPDATE in one transaction]  → LOCKS [utxo1, utxo2]
     * Thread B: [waits for Thread A's transaction to complete]
     * Thread B: SELECT utxos WHERE state = AVAILABLE  → [utxo3, utxo4]  // Different UTXOs!
     * Result: SAFE! Each thread gets different UTXOs
     * ```
     *
     * Uses the smallest-first coin selection and state management strategy
     * standard to the Midnight wallet model.
     *
     * **Usage in Transaction Builder:**
     * ```kotlin
     * // Lock UTXOs for transaction
     * val result = utxoManager.selectAndLockUtxos(
     *     address = senderAddress,
     *     tokenType = "NIGHT",
     *     requiredAmount = BigInteger("100000000")
     * )
     *
     * when (result) {
     *     is SelectionResult.Success -> {
     *         // Build transaction with result.selectedUtxos
     *         // Create change output with result.change (if > 0)
     *     }
     *     is SelectionResult.InsufficientFunds -> {
     *         // Show error to user
     *     }
     * }
     *
     * // If transaction fails, unlock UTXOs:
     * utxoManager.unlockUtxos(result.selectedUtxos.map { it.id })
     * ```
     *
     * @param address Owner address
     * @param tokenType Token type to select
     * @param requiredAmount Amount needed (in smallest units)
     * @return SelectionResult (Success with locked UTXOs, or InsufficientFunds)
     */
    @Transaction
    suspend fun selectAndLockUtxos(
        address: String,
        tokenType: String,
        requiredAmount: BigInteger
    ): UtxoSelector.SelectionResult {
        // Step 1: SELECT available UTXOs (sorted by value, smallest first)
        val availableUtxos = utxoDao.getUnspentUtxosForTokenSorted(address, tokenType)
        Log.d("UtxoManager", "selectAndLockUtxos: ${availableUtxos.size} AVAILABLE UTXOs, total=${availableUtxos.sumOf { it.value.toBigInteger() }}")
        availableUtxos.forEach { utxo ->
            Log.d("UtxoManager", "  AVAILABLE: id=${utxo.id}, intentHash=${utxo.intentHash}:${utxo.outputIndex}, value=${utxo.value}")
        }

        // Step 2: Perform coin selection (smallest-first)
        val selector = UtxoSelector()
        val selectionResult = selector.selectUtxos(availableUtxos, requiredAmount)

        // Step 3: If successful, UPDATE selected UTXOs to PENDING
        if (selectionResult is UtxoSelector.SelectionResult.Success) {
            val utxoIds = selectionResult.selectedUtxos.map { it.id }
            Log.d("UtxoManager", "Selected ${utxoIds.size} UTXOs for spend")
            utxoDao.markAsPending(utxoIds)
        }

        // All three steps completed atomically (no other thread can interfere)
        return selectionResult
    }

    /**
     * Select and lock UTXOs for multiple token types (atomic operation).
     *
     * Performs coin selection for multiple tokens in a single transaction.
     * If ANY token has insufficient funds, NO UTXOs are locked (all-or-nothing).
     *
     * **Atomicity:** Room's @Transaction ensures all-or-nothing behavior:
     * - If all tokens succeed → Lock ALL selected UTXOs
     * - If any token fails → Lock NONE (rollback)
     *
     * **Usage:**
     * ```kotlin
     * val requirements = mapOf(
     *     "NIGHT" to BigInteger("100000000"),
     *     "DUST" to BigInteger("50000000")
     * )
     *
     * val result = utxoManager.selectAndLockUtxosMultiToken(address, requirements)
     * ```
     *
     * @param address Owner address
     * @param requiredAmounts Map of tokenType → required amount
     * @return MultiTokenResult (Success with all selections, or PartialFailure)
     */
    @Transaction
    suspend fun selectAndLockUtxosMultiToken(
        address: String,
        requiredAmounts: Map<String, BigInteger>
    ): UtxoSelector.MultiTokenResult {
        // Collect all available UTXOs for this address
        val availableUtxos = utxoDao.getUnspentUtxos(address)

        // Perform multi-token selection
        val selector = UtxoSelector()
        val result = selector.selectUtxosMultiToken(availableUtxos, requiredAmounts)

        // If all successful, lock all selected UTXOs
        if (result is UtxoSelector.MultiTokenResult.Success) {
            val allUtxoIds = result.allSelectedUtxos().map { it.id }
            utxoDao.markAsPending(allUtxoIds)
        }

        // If any failed, transaction is rolled back (no UTXOs locked)
        return result
    }

    /**
     * Unlock UTXOs (mark as AVAILABLE).
     *
     * Used when transaction fails or is cancelled.
     * Releases locked UTXOs so they can be used in future transactions.
     *
     * **State Transition:** PENDING → AVAILABLE
     *
     * @param utxoIds List of UTXO IDs to unlock
     */
    suspend fun unlockUtxos(utxoIds: List<String>) {
        if (utxoIds.isNotEmpty()) {
            utxoDao.markAsAvailable(utxoIds)
        }
    }

    /**
     * Mark UTXOs as spent by their database IDs.
     *
     * **WARNING:** This method takes database IDs (transactionHash:outputIndex format).
     * For marking spent UTXOs from transaction inputs, use [markUtxosAsSpentByIntent] instead,
     * which takes (intentHash, outputNo) pairs.
     *
     * Used when transaction is successfully confirmed on-chain.
     * Permanently marks UTXOs as spent.
     *
     * **State Transition:** PENDING → SPENT
     *
     * @param utxoIds List of database UTXO IDs (transactionHash:outputIndex format)
     */
    suspend fun markUtxosAsSpent(utxoIds: List<String>) {
        if (utxoIds.isNotEmpty()) {
            utxoDao.markAsSpent(utxoIds)
        }
    }

    /**
     * Mark UTXOs as spent by their intent identifiers (intentHash + outputNo).
     *
     * **IMPORTANT:** The blockchain identifies UTXOs by intentHash + outputNo,
     * but our database uses transactionHash:outputIndex as the primary key.
     * This method looks up UTXOs by intentHash and then marks them by their database ID.
     *
     * **spentByLocalTx flag:**
     * - `true` (default): Our transaction SUCCEEDED and spent this UTXO.
     *   Healing will NOT restore it (we trust our local state over indexer lag).
     * - `false`: Something else spent it (e.g., error 115 = node says already spent).
     *   Healing CAN restore it if indexer shows it's actually available.
     *
     * **When to use each:**
     * - Node accepts our transaction → `spentByLocalTx=true`
     * - Error 115 (UTXO already spent) → `spentByLocalTx=false` (we didn't spend it!)
     *
     * @param utxoIntentIds List of (intentHash, outputNo) pairs identifying UTXOs
     * @param spentByLocalTx Whether OUR transaction spent these UTXOs (default: true)
     * @return Number of UTXOs successfully marked as spent
     */
    suspend fun markUtxosAsSpentByIntent(
        utxoIntentIds: List<Pair<String, Int>>,
        spentByLocalTx: Boolean = true
    ): Int {
        if (utxoIntentIds.isEmpty()) return 0

        // Look up each UTXO by intentHash+outputNo to get its database ID
        val databaseIds = utxoIntentIds.mapNotNull { (intentHash, outputNo) ->
            val utxo = utxoDao.getUtxoByIntentHash(intentHash, outputNo)
            if (utxo != null) {
                Log.d("UtxoManager", "Found UTXO for intentHash=$intentHash:$outputNo -> id=${utxo.id}, currentState=${utxo.state}")
                utxo.id
            } else {
                Log.w("UtxoManager", "No UTXO found for intentHash=$intentHash:$outputNo")
                null
            }
        }

        if (databaseIds.isNotEmpty()) {
            if (spentByLocalTx) {
                // Our transaction spent these - don't let healing restore them
                utxoDao.markAsSpentByLocalTx(databaseIds)
                Log.d("UtxoManager", "Marked ${databaseIds.size}/${utxoIntentIds.size} UTXOs as SPENT (local tx): $databaseIds")
            } else {
                // External spend (e.g., error 115) - healing CAN restore if available
                utxoDao.markAsSpent(databaseIds)
                Log.d("UtxoManager", "Marked ${databaseIds.size}/${utxoIntentIds.size} UTXOs as SPENT (external): $databaseIds")
            }
        } else {
            Log.w("UtxoManager", "No UTXOs found for ${utxoIntentIds.size} intent IDs")
        }

        return databaseIds.size
    }

    /**
     * Result of processing a transaction update.
     */
    sealed class ProcessingResult {
        data class TransactionProcessed(
            val transactionId: Int,
            val transactionHash: String,
            val createdCount: Int,
            val spentCount: Int,
            val status: com.midnight.kuira.core.indexer.model.TransactionStatus
        ) : ProcessingResult()

        data class ProgressUpdate(
            val highestTransactionId: Int
        ) : ProcessingResult()
    }
}
