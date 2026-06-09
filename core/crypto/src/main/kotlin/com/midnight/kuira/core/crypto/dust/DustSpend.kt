// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.dust

import java.math.BigInteger

/**
 * Represents spending a Dust token to pay transaction fees.
 *
 * **What is a Dust Spend?**
 * When you create a transaction, you need to pay fees. To pay fees, you "spend"
 * a Dust token by providing:
 * - The old dust token's nullifier (to prevent double-spending)
 * - A commitment to the new dust token (the "change")
 * - A zero-knowledge proof that the spend is valid
 * - The fee amount
 *
 * **Dust Spend Flow:**
 * ```
 * Old Dust Token (e.g., 5 Dust)
 *   ↓
 * Spend 2 Dust for fees
 *   ↓
 * ├─ Fee: 2 Dust (destroyed, goes to validators)
 * └─ Change: 3 Dust (new dust token, back to you)
 * ```
 *
 * **Zero-Knowledge Proof:**
 * The proof ensures:
 * - You own the old dust token (know the secret key)
 * - The old nullifier is correctly computed
 * - The new commitment is correctly computed
 * - The fee amount is valid (old_value - new_value = fee)
 * - You're not creating dust out of thin air
 *
 * **Ledger Mapping:**
 * Mirrors the ledger's DustSpend: fee amount, spent-token nullifier, change
 * commitment, and ZK proof.
 *
 * **Usage:**
 * ```kotlin
 * // Create a dust spend
 * val spend = wallet.createDustSpend(
 *     oldToken = myDustToken,
 *     feeAmount = BigInteger.valueOf(2_000_000), // 2 Dust
 *     secretKey = myDustSecretKey
 * )
 *
 * // Include in transaction
 * val tx = UnshieldedTransaction(
 *     inputs = listOf(...),
 *     outputs = listOf(...),
 *     dustSpend = spend,  // Pay fees with this
 *     ttl = System.currentTimeMillis() + 60_000
 * )
 * ```
 *
 * @property feeAmount Fee paid in Specks (old_value - new_value)
 * @property oldNullifier Nullifier of the dust token being spent
 * @property newCommitment Commitment to the new (change) dust token
 * @property proof Zero-knowledge proof (hex-encoded, optional for now)
 */
data class DustSpend(
    val feeAmount: BigInteger,
    val oldNullifier: String, // Hex-encoded field element (32 bytes)
    val newCommitment: String, // Hex-encoded field element (32 bytes)
    val proof: String? = null // Hex-encoded ZK proof
) {
    /**
     * Validates that this dust spend is well-formed.
     *
     * **Validation Checks:**
     * - Fee amount is positive
     * - Old nullifier is 64 hex characters (32 bytes)
     * - New commitment is 64 hex characters (32 bytes)
     * - Proof is present (if required)
     *
     * **Note:** This is basic format validation. Full validation requires:
     * - Checking nullifier hasn't been used before
     * - Verifying the zero-knowledge proof
     * - Checking Merkle tree inclusion
     * - Validating against on-chain state
     *
     * @return true if well-formed, false otherwise
     */
    fun isWellFormed(): Boolean {
        if (feeAmount <= BigInteger.ZERO) {
            return false
        }

        if (oldNullifier.length != 64 || !oldNullifier.matches(HEX_64_REGEX)) {
            return false
        }

        if (newCommitment.length != 64 || !newCommitment.matches(HEX_64_REGEX)) {
            return false
        }

        // Proof verification is performed during transaction validation, not here.

        return true
    }

    /**
     * Checks if this dust spend has a proof attached.
     *
     * @return true if proof is present and non-empty
     */
    fun hasProof(): Boolean {
        return !proof.isNullOrEmpty()
    }

    /**
     * Converts this DustSpend to a serializable format for transactions.
     *
     * **Serialization Format:**
     * Uses Midnight's SCALE codec, matching the ledger's DustSpend serialization.
     *
     * **Note:** Not yet implemented.
     *
     * @return Serialized bytes
     */
    fun serialize(): ByteArray {
        // Not yet implemented. Should match the ledger's SCALE codec serialization.

        throw NotImplementedError("Serialization (Phase 2E)")
    }

    companion object {
        /**
         * Regex for validating 64-character hex strings (32 bytes).
         * Compiled once for performance.
         */
        private val HEX_64_REGEX = Regex("[0-9a-fA-F]{64}")

        /**
         * Creates a DustSpend from an old token and fee amount.
         *
         * **Algorithm:**
         * 1. Calculate old nullifier = Hash(secret_key, old_nonce, ...)
         * 2. Calculate new value = old_value - fee
         * 3. Create new dust token with new_value
         * 4. Calculate new commitment = Hash(new_initial_value, owner, new_nonce, ctime)
         * 5. Generate zero-knowledge proof
         *
         * **Note:** Not yet implemented.
         *
         * @param oldToken The dust token being spent
         * @param feeAmount Amount to pay in fees (Specks)
         * @param secretKey Dust secret key (hex-encoded)
         * @param newNonce New nonce for the change token
         * @return DustSpend
         */
        fun create(
            oldToken: DustToken,
            feeAmount: BigInteger,
            secretKey: String,
            newNonce: String
        ): DustSpend {
            // Not yet implemented. Requires:
            // - Nullifier calculation (needs secret key)
            // - Commitment calculation (transient hash)
            // - Zero-knowledge proof generation

            throw NotImplementedError("DustSpend creation (Phase 2D-6)")
        }

        /**
         * Deserializes a DustSpend from bytes.
         *
         * **Note:** Not yet implemented.
         *
         * @param bytes Serialized bytes
         * @return Deserialized DustSpend
         */
        fun deserialize(bytes: ByteArray): DustSpend {
            // Not yet implemented.
            throw NotImplementedError("Deserialization (Phase 2E)")
        }

        /**
         * Minimum fee required for a transaction.
         * This is a conservative floor; actual minimum fees depend on transaction
         * size and network parameters.
         */
        val MINIMUM_FEE = BigInteger.valueOf(100_000) // 0.1 Dust
    }
}

/**
 * Error types for dust spending operations.
 */
sealed class DustSpendError : Exception() {
    /**
     * The backing Night UTXO for this dust token was not found.
     */
    data class BackingNightNotFound(val dustToken: DustToken) : DustSpendError() {
        override val message: String
            get() = "Backing Night of Dust UTXO not found: ${dustToken.backingNight}"
    }

    /**
     * Not enough dust available to pay the fee.
     */
    data class NotEnoughDust(
        val available: BigInteger,
        val required: BigInteger
    ) : DustSpendError() {
        override val message: String
            get() = "Not enough dust: attempted to spend $required Specks, but only $available available"
    }

    /**
     * The dust UTXO is not tracked in the wallet state.
     */
    data class DustUtxoNotTracked(val dustToken: DustToken) : DustSpendError() {
        override val message: String
            get() = "Attempted to spend Dust UTXO that's not in the wallet state: ${dustToken.nonce}"
    }

    /**
     * A Merkle tree is not fully rehashed and can't be used.
     */
    data class MerkleTreeNotRehashed(val treeName: String) : DustSpendError() {
        override val message: String
            get() = "$treeName Merkle tree is not fully rehashed"
    }

    /**
     * The dust token is still pending and can't be spent yet.
     */
    data class DustTokenPending(val dustToken: DustToken, val availableAt: Long) : DustSpendError() {
        override val message: String
            get() = "Dust token is pending until ${availableAt}ms: ${dustToken.nonce}"
    }
}
