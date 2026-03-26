// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.shielded

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for HD Wallet + Shielded Key Derivation.
 *
 * Demonstrates the complete flow:
 * 1. BIP-39 mnemonic → seed
 * 2. BIP-32 HD derivation at m/44'/2400'/account'/3/index (Zswap role)
 * 3. Shielded key derivation (Rust FFI)
 * 4. Result: Coin public key + Encryption public key
 *
 * **Test Vector:**
 * Mnemonic: "abandon abandon ... art" (24 words)
 * Path: m/44'/2400'/0'/3/0
 * Expected output matches Midnight SDK (key derivation version-abstract via Rust FFI)
 */
@RunWith(AndroidJUnit4::class)
class HDWalletShieldedIntegrationTest {

    @Before
    fun setUp() {
        // Skip tests if native library not loaded
        assumeTrue(
            "Native library not loaded - skipping integration tests",
            ShieldedKeyDeriver.isLibraryLoaded()
        )
    }

    @Test
    fun testFullFlowFromMnemonicToShieldedKeys() {
        // Standard test mnemonic
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        // Step 1: BIP-39 mnemonic → seed
        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        try {
            // Step 2: Create HD wallet from seed
            val hdWallet = HDWallet.fromSeed(bip39Seed)

            try {
                // Step 3: Derive key at m/44'/2400'/0'/3/0 (Zswap role)
                val derivedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    // Step 4: Use the 32-byte private key as shielded seed
                    val shieldedSeed = derivedKey.privateKeyBytes

                    // Step 5: Derive shielded keys using Rust FFI
                    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(shieldedSeed)

                    // Verify results
                    assertNotNull("Shielded keys should be derived", shieldedKeys)
                    shieldedKeys!!

                    // Expected values from Midnight SDK
                    assertEquals(
                        "Coin public key should match Midnight SDK",
                        "9408aeffbeedc6b9b45e1bcc621d1a273fb67f77de3f65bfbb1814d84f8b6524",
                        shieldedKeys.coinPublicKey
                    )
                    assertEquals(
                        "Encryption public key should match Midnight SDK",
                        "f3ae706bf28c856a407690b468081a7f5a123e523501b69f4395abcd7e19032b",
                        shieldedKeys.encryptionPublicKey
                    )

                } finally {
                    // CRITICAL: Clear derived key
                    derivedKey.clear()
                }
            } finally {
                // CRITICAL: Clear HD wallet
                hdWallet.clear()
            }
        } finally {
            // CRITICAL: Wipe BIP-39 seed
            MemoryUtils.wipe(bip39Seed)
        }
    }

    @Test
    fun testMultipleShieldedAddresses() {
        // Derive multiple shielded addresses from same mnemonic
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        try {
            val hdWallet = HDWallet.fromSeed(bip39Seed)

            try {
                // Derive first 3 shielded addresses
                val shieldedKeys = (0 until 3).map { index ->
                    val derivedKey = hdWallet
                        .selectAccount(0)
                        .selectRole(MidnightKeyRole.ZSWAP)
                        .deriveKeyAt(index)

                    try {
                        val keys = ShieldedKeyDeriver.deriveKeys(derivedKey.privateKeyBytes)
                        assertNotNull("Keys at index $index should be derived", keys)
                        keys!!
                    } finally {
                        derivedKey.clear()
                    }
                }

                // All keys should be different
                assertEquals(3, shieldedKeys.size)
                assertEquals(3, shieldedKeys.map { it.coinPublicKey }.toSet().size)
                assertEquals(3, shieldedKeys.map { it.encryptionPublicKey }.toSet().size)

                // Index 0 should match test vector
                assertEquals(
                    "9408aeffbeedc6b9b45e1bcc621d1a273fb67f77de3f65bfbb1814d84f8b6524",
                    shieldedKeys[0].coinPublicKey
                )

            } finally {
                hdWallet.clear()
            }
        } finally {
            MemoryUtils.wipe(bip39Seed)
        }
    }

    @Test
    fun testMultipleAccountsProduceDifferentKeys() {
        // Different accounts should produce different keys
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        try {
            val hdWallet = HDWallet.fromSeed(bip39Seed)

            try {
                // Derive from account 0
                val key0 = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                // Derive from account 1
                val key1 = hdWallet
                    .selectAccount(1)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    val shielded0 = ShieldedKeyDeriver.deriveKeys(key0.privateKeyBytes)
                    val shielded1 = ShieldedKeyDeriver.deriveKeys(key1.privateKeyBytes)

                    assertNotNull(shielded0)
                    assertNotNull(shielded1)

                    // Keys should be completely different
                    assertNotEquals(shielded0!!.coinPublicKey, shielded1!!.coinPublicKey)
                    assertNotEquals(shielded0.encryptionPublicKey, shielded1.encryptionPublicKey)

                } finally {
                    key0.clear()
                    key1.clear()
                }
            } finally {
                hdWallet.clear()
            }
        } finally {
            MemoryUtils.wipe(bip39Seed)
        }
    }

    @Test
    fun testSecureMemoryManagementWithHelper() {
        // Demonstrate secure pattern using MemoryUtils
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        val shieldedKeys = MemoryUtils.useAndWipe(BIP39.mnemonicToSeed(mnemonic)) { bip39Seed ->
            val hdWallet = HDWallet.fromSeed(bip39Seed)
            try {
                val derivedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    ShieldedKeyDeriver.deriveKeys(derivedKey.privateKeyBytes)
                } finally {
                    derivedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }

        // Verify keys were derived successfully
        assertNotNull(shieldedKeys)
        assertEquals(
            "9408aeffbeedc6b9b45e1bcc621d1a273fb67f77de3f65bfbb1814d84f8b6524",
            shieldedKeys!!.coinPublicKey
        )
    }

    @Test
    fun testDeterministicDerivation() {
        // Same mnemonic should always produce same keys (deterministic)
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        fun deriveShieldedKeys(): ShieldedKeys? {
            return MemoryUtils.useAndWipe(BIP39.mnemonicToSeed(mnemonic)) { bip39Seed ->
                val hdWallet = HDWallet.fromSeed(bip39Seed)
                try {
                    val derivedKey = hdWallet
                        .selectAccount(0)
                        .selectRole(MidnightKeyRole.ZSWAP)
                        .deriveKeyAt(0)
                    try {
                        ShieldedKeyDeriver.deriveKeys(derivedKey.privateKeyBytes)
                    } finally {
                        derivedKey.clear()
                    }
                } finally {
                    hdWallet.clear()
                }
            }
        }

        // Derive 3 times
        val keys1 = deriveShieldedKeys()
        val keys2 = deriveShieldedKeys()
        val keys3 = deriveShieldedKeys()

        // All should be identical
        assertNotNull(keys1)
        assertEquals(keys1, keys2)
        assertEquals(keys2, keys3)
    }

    @Test
    fun testUnshieldedVsShieldedKeysAreDifferent() {
        // Unshielded (NIGHT_EXTERNAL) and Shielded (ZSWAP) should produce different keys
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        try {
            val hdWallet = HDWallet.fromSeed(bip39Seed)

            try {
                // Derive unshielded key (NIGHT_EXTERNAL role)
                val unshieldedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
                    .deriveKeyAt(0)

                // Derive shielded seed (ZSWAP role)
                val shieldedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    // The private keys should be completely different
                    assertFalse(
                        "Unshielded and shielded keys should be different",
                        unshieldedKey.privateKeyBytes.contentEquals(shieldedKey.privateKeyBytes)
                    )

                    // Derive shielded public keys
                    val shieldedPubKeys = ShieldedKeyDeriver.deriveKeys(shieldedKey.privateKeyBytes)
                    assertNotNull(shieldedPubKeys)

                } finally {
                    unshieldedKey.clear()
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        } finally {
            MemoryUtils.wipe(bip39Seed)
        }
    }
}
