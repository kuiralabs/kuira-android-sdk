// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip39

import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import java.io.InputStream
import java.security.SecureRandom

/**
 * Implementation of [MnemonicService] using BitcoinJ library.
 *
 * This implementation uses the BitcoinJ library which implements
 * standard BIP-39 (same algorithm as @scure/bip39 used by midnight-wallet SDK).
 *
 * **Compatibility:**
 * - Compatible with any standard BIP-39 implementation
 * - Same algorithm as midnight-wallet SDK (@scure/bip39)
 * - Should produce identical outputs for same inputs
 * - Battle-tested in Bitcoin wallets since 2013
 *
 * **Security:**
 * - Uses cryptographically secure random number generation
 * - Implements standard PBKDF2-HMAC-SHA512 with 2048 iterations
 * - Wipes sensitive entropy from memory after use
 * - Based on official BIP-39 specification
 *
 * **Android Compatibility:**
 * - Works correctly on Android (verified on Android 16)
 * - Includes defensive fallback for unusual configurations
 */
class BitcoinJMnemonicService : MnemonicService {

    override fun generateMnemonic(wordCount: Int): String {
        require(wordCount in VALID_WORD_COUNTS) {
            "Invalid word count: $wordCount. Must be one of: ${VALID_WORD_COUNTS.joinToString()}"
        }

        // Convert word count to entropy length in bytes
        // Formula: entropy_bits = (word_count * 11) - (word_count / 3)
        // Simplified: entropy_bytes = (word_count * 4) / 3
        val entropyLength = when (wordCount) {
            12 -> 16   // 128 bits = 16 bytes
            15 -> 20   // 160 bits = 20 bytes
            18 -> 24   // 192 bits = 24 bytes
            21 -> 28   // 224 bits = 28 bytes
            24 -> 32   // 256 bits = 32 bytes
            else -> error("Unreachable: wordCount validated by require() above")
        }

        // Generate random entropy and convert to mnemonic
        // SECURITY: Wipe entropy from memory after use
        val entropy = ByteArray(entropyLength)
        try {
            secureRandom.nextBytes(entropy)
            val wordList = mnemonicCode.toMnemonic(entropy)
            return wordList.joinToString(" ")
        } finally {
            // Wipe sensitive entropy from memory
            entropy.fill(0)
        }
    }

    override fun mnemonicToSeed(mnemonic: String, passphrase: String): ByteArray {
        require(validateMnemonic(mnemonic)) {
            "Invalid mnemonic phrase"
        }

        require(passphrase.length <= MAX_PASSPHRASE_LENGTH) {
            "Passphrase too long (max $MAX_PASSPHRASE_LENGTH characters). " +
                    "Got ${passphrase.length} characters."
        }

        // Convert string to List<String> and normalize whitespace
        val words = mnemonic.trim().split(WHITESPACE_REGEX)

        // Convert mnemonic to seed using BIP-39 standard
        // PBKDF2-HMAC-SHA512 with 2048 iterations
        // BitcoinJ's toSeed is a static method
        //
        // Note: BitcoinJ internally copies the words list. We cannot wipe
        // BitcoinJ's internal copy, but the caller should wipe the seed after use.
        return MnemonicCode.toSeed(words, passphrase)
    }

    override fun mnemonicToEntropy(mnemonic: String): ByteArray {
        require(validateMnemonic(mnemonic)) {
            "Invalid mnemonic phrase"
        }
        val words = mnemonic.trim().split(WHITESPACE_REGEX)
        return mnemonicCode.toEntropy(words)
    }

    override fun entropyToMnemonic(entropy: ByteArray): String {
        // BitcoinJ validates entropy length internally
        val words = mnemonicCode.toMnemonic(entropy)
        return words.joinToString(" ")
    }

    override fun validateMnemonic(mnemonic: String): Boolean {
        return try {
            val words = mnemonic.trim().split(WHITESPACE_REGEX)

            // Check word count
            if (words.size !in VALID_WORD_COUNTS) {
                return false
            }

            // Validate using BitcoinJ
            // check() throws MnemonicException if invalid
            mnemonicCode.check(words)
            true
        } catch (e: MnemonicException) {
            // Expected: invalid word, bad checksum, etc.
            false
        }
        // Note: Let unexpected exceptions (e.g., IllegalStateException) propagate
        // to help catch bugs during development
    }

    companion object {
        /**
         * Valid BIP-39 mnemonic word counts.
         * Each corresponds to specific entropy:
         * - 12 words = 128 bits (16 bytes)
         * - 15 words = 160 bits (20 bytes)
         * - 18 words = 192 bits (24 bytes)
         * - 21 words = 224 bits (28 bytes)
         * - 24 words = 256 bits (32 bytes)
         */
        private val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

        /**
         * Maximum allowed passphrase length (256 characters).
         *
         * While BIP-39 technically allows any passphrase length, extremely long
         * passphrases can cause performance issues with PBKDF2 (2048 iterations).
         * 256 characters is more than sufficient for strong passphrases while
         * preventing potential DOS attacks.
         */
        private const val MAX_PASSPHRASE_LENGTH = 256

        /**
         * Shared SecureRandom instance for generating entropy.
         * Reusing the instance is more efficient and provides better randomness.
         */
        private val secureRandom = SecureRandom()

        /**
         * Regex for splitting mnemonic by whitespace.
         * Compiled once for performance.
         */
        private val WHITESPACE_REGEX = "\\s+".toRegex()

        /**
         * MnemonicCode instance with defensive initialization.
         *
         * **Android Compatibility:** BitcoinJ's MnemonicCode.INSTANCE works correctly
         * on Android (verified on Android 16). Gradle packages JAR resources in the APK,
         * making the wordlist accessible via getResourceAsStream().
         *
         * **Fallback:** Included as defensive programming in case of unusual configurations
         * or custom ROMs where static initialization might fail.
         */
        private val mnemonicCode: MnemonicCode by lazy {
            try {
                // Try to use the default INSTANCE first (works on JVM)
                MnemonicCode.INSTANCE
                    ?: throw IllegalStateException("MnemonicCode.INSTANCE is null")
            } catch (e: Exception) {
                // Fallback: Load wordlist manually (for Android)
                val wordListStream: InputStream = MnemonicCode::class.java
                    .getResourceAsStream("/org/bitcoinj/crypto/mnemonic/wordlist/english.txt")
                    ?: throw IllegalStateException(
                        "Cannot load BIP-39 wordlist. Ensure bitcoinj resources are included.",
                        e
                    )
                MnemonicCode(wordListStream, null)
            }
        }
    }
}
