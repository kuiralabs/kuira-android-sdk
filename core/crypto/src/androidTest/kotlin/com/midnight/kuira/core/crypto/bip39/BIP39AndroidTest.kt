// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip39

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.testing.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumentation tests for BIP-39 implementation.
 *
 * These tests run on an actual Android device/emulator to verify:
 * 1. BitcoinJ's MnemonicCode initialization works on Android
 * 2. Resource loading works correctly
 * 3. All BIP-39 operations work on Android runtime
 *
 * **CRITICAL:** If these tests fail, it means BitcoinJ resource loading
 * doesn't work on Android and we need to bundle the wordlist separately.
 */
@RunWith(AndroidJUnit4::class)
class BIP39AndroidTest {

    @Test
    fun verifyMnemonicCodeInitializationWorksOnAndroid() {
        // This is the most critical test - it will fail if BitcoinJ
        // can't load the wordlist from resources on Android

        // When - Generate a mnemonic (triggers MnemonicCode initialization)
        val mnemonic = BIP39.generateMnemonic()

        // Then - Should succeed without throwing exceptions
        val words = mnemonic.split(" ")
        assertEquals(
            "Generated mnemonic should have 24 words",
            24,
            words.size
        )

        // And - Should be valid
        assertTrue(
            "Generated mnemonic should be valid",
            BIP39.validateMnemonic(mnemonic)
        )
    }

    @Test
    fun verifyMnemonicValidationWorksOnAndroid() {
        // Given - Official test vector
        val validMnemonic = TestFixtures.TEST_MNEMONIC_24_WORDS
        val invalidMnemonic = "invalid word word word word word word word word word word word word " +
                "word word word word word word word word word word word word"

        // When/Then
        assertTrue(
            "Valid mnemonic should pass validation on Android",
            BIP39.validateMnemonic(validMnemonic)
        )

        assertTrue(
            "Invalid mnemonic should fail validation on Android",
            !BIP39.validateMnemonic(invalidMnemonic)
        )
    }

    @Test
    fun verifySeedDerivationWorksOnAndroid() {
        // Given - Official test vector with TREZOR passphrase
        val mnemonic = TestFixtures.TEST_MNEMONIC_24_WORDS
        val expectedSeed = TestFixtures.EXPECTED_SEED_TREZOR_PASSPHRASE

        // When - Derive seed on Android
        val actualSeed = BIP39.mnemonicToSeed(mnemonic, "TREZOR")

        // Then - Should match official test vector exactly
        assertEquals(
            "Seed derivation should match official test vector on Android",
            expectedSeed,
            actualSeed.toHex()
        )
    }

    @Test
    fun verifyMultipleMnemonicsAreUniqueOnAndroid() {
        // When - Generate multiple mnemonics on Android
        val mnemonics = List(10) { BIP39.generateMnemonic() }

        // Then - All should be unique
        val uniqueMnemonics = mnemonics.toSet()
        assertEquals(
            "Generated mnemonics should be unique on Android",
            10,
            uniqueMnemonics.size
        )
    }

    @Test
    fun verifyAllWordCountsWorkOnAndroid() {
        // Given - All valid word counts
        val wordCounts = listOf(12, 15, 18, 21, 24)

        // When/Then - Each should work on Android
        wordCounts.forEach { wordCount ->
            val mnemonic = BIP39.generateMnemonic(wordCount = wordCount)
            val words = mnemonic.split(" ")

            assertEquals(
                "Mnemonic should have $wordCount words on Android",
                wordCount,
                words.size
            )

            assertTrue(
                "$wordCount-word mnemonic should be valid on Android",
                BIP39.validateMnemonic(mnemonic)
            )

            // Verify seed derivation works
            val seed = BIP39.mnemonicToSeed(mnemonic)
            assertEquals(
                "Seed should be 64 bytes for $wordCount words on Android (Lace compatibility)",
                64,
                seed.size
            )
        }
    }

    @Test
    fun verifyPassphrasesProduceDifferentSeedsOnAndroid() {
        // Given
        val mnemonic = BIP39.generateMnemonic()

        // When
        val seedEmpty = BIP39.mnemonicToSeed(mnemonic, "")
        val seedWithPassphrase = BIP39.mnemonicToSeed(mnemonic, "test")

        // Then
        assertNotEquals(
            "Different passphrases should produce different seeds on Android",
            seedEmpty.toHex(),
            seedWithPassphrase.toHex()
        )
    }

    @Test
    fun verifySeedDerivationIsDeterministicOnAndroid() {
        // Given
        val mnemonic = BIP39.generateMnemonic()
        val passphrase = "test"

        // When - Derive seed multiple times
        val seeds = List(5) {
            BIP39.mnemonicToSeed(mnemonic, passphrase)
        }

        // Then - All should be identical
        val uniqueSeeds = seeds.map { it.toHex() }.toSet()
        assertEquals(
            "Seed derivation should be deterministic on Android",
            1,
            uniqueSeeds.size
        )
    }

    @Test
    fun verifyWhitespaceHandlingOnAndroid() {
        // Given - Mnemonic with extra whitespace
        val cleanMnemonic = TestFixtures.TEST_MNEMONIC_24_WORDS
        val messyMnemonic = "  ${cleanMnemonic.replace(" ", "  ")}  " // Double spaces

        // When
        val cleanSeed = BIP39.mnemonicToSeed(cleanMnemonic, "TREZOR")
        val messySeed = BIP39.mnemonicToSeed(messyMnemonic, "TREZOR")

        // Then - Should handle whitespace gracefully
        assertEquals(
            "Whitespace should be normalized on Android",
            cleanSeed.toHex(),
            messySeed.toHex()
        )
    }
}

/**
 * Extension function to convert ByteArray to hex string.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
