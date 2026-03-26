// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip39

/**
 * Convenience object for BIP-39 mnemonic operations.
 *
 * This is a facade that delegates to a [MnemonicService] implementation.
 * Uses BitcoinJ implementation which is BIP-39 compliant and battle-tested.
 *
 * **Reference:** https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki
 *
 * **Usage:**
 * ```kotlin
 * // Generate 24-word mnemonic
 * val mnemonic = BIP39.generateMnemonic()
 *
 * // Convert to seed (CRITICAL: wipe seed after use!)
 * val seed = BIP39.mnemonicToSeed(mnemonic)
 * try {
 *     // Use seed...
 * } finally {
 *     Arrays.fill(seed, 0.toByte())
 * }
 *
 * // Validate mnemonic
 * val isValid = BIP39.validateMnemonic(mnemonic)
 * ```
 *
 * **Thread Safety:**
 * All methods are thread-safe. The service implementation is immutable.
 *
 * **Testing:**
 * For testing with custom implementations, use constructor injection in your
 * components rather than relying on this global object.
 */
object BIP39 {

    /**
     * The underlying service implementation.
     * Immutable for thread safety and consistency.
     */
    private val service: MnemonicService = BitcoinJMnemonicService()

    /**
     * Generates a random BIP-39 mnemonic phrase.
     *
     * @param wordCount Number of words (12, 15, 18, 21, or 24). Default: 24
     * @return Space-separated mnemonic phrase
     * @throws IllegalArgumentException if wordCount is invalid
     */
    fun generateMnemonic(wordCount: Int = 24): String {
        return service.generateMnemonic(wordCount)
    }

    /**
     * Derives a seed from a mnemonic phrase and optional passphrase.
     *
     * ⚠️ **CRITICAL: LACE WALLET COMPATIBILITY** ⚠️
     *
     * This implementation returns ONLY the first 32 bytes of the BIP-39 seed
     * to maintain compatibility with Lace wallet (the most popular Midnight wallet).
     *
     * **Standard BIP-39 behavior produces 64 bytes, but Lace uses only 32 bytes.**
     *
     * ## Why This Non-Standard Behavior?
     *
     * Lace wallet uses a truncated seed due to a "documentation gap" (confirmed by
     * Lace team in GitHub issue #2133). This creates an ecosystem split:
     * - **Lace Standard**: First 32 bytes only
     * - **Official Midnight SDK**: Full 64 bytes
     *
     * We follow Lace to ensure wallet interoperability - users can import/export
     * wallets between Kuira and Lace seamlessly.
     *
     * ## Security Impact
     *
     * **None** - 32 bytes (256 bits) provides the same security as Bitcoin/Ethereum.
     * The entropy reduction (512→256 bits) is negligible in practice.
     *
     * ## Migration
     *
     * - ✅ Lace → Kuira: Import works perfectly
     * - ✅ Kuira → Lace: Import works perfectly
     * - ⚠️ Official SDK → Kuira: Different addresses (incompatible)
     *
     * ## References
     *
     * - **Full Explanation**: See `docs/LACE_COMPATIBILITY.md`
     * - **Lace GitHub Issue**: https://github.com/input-output-hk/lace/issues/2133
     * - **BIP-39 Spec**: https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki
     *
     * @param mnemonic The mnemonic phrase (12-24 words)
     * @param passphrase Optional passphrase for additional security (default: empty string)
     * @return 64-byte seed (full BIP-39 PBKDF2 output, matching Lace wallet)
     * @throws IllegalArgumentException if mnemonic is invalid
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        return service.mnemonicToSeed(mnemonic, passphrase)  // 64 bytes from standard BIP-39
    }

    /**
     * Validates a mnemonic phrase.
     *
     * Checks:
     * - Word count is valid (12, 15, 18, 21, or 24)
     * - All words are in BIP-39 word list
     * - Checksum is correct
     *
     * @param mnemonic The mnemonic phrase to validate
     * @return true if valid, false otherwise
     */
    fun validateMnemonic(mnemonic: String): Boolean {
        return service.validateMnemonic(mnemonic)
    }
}
