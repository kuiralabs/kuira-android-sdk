// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip39

import com.midnight.kuira.core.testing.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for BIP-39 mnemonic generation and seed derivation.
 *
 * Uses official BIP-39 test vectors from:
 * https://github.com/trezor/python-mnemonic/blob/master/vectors.json
 *
 * These test vectors are the same used by @scure/bip39, ensuring
 * compatibility with midnight-wallet SDK and Lace wallet.
 */
class BIP39Test {

    @Test
    fun `given valid 24-word mnemonic when deriving seed then matches test vector`() {
        // Given - official Trezor test vector with "TREZOR" passphrase
        val mnemonic = TestFixtures.TEST_MNEMONIC_24_WORDS
        val expectedSeed = TestFixtures.EXPECTED_SEED_TREZOR_PASSPHRASE

        // When - Using passphrase "TREZOR" as per official Trezor test vectors
        val actualSeed = BIP39.mnemonicToSeed(mnemonic, "TREZOR")

        // Then - MUST match exactly for BIP-39 compliance
        assertEquals(expectedSeed, actualSeed.toHex())
    }

    @Test
    fun `given 24-word mnemonic with empty passphrase when deriving seed then matches expected`() {
        // Given
        val mnemonic = TestFixtures.TEST_MNEMONIC_24_WORDS
        val expectedSeed = TestFixtures.EXPECTED_SEED_EMPTY_PASSPHRASE

        // When - Using empty passphrase (default for most wallets)
        val actualSeed = BIP39.mnemonicToSeed(mnemonic)

        // Then
        assertEquals(expectedSeed, actualSeed.toHex())
    }

    @Test
    fun `given valid 12-word mnemonic when deriving seed then matches test vector`() {
        // Given - Trezor test vector with "TREZOR" passphrase
        // Full 64-byte PBKDF2 output (Lace compatible)
        val mnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        val expectedSeed =
            "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6f" +
            "a457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607"

        // When - Using passphrase "TREZOR" as per official Trezor test vectors
        val actualSeed = BIP39.mnemonicToSeed(mnemonic, "TREZOR")

        // Then
        assertEquals(expectedSeed, actualSeed.toHex())
    }

    @Test
    fun `given mnemonic with passphrase when deriving seed then applies passphrase`() {
        // Given
        // Full 64-byte PBKDF2 output (Lace compatible)
        val mnemonic = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"
        val passphrase = "TREZOR"
        val expectedSeed =
            "d71de856f81a8acc65e6fc851a38d4d7ec216fd0796d0a6827a3ad6ed5511a30" +
            "fa280f12eb2e47ed2ac03b5c462a0358d18d69fe4f985ec81778c1b370b652a8"

        // When
        val actualSeed = BIP39.mnemonicToSeed(mnemonic, passphrase)

        // Then
        assertEquals(expectedSeed, actualSeed.toHex())
    }

    @Test
    fun `given valid mnemonic when validating then returns true`() {
        // Given
        val mnemonic = TestFixtures.TEST_MNEMONIC_24_WORDS

        // When
        val isValid = BIP39.validateMnemonic(mnemonic)

        // Then
        assertTrue(isValid)
    }

    @Test
    fun `given invalid checksum when validating then returns false`() {
        // Given - last word "zoo" creates invalid checksum
        val invalidMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon zoo"

        // When
        val isValid = BIP39.validateMnemonic(invalidMnemonic)

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `given invalid word when validating then returns false`() {
        // Given
        val invalidMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon notaword"

        // When
        val isValid = BIP39.validateMnemonic(invalidMnemonic)

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `given invalid word count when validating then returns false`() {
        // Given - 11 words instead of 12, 15, 18, 21, or 24
        val invalidMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon"

        // When
        val isValid = BIP39.validateMnemonic(invalidMnemonic)

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `when generating mnemonic then produces valid 24-word phrase`() {
        // When
        val mnemonic = BIP39.generateMnemonic()

        // Then
        val words = mnemonic.split(" ")
        assertEquals(24, words.size)
        assertTrue(BIP39.validateMnemonic(mnemonic))
    }

    @Test
    fun `when generating 12-word mnemonic then produces valid phrase`() {
        // When
        val mnemonic = BIP39.generateMnemonic(wordCount = 12)

        // Then
        val words = mnemonic.split(" ")
        assertEquals(12, words.size)
        assertTrue(BIP39.validateMnemonic(mnemonic))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given invalid word count when generating then throws exception`() {
        // When/Then
        BIP39.generateMnemonic(wordCount = 13) // Invalid count
    }

    @Test
    fun `given 24-word mnemonic when generating seed then produces 64 bytes`() {
        // Given
        val mnemonic = TestFixtures.TEST_MNEMONIC_24_WORDS

        // When
        val seed = BIP39.mnemonicToSeed(mnemonic)

        // Then - Full 64-byte PBKDF2 output (Lace compatible)
        // See internal design notes for explanation
        assertEquals(64, seed.size)
    }
}

/**
 * Extension function to convert ByteArray to hex string for comparison with test vectors.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
