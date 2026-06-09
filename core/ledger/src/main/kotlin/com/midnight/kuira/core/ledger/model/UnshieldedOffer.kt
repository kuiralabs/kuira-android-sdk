// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.model

import java.math.BigInteger

/**
 * Represents an unshielded offer in a Midnight transaction.
 *
 * An offer pairs the UTXOs being spent (inputs) with the new UTXOs being
 * created (outputs), plus the signatures authorizing each input.
 *
 * **Purpose:**
 * - Contains inputs (UTXOs being spent)
 * - Contains outputs (new UTXOs being created)
 * - Will contain signatures (added during signing)
 *
 * **Important:**
 * - Inputs and outputs are automatically SORTED by the ledger
 * - Signatures must match the sorted input order
 *
 * **Usage in Transaction:**
 * ```kotlin
 * val offer = UnshieldedOffer(
 *     inputs = listOf(
 *         UtxoSpend(intentHash = "0x123...", outputNo = 0, value = 150M, ...)
 *     ),
 *     outputs = listOf(
 *         UtxoOutput(value = 100M, owner = "recipient...", ...),  // Send
 *         UtxoOutput(value = 50M, owner = "sender...", ...)       // Change
 *     )
 * )
 *
 * // Later, during signing: add signatures
 * val signed = offer.copy(signatures = listOf(...))
 * ```
 *
 * @property inputs List of UTXOs being spent (at least one required)
 * @property outputs List of new UTXOs being created (at least one required)
 * @property signatures List of Schnorr signatures (one per input, added during signing)
 */
data class UnshieldedOffer(
    val inputs: List<UtxoSpend>,
    val outputs: List<UtxoOutput>,
    val signatures: List<ByteArray> = emptyList()
) {
    init {
        require(inputs.isNotEmpty()) { "At least one input is required" }
        require(outputs.isNotEmpty()) { "At least one output is required" }

        // If signatures are present, must match input count
        if (signatures.isNotEmpty()) {
            require(signatures.size == inputs.size) {
                "Signature count (${signatures.size}) must match input count (${inputs.size})"
            }

            // Validate signature size (BIP-340 Schnorr = 64 bytes)
            signatures.forEachIndexed { index, sig ->
                require(sig.size == 64) {
                    "Signature at index $index must be 64 bytes (BIP-340 Schnorr), got: ${sig.size}"
                }
            }
        }
    }

    /**
     * Calculate total input value for a specific token type.
     *
     * Used for transaction balancing validation.
     */
    fun totalInput(tokenType: String): BigInteger {
        return inputs
            .filter { it.tokenType == tokenType }
            .fold(BigInteger.ZERO) { acc, input -> acc + input.value }
    }

    /**
     * Calculate total output value for a specific token type.
     *
     * Used for transaction balancing validation.
     */
    fun totalOutput(tokenType: String): BigInteger {
        return outputs
            .filter { it.tokenType == tokenType }
            .fold(BigInteger.ZERO) { acc, output -> acc + output.value }
    }

    /**
     * Validate transaction is balanced for all token types.
     *
     * **Rule:** For each token type, sum(inputs) must equal sum(outputs)
     *
     * @return true if balanced, false otherwise
     */
    fun isBalanced(): Boolean {
        val tokenTypes = (inputs.map { it.tokenType } + outputs.map { it.tokenType }).toSet()

        return tokenTypes.all { tokenType ->
            totalInput(tokenType) == totalOutput(tokenType)
        }
    }

    /**
     * Check if all inputs are signed.
     *
     * @return true if signature count matches input count
     */
    fun isSigned(): Boolean {
        return signatures.size == inputs.size
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val otherOffer = other as? UnshieldedOffer ?: return false

        if (inputs != otherOffer.inputs) return false
        if (outputs != otherOffer.outputs) return false
        if (signatures.size != otherOffer.signatures.size) return false

        // Deep comparison of signature byte arrays
        signatures.forEachIndexed { index, sig ->
            if (!sig.contentEquals(otherOffer.signatures[index])) return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = inputs.hashCode()
        result = 31 * result + outputs.hashCode()
        result = 31 * result + signatures.map { it.contentHashCode() }.hashCode()
        return result
    }
}
