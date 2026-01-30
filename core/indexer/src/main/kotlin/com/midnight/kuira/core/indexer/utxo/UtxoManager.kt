package com.midnight.kuira.core.indexer.utxo

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

        // Log what we received for debugging
        android.util.Log.d("UtxoManager", "═══ Processing tx ${txHash.take(16)}... (id=${update.transaction.id}) ═══")
        android.util.Log.d("UtxoManager", "Status: $status, created=${createdCount}, spent=${spentCount}")
        createdUtxosWithTxHash.forEach { utxo ->
            android.util.Log.d("UtxoManager", "  CREATED: ${utxo.identifier()} = ${utxo.value} (intentHash=${utxo.intentHash.take(16)}...)")
        }
        update.spentUtxos.forEach { utxo ->
            // Spent UTXOs use intentHash from subscription (their creating tx's intent hash)
            android.util.Log.d("UtxoManager", "  SPENT: intentHash=${utxo.intentHash.take(16)}...:${utxo.outputIndex} = ${utxo.value}")
        }

        // Handle based on transaction status
        when (status) {
            TransactionStatus.SUCCESS, TransactionStatus.PARTIAL_SUCCESS -> {
                // Insert created UTXOs as AVAILABLE
                // Note: Uses REPLACE strategy so if UTXO already exists, it gets updated
                if (createdCount > 0) {
                    val entities = createdUtxosWithTxHash.map { utxo ->
                        UnshieldedUtxoEntity.fromUtxo(utxo, state = UtxoState.AVAILABLE)
                    }
                    android.util.Log.d("UtxoManager", "Inserting ${entities.size} UTXOs: ${entities.map { it.id }}")
                    utxoDao.insertUtxos(entities)
                }

                // Mark spent UTXOs as SPENT (permanent)
                // For spent UTXOs, we need to find them by intentHash (what the subscription returns)
                // then mark them by their id (which is transactionHash:outputIndex)
                if (spentCount > 0) {
                    val utxoIds = update.spentUtxos.mapNotNull { spentUtxo ->
                        // Find the UTXO by its intentHash+outputIndex
                        val existing = utxoDao.getUtxoByIntentHash(spentUtxo.intentHash, spentUtxo.outputIndex)
                        if (existing != null) {
                            android.util.Log.d("UtxoManager", "  Found spent UTXO: ${existing.id}")
                            existing.id
                        } else {
                            // UTXO not in our database - might be from before we started syncing
                            android.util.Log.w("UtxoManager", "  Spent UTXO not found: ${spentUtxo.intentHash.take(16)}...:${spentUtxo.outputIndex}")
                            null
                        }
                    }
                    if (utxoIds.isNotEmpty()) {
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

    // ========== Phase 2B: Coin Selection & Atomic Locking ==========

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
     * **Source:** Based on midnight-wallet coin selection + state management
     * **File:** `midnight-wallet/packages/capabilities/src/balancer/Balancer.ts`
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
        android.util.Log.d("UtxoManager", "selectAndLockUtxos: Found ${availableUtxos.size} AVAILABLE UTXOs for token $tokenType")
        availableUtxos.forEach { utxo ->
            android.util.Log.d("UtxoManager", "  AVAILABLE UTXO: ${utxo.id} = ${utxo.value} (state=${utxo.state})")
        }

        // Step 2: Perform coin selection (smallest-first)
        val selector = UtxoSelector()
        val selectionResult = selector.selectUtxos(availableUtxos, requiredAmount)

        // Step 3: If successful, UPDATE selected UTXOs to PENDING
        if (selectionResult is UtxoSelector.SelectionResult.Success) {
            val utxoIds = selectionResult.selectedUtxos.map { it.id }
            android.util.Log.d("UtxoManager", "Selected ${utxoIds.size} UTXOs: $utxoIds")
            utxoDao.markAsPending(utxoIds)
        } else {
            android.util.Log.d("UtxoManager", "Coin selection failed: $selectionResult")
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
     * Mark UTXOs as spent (transaction confirmed).
     *
     * Used when transaction is successfully confirmed on-chain.
     * Permanently marks UTXOs as spent.
     *
     * **State Transition:** PENDING → SPENT
     *
     * @param utxoIds List of UTXO IDs to mark as spent
     */
    suspend fun markUtxosAsSpent(utxoIds: List<String>) {
        if (utxoIds.isNotEmpty()) {
            android.util.Log.d("UtxoManager", "Marking ${utxoIds.size} UTXOs as SPENT: $utxoIds")
            utxoDao.markAsSpent(utxoIds)
            android.util.Log.d("UtxoManager", "✅ UTXOs marked as SPENT")
        }
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
