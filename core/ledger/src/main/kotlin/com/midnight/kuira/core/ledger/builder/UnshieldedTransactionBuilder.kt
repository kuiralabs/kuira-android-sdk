// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.builder

import android.util.Log
import com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.indexer.utxo.UtxoSelector
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UnshieldedOffer
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.model.UtxoSpend
import java.math.BigInteger

/**
 * Builds unshielded transactions for Midnight blockchain.
 *
 * **Purpose:**
 * - Construct balanced transactions from user inputs
 * - Select appropriate UTXOs using coin selection
 * - Create Intent with guaranteed offer
 * - Calculate change and create change outputs
 *
 * **Usage:**
 * ```kotlin
 * val builder = UnshieldedTransactionBuilder(utxoManager)
 *
 * val result = builder.buildTransfer(
 *     from = "mn_addr_sender...",
 *     to = "mn_addr_recipient...",
 *     amount = BigInteger("100000000"),  // 100.0 NIGHT
 *     tokenType = UtxoOutput.NATIVE_TOKEN_TYPE
 * )
 *
 * when (result) {
 *     is BuildResult.Success -> {
 *         val intent = result.intent
 *         // Proceed to sign the intent
 *     }
 *     is BuildResult.InsufficientFunds -> {
 *         // Show error to user
 *     }
 * }
 * ```
 *
 * **Transaction Structure:**
 * ```
 * Intent {
 *   guaranteedUnshieldedOffer: {
 *     inputs: [UtxoSpend, ...]      // Selected UTXOs
 *     outputs: [
 *       UtxoOutput(recipient),       // Payment output
 *       UtxoOutput(sender)?          // Change output (if any)
 *     ]
 *     signatures: []                 // Empty (added during signing)
 *   }
 *   ttl: currentTime + 30 minutes
 * }
 * ```
 *
 * **Important:**
 * - UTXOs are LOCKED during selection (state = PENDING)
 * - If transaction fails, call `utxoManager.unlockUtxos()` to release
 * - Change output only created if change > 0
 * - Signatures added during the signing phase
 *
 * @property utxoManager Manager for UTXO selection and locking
 */
class UnshieldedTransactionBuilder(
    private val utxoManager: UtxoManager
) {

    /**
     * Build an unshielded transfer transaction.
     *
     * **Steps:**
     * 1. Validate inputs
     * 2. Select and lock UTXOs (smallest-first algorithm)
     * 3. Convert selected UTXOs to UtxoSpend inputs
     * 4. Create recipient UtxoOutput
     * 5. Create change UtxoOutput if needed (change > 0)
     * 6. Create UnshieldedOffer with inputs and outputs
     * 7. Create Intent with TTL
     *
     * **UTXO Locking:**
     * Selected UTXOs are automatically marked as PENDING by UtxoManager.
     * If transaction building succeeds but later fails (signing/submission),
     * call `utxoManager.unlockUtxos()` to release them.
     *
     * **Public Key Derivation:**
     * The indexer doesn't provide public keys for UTXOs (security/privacy).
     * The caller must derive the public key from the HD wallet before calling this method.
     * This matches Lace wallet's architecture.
     *
     * @param from Sender's unshielded address
     * @param to Recipient's unshielded address
     * @param amount Amount to send (in smallest units)
     * @param tokenType Token type identifier (64 hex chars)
     * @param senderPublicKey Sender's BIP-340 public key (33 bytes hex)
     * @param ttlMinutes Transaction time-to-live in minutes (default 30)
     * @return BuildResult.Success with Intent, or BuildResult.InsufficientFunds
     */
    suspend fun buildTransfer(
        from: String,
        to: String,
        amount: BigInteger,
        tokenType: String,
        senderPublicKey: String,
        ttlMinutes: Int = DEFAULT_TTL_MINUTES,
        largestFirst: Boolean = false,
        // Anchor the TTL to the CHAIN block time (ms), not device wall-clock: the node validates the
        // intent TTL against its chain time, which can sit outside a wall-clock window on a network
        // where the two diverge (localnet). Default = wall-clock so standalone/test callers compile.
        nowMs: Long = System.currentTimeMillis(),
        // When set, overrides ttlMinutes with an exact TTL window in seconds. The SDK sizes this to
        // the chain's global_ttl so a fixed minute-granular window can't overshoot a tight node and
        // get the tx rejected (custom error 182 / IntentTtlTooFarInFuture).
        ttlSecondsOverride: Long? = null,
    ): BuildResult {
        // Step 1: Validate inputs
        require(from.isNotBlank()) { "Sender address cannot be blank" }
        require(to.isNotBlank()) { "Recipient address cannot be blank" }
        require(amount > BigInteger.ZERO) { "Amount must be positive, got: $amount" }
        require(tokenType.isNotBlank()) { "Token type cannot be blank" }
        require(senderPublicKey.isNotBlank()) { "Sender public key cannot be blank" }
        require(senderPublicKey.length == 64) { "Sender public key must be 64 hex chars (32 bytes BIP-340 x-only), got: ${senderPublicKey.length}" }
        require(ttlMinutes > 0) { "TTL minutes must be positive, got: $ttlMinutes" }
        require(ttlSecondsOverride == null || ttlSecondsOverride > 0) {
            "TTL seconds override must be positive, got: $ttlSecondsOverride"
        }

        // Step 2: Select and lock UTXOs
        val selectionResult = utxoManager.selectAndLockUtxos(
            address = from,
            tokenType = tokenType,
            requiredAmount = amount,
            largestFirst = largestFirst
        )

        // Handle insufficient funds
        if (selectionResult is UtxoSelector.SelectionResult.InsufficientFunds) {
            return BuildResult.InsufficientFunds(
                required = selectionResult.required,
                available = selectionResult.available,
                shortfall = selectionResult.shortfall
            )
        }

        val success = selectionResult as UtxoSelector.SelectionResult.Success

        // Step 3: Convert selected UTXOs to UtxoSpend inputs
        // Note: We use the derived public key from HD wallet, not from database
        Log.d("TxBuilder", "Converting ${success.selectedUtxos.size} UTXOs to UtxoSpend inputs:")
        val inputs = success.selectedUtxos.map { utxo ->
            Log.d("TxBuilder", "  UTXO: intentHash=${utxo.intentHash}, outputIndex=${utxo.outputIndex}, value=${utxo.value}")
            Log.d("TxBuilder", "        transactionHash=${utxo.transactionHash}, id=${utxo.id}")
            utxo.toUtxoSpend(senderPublicKey)
        }

        // Step 4: Create recipient output
        val recipientOutput = UtxoOutput(
            value = amount,
            owner = to,
            tokenType = tokenType
        )

        // Step 5: Create change output if needed
        val outputs = if (success.change > BigInteger.ZERO) {
            // Have change - create two outputs (recipient + change)
            val changeOutput = UtxoOutput(
                value = success.change,
                owner = from,  // Change goes back to sender
                tokenType = tokenType
            )
            listOf(recipientOutput, changeOutput)
        } else {
            // No change - only recipient output
            listOf(recipientOutput)
        }

        // Step 6: Create UnshieldedOffer
        val offer = UnshieldedOffer(
            inputs = inputs,
            outputs = outputs,
            signatures = emptyList()  // Empty - signatures added during signing
        )

        // Step 7: Calculate TTL — anchored to the CHAIN block time (nowMs), not wall-clock.
        // An explicit seconds override (the SDK sizes it to the chain's global_ttl) wins over the
        // minute-granular ttlMinutes default.
        val windowMillis = ttlSecondsOverride?.let { it * MILLIS_PER_SECOND }
            ?: (ttlMinutes.toLong() * SECONDS_PER_MINUTE * MILLIS_PER_SECOND)
        val ttl = nowMs + windowMillis

        // Step 8: Create Intent
        val intent = Intent(
            guaranteedUnshieldedOffer = offer,
            fallibleUnshieldedOffer = null,  // Not used for unshielded transfers
            ttl = ttl
        )

        return BuildResult.Success(intent, success.selectedUtxos)
    }

    /**
     * Result of transaction building.
     */
    sealed class BuildResult {
        /**
         * Transaction built successfully.
         *
         * @property intent The constructed Intent (ready for signing)
         * @property lockedUtxos The UTXOs locked for this transaction (now PENDING)
         */
        data class Success(
            val intent: Intent,
            val lockedUtxos: List<UnshieldedUtxoEntity>
        ) : BuildResult()

        /**
         * Insufficient funds to build transaction.
         *
         * @property required Amount required
         * @property available Amount available
         * @property shortfall How much more is needed
         */
        data class InsufficientFunds(
            val required: BigInteger,
            val available: BigInteger,
            val shortfall: BigInteger
        ) : BuildResult()
    }

    companion object {
        /**
         * Default TTL: 30 minutes.
         *
         * Matches the Midnight SDK default.
         */
        const val DEFAULT_TTL_MINUTES = 30

        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * Convert UnshieldedUtxoEntity (database model) to UtxoSpend (ledger model).
 *
 * Maps database fields to transaction input fields.
 *
 * **Important:** The indexer doesn't provide public keys for privacy/security.
 * The public key must be derived from the HD wallet when spending UTXOs.
 * This matches Lace wallet's architecture.
 *
 * @param ownerPublicKey The BIP-340 public key derived from HD wallet (33 bytes hex)
 */
private fun UnshieldedUtxoEntity.toUtxoSpend(ownerPublicKey: String): UtxoSpend {
    return UtxoSpend(
        intentHash = this.intentHash,  // Blockchain identifies UTXOs by intentHash + outputNo
        outputNo = this.outputIndex,
        value = BigInteger(this.value),
        owner = this.owner,
        ownerPublicKey = ownerPublicKey,
        tokenType = this.tokenType
    )
}
