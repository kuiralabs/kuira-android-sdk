// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.shielded

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⚠️ CRITICAL: LACE WALLET COMPATIBILITY TESTS ⚠️
 *
 * These tests verify that Kuira Wallet generates IDENTICAL addresses to Lace wallet
 * for the same mnemonic phrase.
 *
 * ## Why This Matters
 *
 * Lace is the most popular Midnight wallet. Users expect to be able to:
 * - Export their mnemonic from Lace → Import into Kuira → Get same addresses
 * - Export their mnemonic from Kuira → Import into Lace → Get same addresses
 *
 * ## The Non-Standard Behavior
 *
 * Lace now uses the full 64-byte BIP-39 seed (PBKDF2 output).
 * Our implementation matches this behavior for compatibility.
 *
 * ## Test Vectors Source
 *
 * These test vectors were generated using:
 * 1. The official Lace wallet (https://www.lace.io/)
 * 2. Midnight TypeScript SDK with full 64-byte seed
 * 3. Verification script: `generate-lace-addresses-all-networks.mjs` (see kuira-verification-test repo)
 *
 * ## References
 *
 * - **Full Documentation**: internal design notes
 * - **Lace GitHub Issue**: https://github.com/input-output-hk/lace/issues/2133
 * - **Test Mnemonic**: "abandon abandon abandon... art" (BIP-39 standard test mnemonic)
 *
 * @see com.midnight.kuira.core.crypto.bip39.BIP39.mnemonicToSeed
 */
@RunWith(AndroidJUnit4::class)
class LaceCompatibilityTest {

    @Before
    fun setUp() {
        assumeTrue(
            "Native library not loaded - skipping Lace compatibility tests",
            ShieldedKeyDeriver.isLibraryLoaded()
        )
    }

    /**
     * Verifies that using the standard BIP-39 test mnemonic produces addresses
     * that match Lace wallet EXACTLY.
     *
     * **Test Mnemonic**: "abandon abandon abandon... art"
     * **Source**: BIP-39 specification (standard test vector)
     * **Verification**: Confirmed with actual Lace wallet
     */
    @Test
    fun verifyLaceCompatibility_PreviewNetwork() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        // Verify seed is 64 bytes (full PBKDF2 output, Lace compatible)
        assertEquals("Seed should be 64 bytes for Lace compatibility", 64, bip39Seed.size)

        MemoryUtils.useAndWipe(bip39Seed) { seed ->
            val hdWallet = HDWallet.fromSeed(seed)
            try {
                val shieldedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(shieldedKey.privateKeyBytes)
                    assertNotNull(shieldedKeys)

                    val coinPkBytes = hexToBytes(shieldedKeys!!.coinPublicKey)
                    val encPkBytes = hexToBytes(shieldedKeys.encryptionPublicKey)
                    val fullAddressBytes = coinPkBytes + encPkBytes

                    val address = Bech32m.encode("mn_shield-addr_preview", fullAddressBytes)

                    // Expected address from Lace wallet
                    val laceAddress = "mn_shield-addr_preview1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2cy9mucu"

                    assertEquals("Address MUST match Lace wallet", laceAddress, address)

                    println("Lace compatibility verified for preview network")

                } finally {
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }
    }

    /**
     * Verifies compatibility across ALL major Midnight networks.
     *
     * This ensures that Kuira-generated wallets work seamlessly with Lace
     * regardless of which network the user is on.
     */
    @Test
    fun verifyLaceCompatibility_AllNetworks() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val bip39Seed = BIP39.mnemonicToSeed(mnemonic)

        val expectedAddresses = mapOf(
            "test" to "mn_shield-addr_test1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2cyckupm",
            "dev" to "mn_shield-addr_dev1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2czvgsxd",
            "preview" to "mn_shield-addr_preview1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2cy9mucu",
            "undeployed" to "mn_shield-addr_undeployed1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2czvqdfx",
            "mainnet" to "mn_shield-addr1jsy2ala7ahrtndz7r0xxy8g6yulmvlmhmclkt0amrq2dsnutv5j08tnsd0egept2gpmfpdrgpqd87ksj8efr2qdknapet27d0cvsx2ckxvxuk"
        )

        MemoryUtils.useAndWipe(bip39Seed) { seed ->
            val hdWallet = HDWallet.fromSeed(seed)
            try {
                val shieldedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)

                try {
                    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(shieldedKey.privateKeyBytes)
                    assertNotNull(shieldedKeys)

                    val coinPkBytes = hexToBytes(shieldedKeys!!.coinPublicKey)
                    val encPkBytes = hexToBytes(shieldedKeys.encryptionPublicKey)
                    val fullAddressBytes = coinPkBytes + encPkBytes

                    expectedAddresses.forEach { (network, expectedAddress) ->
                        val hrp = if (network == "mainnet") {
                            "mn_shield-addr"
                        } else {
                            "mn_shield-addr_$network"
                        }

                        val address = Bech32m.encode(hrp, fullAddressBytes)

                        assertEquals(
                            "Address for $network MUST match Lace",
                            expectedAddress,
                            address
                        )

                    }

                } finally {
                    shieldedKey.clear()
                }
            } finally {
                hdWallet.clear()
            }
        }
    }

    /**
     * This test verifies that BIP39.mnemonicToSeed() returns the full 64-byte
     * PBKDF2 output, matching standard BIP-39 and Lace wallet behavior.
     */
    @Test
    fun verifyFullSeedMatchesStandardBIP39() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"

        // Standard BIP-39 via BitcoinJMnemonicService: 64 bytes
        val service = com.midnight.kuira.core.crypto.bip39.BitcoinJMnemonicService()
        val standardSeed = service.mnemonicToSeed(mnemonic, "")

        assertEquals("Standard BIP-39 produces 64 bytes", 64, standardSeed.size)

        // Our BIP39.mnemonicToSeed() should also return 64 bytes
        val ourSeed = BIP39.mnemonicToSeed(mnemonic)
        assertEquals("Our implementation produces 64 bytes", 64, ourSeed.size)

        // Both should be identical (full PBKDF2 output)
        assertArrayEquals(
            "BIP39.mnemonicToSeed() should match standard BIP-39 output",
            standardSeed,
            ourSeed
        )

        // BIP39.mnemonicToSeed() returns full 64-byte PBKDF2 output
    }

    // Helper function
    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}
