package com.midnight.kuira.core.testing

/**
 * Test fixtures and utilities for Kuira Wallet tests.
 *
 * Following BIP-39 and BIP-32 test vectors for crypto testing.
 */
object TestFixtures {
    /**
     * Standard BIP-39 test mnemonic (24 words)
     * From official Trezor test vectors: https://github.com/trezor/python-mnemonic/blob/master/vectors.json
     * Entropy: all zeros (0x0000...0000)
     */
    const val TEST_MNEMONIC_24_WORDS =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon abandon art"

    /**
     * Expected seed for TEST_MNEMONIC_24_WORDS with passphrase "TREZOR"
     * (Official Trezor test vectors use "TREZOR" passphrase for all vectors)
     *
     * Full 64-byte PBKDF2 output (Lace compatible).
     * See docs/LACE_COMPATIBILITY.md for full explanation.
     */
    const val EXPECTED_SEED_TREZOR_PASSPHRASE =
        "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd30971" +
        "70af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8"

    /**
     * Expected seed for TEST_MNEMONIC_24_WORDS with empty passphrase ("")
     * (Most wallets use empty passphrase by default)
     *
     * Full 64-byte PBKDF2 output (Lace compatible).
     * See docs/LACE_COMPATIBILITY.md for full explanation.
     */
    const val EXPECTED_SEED_EMPTY_PASSPHRASE =
        "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf70" +
        "5489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840"

    /**
     * Placeholder test address format (Bech32m for Midnight)
     * Format: mn_addr_[network][checksum]...
     */
    const val TEST_ADDRESS = "mn_addr_testnet1qpzry9x8gf2tvdw0s3jn54khce6mua7l"
}
