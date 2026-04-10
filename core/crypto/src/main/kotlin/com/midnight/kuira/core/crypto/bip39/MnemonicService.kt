// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip39

/**
 * Service for BIP-39 mnemonic operations.
 *
 * This interface abstracts the mnemonic generation and seed derivation,
 * allowing for different implementations (e.g., bitcoin-kmp, @scure/bip39 port)
 * to ensure compatibility with various wallet implementations.
 *
 * **Security Note:**
 * - Mnemonics and seeds are highly sensitive
 * - Always wipe from memory after use
 * - Never log or persist in plaintext
 */
interface MnemonicService {

    /**
     * Generates a random BIP-39 mnemonic phrase.
     *
     * @param wordCount Number of words (12, 15, 18, 21, or 24). Default: 24
     * @return Space-separated mnemonic phrase
     * @throws IllegalArgumentException if wordCount is invalid
     */
    fun generateMnemonic(wordCount: Int = 24): String

    /**
     * Converts a mnemonic phrase to a seed using PBKDF2-HMAC-SHA512.
     *
     * This follows BIP-39 specification:
     * - 2048 iterations of PBKDF2
     * - HMAC-SHA512
     * - Optional passphrase (default: "")
     *
     * @param mnemonic The BIP-39 mnemonic phrase
     * @param passphrase Optional passphrase for additional security (default: empty)
     * @return 64-byte seed derived from mnemonic
     * @throws IllegalArgumentException if mnemonic is invalid
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray

    /**
     * Validates a BIP-39 mnemonic phrase.
     *
     * Checks:
     * - All words are in the BIP-39 wordlist
     * - Word count is valid (12, 15, 18, 21, or 24)
     * - Checksum is correct
     *
     * @param mnemonic The mnemonic phrase to validate
     * @return true if valid, false otherwise
     */
    fun validateMnemonic(mnemonic: String): Boolean

    /**
     * Extracts the raw entropy bytes from a BIP-39 mnemonic phrase.
     *
     * This is the inverse of [generateMnemonic] — given the words, it returns
     * the bit pattern they encode. Used by wallet storage to persist entropy
     * (smaller than the phrase, reproducible to the phrase on demand).
     *
     * - 12 words → 16 bytes
     * - 15 words → 20 bytes
     * - 18 words → 24 bytes
     * - 21 words → 28 bytes
     * - 24 words → 32 bytes
     *
     * @param mnemonic The BIP-39 mnemonic phrase
     * @return The raw entropy bytes
     * @throws IllegalArgumentException if mnemonic is invalid
     */
    fun mnemonicToEntropy(mnemonic: String): ByteArray

    /**
     * Reconstructs a BIP-39 mnemonic phrase from raw entropy bytes.
     *
     * This is the inverse of [mnemonicToEntropy]. Used by wallet storage
     * to show the user their recovery phrase from the persisted entropy.
     *
     * @param entropy Raw entropy (16, 20, 24, 28, or 32 bytes)
     * @return Space-separated mnemonic phrase
     * @throws IllegalArgumentException if entropy size is invalid
     */
    fun entropyToMnemonic(entropy: ByteArray): String
}
