// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.fee

import android.util.Log
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.database.DustTokenEntity
import com.midnight.kuira.core.indexer.repository.DustRepository
import java.math.BigInteger
import javax.inject.Inject

/**
 * Builds DustActions for transaction fee payment.
 *
 * **High-Level Flow:**
 * ```
 * 1. Calculate transaction fee (FeeCalculator)
 * 2. Load DustLocalState from repository
 * 3. Create DustSpend for each UTXO in state (DustSpendCreator)
 * 4. Save updated state (contains new nullifiers)
 * 5. Return DustActions (ready to add to Intent)
 * ```
 *
 * **DustActions Structure:**
 * - `spends`: List of DustSpend actions (one per UTXO)
 * - `registrations`: List of DustRegistration actions (empty for fee payment)
 *
 * **Integration with TransactionSubmitter:**
 * ```kotlin
 * val dustActions = dustActionsBuilder.buildDustActions(
 *     transactionHex = serializedTx,
 *     ledgerParamsHex = paramsHex,
 *     address = userAddress,
 *     seed = userSeed
 * )
 *
 * // Add dustActions to Intent
 * val intentWithFees = intent.copy(dustActions = dustActions)
 * ```
 *
 * **Note:** This implementation works directly with DustLocalState (Rust FFI),
 * bypassing the database for fee payment. Database sync is only needed for UI display.
 *
 * @see `/midnight-wallet/packages/dust-wallet/src/Transacting.ts:addFeePayment` (TypeScript SDK reference)
 */
class DustActionsBuilder @Inject constructor(
    private val dustRepository: DustRepository,
    private val feeCalculator: FeeCalculator,
    private val dustSpendCreator: DustSpendCreator
) {

    companion object {
        private const val TAG = "DustActionsBuilder"
    }

    /**
     * Result of building dust actions.
     *
     * @property spends List of DustSpend actions
     * @property selectedCoins Coins that were selected (for rollback)
     * @property totalFee Total fee paid in Specks
     * @property change Change amount in Specks
     */
    data class DustActions(
        val spends: List<DustSpendCreator.DustSpend>,
        val selectedCoins: List<DustTokenEntity>,
        val totalFee: BigInteger,
        val change: BigInteger,
        val utxoIndices: List<Int>  // Indices of UTXOs used for fee payment
    ) {
        /** Returns true if actions were successfully created. */
        fun isSuccess(): Boolean = utxoIndices.isNotEmpty()

        /** Returns nullifiers of selected coins (for state management). */
        fun getNullifiers(): List<String> = selectedCoins.map { it.nullifier }
    }

    /**
     * Builds dust actions for transaction fee payment.
     *
     * **Steps:**
     * 1. Calculate transaction fee using midnight-ledger
     * 2. Load DustLocalState from repository
     * 3. Create DustSpend for each UTXO in state
     * 4. Save updated state (contains new nullifiers)
     * 5. Return DustActions
     *
     * **Implementation Notes:**
     * - Works directly with DustLocalState (Rust FFI), bypasses database
     * - Uses ALL available UTXOs (no coin selection for MVP)
     * - Only first spend pays fee, rest have vFee=0
     * - State is saved immediately after spends created
     *
     * **Rollback:**
     * Not needed for MVP - if transaction fails, state is already saved.
     * Future: Track UTXO states in database for UI display.
     *
     * @param transactionHex SCALE-serialized transaction (hex)
     * @param ledgerParamsHex SCALE-serialized ledger parameters (hex)
     * @param address Wallet address
     * @param seed 32-byte seed for deriving DustSecretKey
     * @param feeBlocksMargin Safety margin in blocks (default: 5)
     * @return DustActions, or null on error
     */
    suspend fun buildDustActions(
        transactionHex: String,
        ledgerParamsHex: String,
        address: String,
        seed: ByteArray,
        dustState: DustLocalState,
        feeBlocksMargin: Int = 5
    ): DustActions? {
        // Step 1: Calculate transaction fee
        val fee = feeCalculator.calculateFee(
            transactionHex = transactionHex,
            ledgerParamsHex = ledgerParamsHex,
            feeBlocksMargin = feeBlocksMargin
        )

        if (fee == null) {
            Log.e(TAG, "Failed to calculate transaction fee")
            return null
        }

        // Step 2: Use the caller-provided dust state — do NOT load or close it here. The
        // caller (TransactionSubmitter) loads the dust state once and reuses it for both the
        // fee/UTXO selection here and the dust spend, avoiding a second expensive deserialize.
        // The caller owns the state and closes it.
        val utxoCount = dustState.getUtxoCount()

        if (utxoCount == 0) {
            Log.e(TAG, "No dust UTXOs available")
            return null
        }

        // Step 3: Select UTXOs for fee payment
        val selectedIndices = (0 until utxoCount).toList()
        val totalBalance = dustState.getBalance(System.currentTimeMillis())

        if (totalBalance < fee) {
            Log.e(TAG, "Insufficient dust: $totalBalance < $fee Specks")
            return null
        }

        // NOTE: Do NOT call createDustSpend here!
        // The Rust FFI will call state.spend() when serializing the transaction.
        // Calling it here would double-spend the UTXOs.

        // Return DustActions with UTXO indices (spends will be created in Rust FFI)
        return DustActions(
            spends = emptyList(), // Spends created in Rust FFI, not here
            selectedCoins = emptyList(), // No longer tracking individual coins
            totalFee = fee,
            change = BigInteger.ZERO, // No change calculation for MVP
            utxoIndices = selectedIndices
        )
    }

    /**
     * Rolls back dust actions (unlocks coins after transaction failure).
     *
     * **When to call:**
     * - Transaction submission failed
     * - Transaction was rejected by blockchain
     * - User cancelled transaction
     *
     * **Current Implementation:**
     * No-op for MVP. DustLocalState manages UTXO state internally.
     * When dust spends fail, the state is not saved, so UTXOs remain available.
     *
     * **Future Enhancement:**
     * Track PENDING/AVAILABLE states in database for UI display.
     *
     * @param actions DustActions to roll back
     */
    suspend fun rollbackDustActions(actions: DustActions) {
        // No-op: DustLocalState rollback happens by not saving state
    }

    /**
     * Confirms dust actions (marks coins as spent after transaction success).
     *
     * **When to call:**
     * - Transaction successfully confirmed on blockchain
     *
     * **Current Implementation:**
     * No-op for MVP. DustLocalState already updated when spends were created.
     * State was saved in buildDustActions(), so spent UTXOs are already removed.
     *
     * **Future Enhancement:**
     * Track SPENT state in database for transaction history display.
     *
     * @param actions DustActions to confirm
     */
    suspend fun confirmDustActions(actions: DustActions) {
        // No-op: DustLocalState already saved in buildDustActions()
    }

    /**
     * Validates dust actions before submission.
     *
     * **Checks:**
     * - Spends were created successfully
     * - Total fee is valid
     *
     * @param actions DustActions to validate
     * @return true if valid, false otherwise
     */
    fun validateDustActions(actions: DustActions): Boolean {
        if (!actions.isSuccess()) return false
        if (actions.totalFee <= BigInteger.ZERO) return false
        return true
    }
}
