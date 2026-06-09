// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.model

import com.midnight.kuira.core.ledger.fee.DustSpendCreator

/**
 * Represents a complete Midnight transaction intent.
 *
 * **Purpose:**
 * - Top-level transaction container
 * - Contains offers for unshielded assets
 * - Includes TTL (time-to-live) for transaction expiry
 * - Includes dust actions for fee payment
 *
 * **Ledger Mapping:**
 * Mirrors the ledger's Intent: an optional guaranteed unshielded offer (segment 0,
 * always executes), an optional fallible unshielded offer, contract actions, dust
 * actions for fee payment, a TTL, and a cryptographic binding commitment. When the
 * dust actions are present they are required for the transaction to be valid; the
 * binding commitment is created by the ledger during signing.
 *
 * **Usage in Transaction:**
 * ```kotlin
 * // Simple unshielded transfer
 * val intent = Intent(
 *     guaranteedUnshieldedOffer = UnshieldedOffer(
 *         inputs = listOf(utxoSpend),
 *         outputs = listOf(recipientOutput, changeOutput)
 *     ),
 *     fallibleUnshieldedOffer = null,
 *     ttl = System.currentTimeMillis() + (5 * 60 * 1000)  // 5 minutes
 * )
 * ```
 *
 * **Important:**
 * - At least one offer (guaranteed or fallible) must be present
 * - Guaranteed offer executes unconditionally (always processes)
 * - Fallible offer may fail without invalidating transaction (future use)
 * - TTL must be in future (transaction expires after this time)
 *
 * @property guaranteedUnshieldedOffer Segment 0 offer (always executes)
 * @property fallibleUnshieldedOffer Optional fallible segment (may fail)
 * @property dustActions List of dust spends for fee payment
 * @property ttl Time-to-live in milliseconds (Unix epoch time)
 */
data class Intent(
    val guaranteedUnshieldedOffer: UnshieldedOffer?,
    val fallibleUnshieldedOffer: UnshieldedOffer?,
    val dustActions: List<DustSpendCreator.DustSpend>? = null,
    val ttl: Long
) {
    init {
        // At least one offer must be present
        require(guaranteedUnshieldedOffer != null || fallibleUnshieldedOffer != null) {
            "Intent must have at least one offer (guaranteed or fallible)"
        }

        // TTL must be positive (Unix epoch time)
        require(ttl > 0) {
            "TTL must be positive (milliseconds since epoch), got: $ttl"
        }
    }

    /**
     * Check if intent has expired.
     *
     * @param currentTime Current time in milliseconds (defaults to now)
     * @return true if TTL has passed
     */
    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        return currentTime > ttl
    }

    /**
     * Get remaining time until expiry.
     *
     * @param currentTime Current time in milliseconds (defaults to now)
     * @return Remaining milliseconds (negative if expired)
     */
    fun remainingTime(currentTime: Long = System.currentTimeMillis()): Long {
        return ttl - currentTime
    }

    /**
     * Check if intent is balanced (all offers are balanced).
     *
     * **Rule:** For each token type, sum(inputs) must equal sum(outputs)
     *
     * @return true if all offers are balanced, false otherwise
     */
    fun isBalanced(): Boolean {
        val guaranteedBalanced = guaranteedUnshieldedOffer?.isBalanced() ?: true
        val fallibleBalanced = fallibleUnshieldedOffer?.isBalanced() ?: true
        return guaranteedBalanced && fallibleBalanced
    }

    /**
     * Check if intent is fully signed (all offers have signatures).
     *
     * @return true if all offers are signed
     */
    fun isSigned(): Boolean {
        val guaranteedSigned = guaranteedUnshieldedOffer?.isSigned() ?: true
        val fallibleSigned = fallibleUnshieldedOffer?.isSigned() ?: true
        return guaranteedSigned && fallibleSigned
    }

    /**
     * Get total number of inputs across all offers.
     *
     * Used for signature count validation.
     */
    fun totalInputCount(): Int {
        val guaranteedInputs = guaranteedUnshieldedOffer?.inputs?.size ?: 0
        val fallibleInputs = fallibleUnshieldedOffer?.inputs?.size ?: 0
        return guaranteedInputs + fallibleInputs
    }

    /**
     * Get total number of outputs across all offers.
     *
     * Used for transaction size estimation.
     */
    fun totalOutputCount(): Int {
        val guaranteedOutputs = guaranteedUnshieldedOffer?.outputs?.size ?: 0
        val fallibleOutputs = fallibleUnshieldedOffer?.outputs?.size ?: 0
        return guaranteedOutputs + fallibleOutputs
    }

    companion object {
        /**
         * Default TTL duration: 5 minutes.
         *
         * Transactions expire after this time to prevent replay attacks.
         */
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L  // 5 minutes

        /**
         * Create intent with default TTL (current time + 5 minutes).
         *
         * @param guaranteedOffer Guaranteed unshielded offer (segment 0)
         * @param fallibleOffer Optional fallible offer
         * @param dustActions Optional dust spends for fee payment
         * @return Intent with TTL set to now + 5 minutes
         */
        fun withDefaultTtl(
            guaranteedOffer: UnshieldedOffer?,
            fallibleOffer: UnshieldedOffer? = null,
            dustActions: List<DustSpendCreator.DustSpend>? = null
        ): Intent {
            return Intent(
                guaranteedUnshieldedOffer = guaranteedOffer,
                fallibleUnshieldedOffer = fallibleOffer,
                dustActions = dustActions,
                ttl = System.currentTimeMillis() + DEFAULT_TTL_MILLIS
            )
        }
    }
}
