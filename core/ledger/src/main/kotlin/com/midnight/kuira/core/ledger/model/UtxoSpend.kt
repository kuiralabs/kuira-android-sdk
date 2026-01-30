// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.model

import java.math.BigInteger

/**
 * Represents a UTXO being spent (input) in an unshielded transaction.
 *
 * **Source:** Based on midnight-ledger `UtxoSpend` structure
 * **File:** `midnight-ledger/ledger/src/structure.rs`
 *
 * **Purpose:**
 * - References an existing UTXO by intentHash and outputNo
 * - Specifies the owner address (for signature verification)
 * - Includes value and token type for balancing
 *
 * **CRITICAL:** The blockchain identifies UTXOs by intentHash + outputNo.
 *
 * **Usage in Transaction:**
 * ```kotlin
 * val input = UtxoSpend(
 *     intentHash = "87eb8bd03bfb2cd7...",  // Intent hash from indexer
 *     outputNo = 0,
 *     value = BigInteger("1000000"),  // 1.0 NIGHT
 *     owner = "mn_addr_undeployed1...",
 *     tokenType = "0000...000"  // NIGHT token
 * )
 * ```
 *
 * @property intentHash Intent hash that identifies this UTXO on chain (hex string, 64 chars)
 * @property outputNo Output index in the creating transaction (0-based)
 * @property value Amount being spent (in smallest units)
 * @property owner Owner's unshielded address (Bech32m format, for display)
 * @property ownerPublicKey Owner's BIP-340 x-only public key (hex-encoded, 32 bytes, for signing)
 * @property tokenType Token type identifier (hex string, 64 chars)
 */
data class UtxoSpend(
    val intentHash: String,
    val outputNo: Int,
    val value: BigInteger,
    val owner: String,
    val ownerPublicKey: String,
    val tokenType: String
) {
    init {
        require(intentHash.isNotBlank()) { "Intent hash cannot be blank" }
        require(outputNo >= 0) { "Output number must be non-negative, got: $outputNo" }
        require(value >= BigInteger.ZERO) { "Value must be non-negative, got: $value" }
        require(owner.isNotBlank()) { "Owner address cannot be blank" }
        require(ownerPublicKey.isNotBlank()) { "Owner public key cannot be blank" }
        require(ownerPublicKey.length == 64) { "Owner public key must be 64 hex characters (32 bytes BIP-340 x-only), got: ${ownerPublicKey.length}" }
        require(tokenType.isNotBlank()) { "Token type cannot be blank" }
        require(tokenType.length == 64) { "Token type must be 64 hex characters, got: ${tokenType.length}" }
    }

    /**
     * Unique identifier for this UTXO.
     *
     * **Format:** `intentHash:outputNo`
     *
     * Used for database lookups and UTXO tracking.
     */
    fun identifier(): String = "$intentHash:$outputNo"

    companion object {
        /**
         * Native NIGHT token type (all zeros).
         */
        const val NATIVE_TOKEN_TYPE = "0000000000000000000000000000000000000000000000000000000000000000"

        /**
         * Test public key (32 bytes BIP-340 x-only, hex-encoded).
         * Used in unit tests. All zeros (corresponds to a specific private key).
         */
        const val TEST_PUBLIC_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
