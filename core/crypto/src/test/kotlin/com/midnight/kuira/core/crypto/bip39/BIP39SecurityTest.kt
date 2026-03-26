// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip39

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security-focused tests for BIP-39 implementation.
 *
 * Tests:
 * - Randomness and non-determinism
 * - Seed length consistency
 * - Passphrase independence
 * - Thread safety
 */
class BIP39SecurityTest {

    // ========== Randomness Tests ==========

    @Test
    fun `when generating multiple mnemonics then they are different`() {
        // When
        val mnemonics = List(10) { BIP39.generateMnemonic() }

        // Then - all should be unique
        val uniqueMnemonics = mnemonics.toSet()
        assertEquals(
            "Generated mnemonics should be unique",
            10,
            uniqueMnemonics.size
        )
    }

    @Test
    fun `when generating multiple 12-word mnemonics then they are different`() {
        // When
        val mnemonics = List(10) { BIP39.generateMnemonic(wordCount = 12) }

        // Then - all should be unique
        val uniqueMnemonics = mnemonics.toSet()
        assertEquals(
            "Generated 12-word mnemonics should be unique",
            10,
            uniqueMnemonics.size
        )
    }

    @Test
    fun `when generating mnemonic multiple times then each is valid`() {
        // When/Then
        repeat(20) {
            val mnemonic = BIP39.generateMnemonic()
            assertTrue(
                "Generated mnemonic should be valid",
                BIP39.validateMnemonic(mnemonic)
            )
        }
    }

    // ========== Seed Length Consistency ==========

    @Test
    fun `when deriving seed then always produces 64 bytes`() {
        // Given
        val mnemonics = List(5) { BIP39.generateMnemonic() }

        // When/Then
        mnemonics.forEach { mnemonic ->
            val seed = BIP39.mnemonicToSeed(mnemonic)
            assertEquals(
                "Seed should always be 64 bytes (Lace compatibility)",
                64,
                seed.size
            )
        }
    }

    @Test
    fun `when deriving seed for all word counts then always produces 64 bytes`() {
        // Given
        val wordCounts = listOf(12, 15, 18, 21, 24)

        // When/Then
        wordCounts.forEach { wordCount ->
            val mnemonic = BIP39.generateMnemonic(wordCount = wordCount)
            val seed = BIP39.mnemonicToSeed(mnemonic)
            assertEquals(
                "Seed should always be 64 bytes for Lace compatibility (wordCount=$wordCount)",
                64,
                seed.size
            )
        }
    }

    // ========== Passphrase Independence ==========

    @Test
    fun `given same mnemonic with different passphrases when deriving seeds then produces different seeds`() {
        // Given
        val mnemonic = BIP39.generateMnemonic()
        val passphrases = listOf("", "test1", "test2", "test3", "🔐")

        // When
        val seeds = passphrases.map { passphrase ->
            BIP39.mnemonicToSeed(mnemonic, passphrase)
        }

        // Then - all seeds should be unique
        val uniqueSeeds = seeds.map { it.toHex() }.toSet()
        assertEquals(
            "Different passphrases should produce different seeds",
            passphrases.size,
            uniqueSeeds.size
        )
    }

    @Test
    fun `given different mnemonics with same passphrase when deriving seeds then produces different seeds`() {
        // Given
        val mnemonics = List(5) { BIP39.generateMnemonic() }
        val passphrase = "test"

        // When
        val seeds = mnemonics.map { mnemonic ->
            BIP39.mnemonicToSeed(mnemonic, passphrase)
        }

        // Then - all seeds should be unique
        val uniqueSeeds = seeds.map { it.toHex() }.toSet()
        assertEquals(
            "Different mnemonics should produce different seeds",
            mnemonics.size,
            uniqueSeeds.size
        )
    }

    // ========== Determinism Tests ==========

    @Test
    fun `given same mnemonic when deriving seed multiple times then produces identical seeds`() {
        // Given
        val mnemonic = BIP39.generateMnemonic()
        val passphrase = "test"

        // When
        val seeds = List(10) {
            BIP39.mnemonicToSeed(mnemonic, passphrase)
        }

        // Then - all seeds should be identical
        val uniqueSeeds = seeds.map { it.toHex() }.toSet()
        assertEquals(
            "Same mnemonic + passphrase should produce identical seeds",
            1,
            uniqueSeeds.size
        )
    }

    // ========== Thread Safety Tests ==========

    @Test
    fun `when generating mnemonics concurrently then all are valid and unique`() {
        // When - generate mnemonics on multiple threads
        val mnemonics = (1..50).toList().parallelStream().map {
            BIP39.generateMnemonic()
        }.toList()

        // Then - all should be valid
        mnemonics.forEach { mnemonic ->
            assertTrue(
                "Concurrently generated mnemonic should be valid",
                BIP39.validateMnemonic(mnemonic)
            )
        }

        // And - most should be unique (allow small collision rate)
        val uniqueMnemonics = mnemonics.toSet()
        assertTrue(
            "Most concurrently generated mnemonics should be unique (got ${uniqueMnemonics.size}/50)",
            uniqueMnemonics.size >= 45
        )
    }

    @Test
    fun `when deriving seeds concurrently then produces consistent results`() {
        // Given
        val mnemonic = BIP39.generateMnemonic()
        val passphrase = "test"

        // When - derive seed on multiple threads
        val seeds = (1..50).toList().parallelStream().map {
            BIP39.mnemonicToSeed(mnemonic, passphrase)
        }.toList()

        // Then - all seeds should be identical
        val uniqueSeeds = seeds.map { it.toHex() }.toSet()
        assertEquals(
            "Concurrent seed derivation should be deterministic",
            1,
            uniqueSeeds.size
        )
    }

    // ========== Entropy Distribution Tests ==========

    @Test
    fun `when generating multiple mnemonics then words are distributed`() {
        // Given
        val mnemonics = List(100) { BIP39.generateMnemonic(wordCount = 12) }

        // When - collect all words
        val allWords = mnemonics.flatMap { it.split(" ") }
        val uniqueWords = allWords.toSet()

        // Then - should have good variety of words (at least 100 unique words in 1200 total)
        assertTrue(
            "Generated mnemonics should use variety of words (got ${uniqueWords.size} unique words)",
            uniqueWords.size >= 100
        )
    }

    @Test
    fun `when generating mnemonic then first word varies`() {
        // Given
        val mnemonics = List(20) { BIP39.generateMnemonic(wordCount = 12) }

        // When
        val firstWords = mnemonics.map { it.split(" ")[0] }.toSet()

        // Then - first words should vary (at least 10 different)
        assertTrue(
            "First words should vary (got ${firstWords.size} unique first words)",
            firstWords.size >= 10
        )
    }

    @Test
    fun `when generating mnemonic then last word varies`() {
        // Given
        val mnemonics = List(20) { BIP39.generateMnemonic(wordCount = 12) }

        // When
        val lastWords = mnemonics.map { it.split(" ").last() }.toSet()

        // Then - last words should vary (checksum word)
        assertTrue(
            "Last words should vary (got ${lastWords.size} unique last words)",
            lastWords.size >= 8
        )
    }

    // ========== Passphrase Sensitivity Tests ==========

    @Test
    fun `given passphrases differing by single character when deriving seeds then produces different seeds`() {
        // Given
        val mnemonic = BIP39.generateMnemonic()
        val passphrase1 = "password"
        val passphrase2 = "Password" // Different by capitalization
        val passphrase3 = "password1" // Extra character

        // When
        val seed1 = BIP39.mnemonicToSeed(mnemonic, passphrase1)
        val seed2 = BIP39.mnemonicToSeed(mnemonic, passphrase2)
        val seed3 = BIP39.mnemonicToSeed(mnemonic, passphrase3)

        // Then - all should be different
        assertNotEquals(
            "Passphrases differing by case should produce different seeds",
            seed1.toHex(),
            seed2.toHex()
        )
        assertNotEquals(
            "Passphrases differing by one character should produce different seeds",
            seed1.toHex(),
            seed3.toHex()
        )
    }

    @Test
    fun `given empty passphrase vs space passphrase when deriving seeds then produces different seeds`() {
        // Given
        val mnemonic = BIP39.generateMnemonic()

        // When
        val seedEmpty = BIP39.mnemonicToSeed(mnemonic, "")
        val seedSpace = BIP39.mnemonicToSeed(mnemonic, " ")

        // Then - should be different
        assertNotEquals(
            "Empty passphrase and space passphrase should produce different seeds",
            seedEmpty.toHex(),
            seedSpace.toHex()
        )
    }
}

/**
 * Extension function to convert ByteArray to hex string.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
