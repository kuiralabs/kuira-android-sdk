// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [PlaintextSeed] data class contract.
 *
 * Full SeedVault encrypt/decrypt round-trip tests live in androidTest/
 * since they require Android Keystore and BiometricPrompt.
 */
class PlaintextSeedTest {

    private val validEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { it.toByte() }
    private val validSeed = ByteArray(PlaintextSeed.SEED_SIZE) { (it + 100).toByte() }

    @Test
    fun `ENTROPY_SIZE is 32 bytes for 24-word mnemonic`() {
        // BIP-39: 24 words = 256 bits = 32 bytes of entropy
        assertEquals(32, PlaintextSeed.ENTROPY_SIZE)
    }

    @Test
    fun `SEED_SIZE is 64 bytes per BIP-39`() {
        // BIP-39: mnemonic + passphrase → PBKDF2-HMAC-SHA512 → 512-bit (64-byte) seed
        assertEquals(64, PlaintextSeed.SEED_SIZE)
    }

    @Test
    fun `PLAINTEXT_SIZE is entropy plus seed`() {
        assertEquals(
            PlaintextSeed.ENTROPY_SIZE + PlaintextSeed.SEED_SIZE,
            PlaintextSeed.PLAINTEXT_SIZE
        )
        assertEquals(96, PlaintextSeed.PLAINTEXT_SIZE)
    }

    @Test
    fun `constructor accepts valid sizes`() {
        val plaintext = PlaintextSeed(validEntropy, validSeed)
        assertEquals(PlaintextSeed.ENTROPY_SIZE, plaintext.mnemonicEntropy.size)
        assertEquals(PlaintextSeed.SEED_SIZE, plaintext.bip39Seed.size)
    }

    @Test
    fun `constructor rejects wrong entropy size`() {
        try {
            PlaintextSeed(ByteArray(16), validSeed)
            fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("Mnemonic entropy must be 32 bytes"))
        }
    }

    @Test
    fun `constructor rejects wrong seed size`() {
        try {
            PlaintextSeed(validEntropy, ByteArray(32))
            fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("BIP-39 seed must be 64 bytes"))
        }
    }

    @Test
    fun `wipe zeros out entropy and seed`() {
        val entropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0xAB.toByte() }
        val seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0xCD.toByte() }
        val plaintext = PlaintextSeed(entropy, seed)

        plaintext.wipe()

        // Both arrays should be zeroed
        assertTrue(plaintext.mnemonicEntropy.all { it == 0.toByte() })
        assertTrue(plaintext.bip39Seed.all { it == 0.toByte() })
    }

    @Test
    fun `wipe affects the original arrays in-place`() {
        // The wipe() implementation should modify the arrays we were given,
        // not copies — otherwise the caller's references stay populated.
        val entropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0xFF.toByte() }
        val seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0xFF.toByte() }
        val plaintext = PlaintextSeed(entropy, seed)

        plaintext.wipe()

        // The ORIGINAL arrays (not copies) should be zeroed
        assertTrue(entropy.all { it == 0.toByte() })
        assertTrue(seed.all { it == 0.toByte() })
    }
}
