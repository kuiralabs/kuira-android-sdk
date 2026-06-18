package com.midnight.kuira.core.identity.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #252: the recovery-phrase codec is the cryptographic heart of "reveal a phrase that restores the
 * exact wallet." These pin the round-trip (entropy → words → entropy), a published BIP-39 vector,
 * input normalization, and rejection of bad input.
 */
class SeedDeriverRecoveryCodecTest {

    @Test
    fun `all-zero entropy maps to the canonical 256-bit BIP-39 vector`() {
        val words = SeedDeriver.entropyToRecoveryWords(ByteArray(32))
        assertEquals(24, words.size)
        assertEquals("abandon", words.first())
        assertEquals("art", words.last()) // the well-known 24-word all-zero vector ends in "art"
    }

    @Test
    fun `entropy to words back to entropy is a faithful round trip`() {
        val entropy = ByteArray(32) { it.toByte() }
        val words = SeedDeriver.entropyToRecoveryWords(entropy)
        assertArrayEquals(entropy, SeedDeriver.recoveryWordsToEntropy(words))
    }

    @Test
    fun `a generated phrase validates`() {
        val words = SeedDeriver.entropyToRecoveryWords(ByteArray(32) { 0x5A })
        assertTrue(SeedDeriver.isValidRecoveryPhrase(words))
    }

    @Test
    fun `casing and surrounding whitespace are normalized`() {
        val words = SeedDeriver.entropyToRecoveryWords(ByteArray(32) { it.toByte() })
        val messy = words.map { "  ${it.uppercase()} " }
        assertTrue(SeedDeriver.isValidRecoveryPhrase(messy))
        assertArrayEquals(
            SeedDeriver.recoveryWordsToEntropy(words),
            SeedDeriver.recoveryWordsToEntropy(messy),
        )
    }

    @Test
    fun `a broken checksum is rejected`() {
        val words = SeedDeriver.entropyToRecoveryWords(ByteArray(32)).toMutableList()
        words[words.lastIndex] = "abandon" // valid wordlist word, but now the checksum is wrong
        assertFalse(SeedDeriver.isValidRecoveryPhrase(words))
    }

    @Test
    fun `wrong word count is rejected`() {
        assertFalse(SeedDeriver.isValidRecoveryPhrase(listOf("abandon", "abandon")))
    }
}
