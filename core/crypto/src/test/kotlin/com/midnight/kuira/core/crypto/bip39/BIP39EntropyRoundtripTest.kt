// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip39

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the BIP-39 mnemonic ↔ entropy conversions added for the
 * wallet storage layer (SeedVault persists entropy, not the phrase).
 *
 * Correctness matters here because an entropy encoding bug would
 * produce undecodable wallets — the user's recovery phrase would be
 * wrong when they try to read it back.
 */
class BIP39EntropyRoundtripTest {

    @Test
    fun `mnemonicToEntropy produces 32 bytes for 24 words`() {
        val mnemonic = BIP39.generateMnemonic(wordCount = 24)
        val entropy = BIP39.mnemonicToEntropy(mnemonic)
        assertEquals(32, entropy.size)
    }

    @Test
    fun `mnemonicToEntropy produces 16 bytes for 12 words`() {
        val mnemonic = BIP39.generateMnemonic(wordCount = 12)
        val entropy = BIP39.mnemonicToEntropy(mnemonic)
        assertEquals(16, entropy.size)
    }

    @Test
    fun `entropy to mnemonic to entropy is a round-trip`() {
        val original = BIP39.generateMnemonic(wordCount = 24)
        val entropy = BIP39.mnemonicToEntropy(original)
        val roundTripped = BIP39.entropyToMnemonic(entropy)

        assertEquals(
            "Mnemonic must survive entropy round-trip unchanged",
            original,
            roundTripped
        )
    }

    @Test
    fun `mnemonic to entropy to mnemonic preserves all word counts`() {
        listOf(12, 15, 18, 21, 24).forEach { wordCount ->
            val original = BIP39.generateMnemonic(wordCount)
            val entropy = BIP39.mnemonicToEntropy(original)
            val roundTripped = BIP39.entropyToMnemonic(entropy)
            assertEquals(
                "Round-trip failed for $wordCount words",
                original,
                roundTripped
            )
        }
    }

    @Test
    fun `mnemonicToEntropy rejects invalid phrase`() {
        assertThrows(IllegalArgumentException::class.java) {
            BIP39.mnemonicToEntropy("this is not a valid mnemonic phrase at all here")
        }
    }

    @Test
    fun `entropyToMnemonic rejects empty entropy`() {
        // BitcoinJ rejects zero-size entropy
        assertThrows(Exception::class.java) {
            BIP39.entropyToMnemonic(ByteArray(0))
        }
    }

    @Test
    fun `different mnemonics produce different entropy`() {
        val m1 = BIP39.generateMnemonic(24)
        val m2 = BIP39.generateMnemonic(24)
        assertTrue("Two random mnemonics must differ", m1 != m2)

        val e1 = BIP39.mnemonicToEntropy(m1)
        val e2 = BIP39.mnemonicToEntropy(m2)
        assertTrue("Different mnemonics must have different entropy", !e1.contentEquals(e2))
    }

    @Test
    fun `known test vector entropy matches expected mnemonic`() {
        // BIP-39 test vector: all zero entropy (32 bytes) → specific mnemonic
        // Source: https://github.com/trezor/python-mnemonic/blob/master/vectors.json
        val zeroEntropy = ByteArray(32) { 0 }
        val expected = "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon art"

        val actual = BIP39.entropyToMnemonic(zeroEntropy)
        assertEquals(expected, actual)

        // Round-trip: the canonical mnemonic should yield back zero entropy
        val roundTripped = BIP39.mnemonicToEntropy(expected)
        assertTrue("Round-trip entropy should be all zeros", roundTripped.all { it == 0.toByte() })
    }
}
