// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.shielded

/**
 * Result of shielded key derivation containing Midnight's ZSwap public keys.
 *
 * Midnight's shielded addresses use zero-knowledge proofs (ZKPs) and consist of two components:
 * - **Coin Public Key (CPK):** Used in zero-knowledge circuits for coin ownership
 * - **Encryption Public Key (EPK):** Used for encrypting transaction data
 *
 * **Algorithm:**
 * Both keys are derived from a 32-byte seed using Midnight's proprietary algorithm:
 * 1. Coin secret key: `Blake2b("midnight:csk" || seed)`
 * 2. Coin public key: `Blake2b("midnight:zswap-pk[v1]" || coin_secret_key)`
 * 3. Encryption keys: JubJub elliptic curve operations
 *
 * **Format:**
 * Both keys are serialized as 64 hexadecimal characters (32 bytes).
 *
 * **Addressing:**
 * These keys are encoded into Bech32m addresses with the `mn_shield-cpk` and `mn_shield-epk` prefixes:
 * - `mn_shield-cpk_testnet1...` (coin public key address)
 * - `mn_shield-epk_testnet1...` (encryption public key address)
 *
 * **Security:**
 * - Public keys are safe to share publicly
 * - The seed used to derive these keys MUST be kept secret
 * - Never log or transmit the seed
 *
 * **References:**
 * - Midnight Ledger: `midnight-libraries/midnight-ledger/zswap/src/keys.rs`
 * - Midnight Wallet SDK: `midnight-zswap` (see Cargo.toml for version)
 *
 * @property coinPublicKey 64-character hex string (32 bytes) - ZSwap coin public key
 * @property encryptionPublicKey 64-character hex string (32 bytes) - ZSwap encryption public key
 */
data class ShieldedKeys(
    val coinPublicKey: String,
    val encryptionPublicKey: String
) {
    init {
        require(coinPublicKey.length == 64) {
            "Coin public key must be 64 hex characters (32 bytes), got ${coinPublicKey.length}"
        }
        require(encryptionPublicKey.length == 64) {
            "Encryption public key must be 64 hex characters (32 bytes), got ${encryptionPublicKey.length}"
        }
        require(coinPublicKey.matches(Regex("^[0-9a-f]{64}$"))) {
            "Coin public key must be lowercase hex string"
        }
        require(encryptionPublicKey.matches(Regex("^[0-9a-f]{64}$"))) {
            "Encryption public key must be lowercase hex string"
        }
    }

    /**
     * Returns the coin public key as a byte array.
     *
     * Useful for further processing like Bech32m encoding.
     *
     * @return 32-byte array
     */
    fun coinPublicKeyBytes(): ByteArray {
        return coinPublicKey.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Returns the encryption public key as a byte array.
     *
     * Useful for further processing like Bech32m encoding.
     *
     * @return 32-byte array
     */
    fun encryptionPublicKeyBytes(): ByteArray {
        return encryptionPublicKey.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    override fun toString(): String {
        return "ShieldedKeys(coinPublicKey=${coinPublicKey.take(8)}..., " +
                "encryptionPublicKey=${encryptionPublicKey.take(8)}...)"
    }
}
