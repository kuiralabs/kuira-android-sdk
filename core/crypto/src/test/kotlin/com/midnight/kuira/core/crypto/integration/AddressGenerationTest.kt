// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.integration

import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Arrays

/**
 * Integration test for generating Midnight blockchain addresses.
 *
 * **Purpose:**
 * - Generate a complete unshielded address from mnemonic
 * - Print address for manual verification (send Night tokens to it)
 * - Verify the address format matches Midnight SDK
 *
 * **Midnight Unshielded Address Format:**
 * ```
 * Mnemonic (BIP-39)
 *   → Seed (64 bytes)
 *   → HD Wallet (BIP-32)
 *   → Private Key at m/44'/2400'/0'/0/0 (32 bytes)
 *   → Public Key - BIP-340 format (x-only, 32 bytes)
 *   → Bech32m encoding: mn_addr_[network]1...
 * ```
 *
 * **Reference:**
 * - Midnight SDK: @midnight-ntwrk/wallet-sdk-address-format
 * - BIP-340: Schnorr signatures (x-only public keys)
 */
class AddressGenerationTest {

    private val walletsToClean = mutableListOf<HDWallet>()

    @After
    fun cleanup() {
        walletsToClean.forEach { it.clear() }
        walletsToClean.clear()
    }

    /**
     * Generates an unshielded address from a test mnemonic.
     */
    @Test
    fun `generate unshielded address from test mnemonic`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        val seed = BIP39.mnemonicToSeed(mnemonic, passphrase = "")
        try {
            val wallet = HDWallet.fromSeed(seed)
            walletsToClean.add(wallet)

            val derivedKey = wallet
                .selectAccount(0)
                .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
                .deriveKeyAt(0)

            val compressedPublicKey = derivedKey.publicKeyBytes
            require(compressedPublicKey.size == 33)
            require(compressedPublicKey[0] == 0x02.toByte() || compressedPublicKey[0] == 0x03.toByte())

            val xOnlyPublicKey = compressedPublicKey.copyOfRange(1, 33)
            val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPublicKey)

            val previewAddress = Bech32m.encode("mn_addr_preview", addressData)
            val mainnetAddress = Bech32m.encode("mn_addr", addressData)

            // Verify against known test vectors (Lace compatibility)
            // Address generated from Lace-compatible 64-byte seed
            assertEquals(
                "mn_addr_preview19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpq8xczf2",
                previewAddress
            )

            derivedKey.clear()
        } finally {
            Arrays.fill(seed, 0.toByte())
        }
    }

    /**
     * Generates an unshielded address from a NEW random mnemonic.
     */
    @Test
    fun `generate unshielded address from new random mnemonic`() {
        val mnemonic = BIP39.generateMnemonic(wordCount = 24)
        val seed = BIP39.mnemonicToSeed(mnemonic, passphrase = "")
        try {
            val wallet = HDWallet.fromSeed(seed)
            walletsToClean.add(wallet)

            val derivedKey = wallet
                .selectAccount(0)
                .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
                .deriveKeyAt(0)

            val compressedPublicKey = derivedKey.publicKeyBytes
            val xOnlyPublicKey = compressedPublicKey.copyOfRange(1, 33)
            val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPublicKey)
            val previewAddress = Bech32m.encode("mn_addr_preview", addressData)

            // Verify address format
            assertTrue(previewAddress.startsWith("mn_addr_preview1"))
            assertTrue(previewAddress.length > 50)

            derivedKey.clear()
        } finally {
            Arrays.fill(seed, 0.toByte())
        }
    }

    /**
     * Generates multiple addresses from the same mnemonic.
     */
    @Test
    fun `generate multiple addresses from same mnemonic`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        val seed = BIP39.mnemonicToSeed(mnemonic, passphrase = "")
        try {
            val wallet = HDWallet.fromSeed(seed)
            walletsToClean.add(wallet)

            val role = wallet.selectAccount(0).selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
            val addresses = mutableSetOf<String>()

            (0 until 5).forEach { index ->
                val derivedKey = role.deriveKeyAt(index)
                val compressedPublicKey = derivedKey.publicKeyBytes
                val xOnlyPublicKey = compressedPublicKey.copyOfRange(1, 33)
                val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPublicKey)
                val address = Bech32m.encode("mn_addr_preview", addressData)

                // Verify each address is unique
                assertTrue("Address at index $index should be unique", addresses.add(address))
                assertTrue(address.startsWith("mn_addr_preview1"))

                derivedKey.clear()
            }

            // Verify we generated 5 unique addresses
            assertEquals(5, addresses.size)

        } finally {
            Arrays.fill(seed, 0.toByte())
        }
    }

    /**
     * Generates addresses for local testing (undeployed network).
     *
     * Check balance: node check-balance-local.mjs <address>
     */
    @Test
    fun `generate address for local testing`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        val seed = BIP39.mnemonicToSeed(mnemonic, passphrase = "")
        try {
            val wallet = HDWallet.fromSeed(seed)
            walletsToClean.add(wallet)

            val derivedKey = wallet
                .selectAccount(0)
                .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
                .deriveKeyAt(0)

            val compressedPublicKey = derivedKey.publicKeyBytes
            val xOnlyPublicKey = compressedPublicKey.copyOfRange(1, 33)
            val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPublicKey)

            val testAddress = Bech32m.encode("mn_addr_test", addressData)
            val undeployedAddress = Bech32m.encode("mn_addr_undeployed", addressData)
            val previewAddress = Bech32m.encode("mn_addr_preview", addressData)

            val seedHex = seed.joinToString("") { "%02x".format(it) }
            println("\nSeed (64 bytes - Lace compatible):")
            println("  $seedHex")
            println()
            println("Addresses:")
            println("  Undeployed: $undeployedAddress")
            println("  Test:       $testAddress")
            println("  Preview:    $previewAddress")

            // Verify known addresses (Lace compatibility - 64-byte seed)
            assertEquals(
                "mn_addr_test19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqvayl85",
                testAddress
            )
            assertTrue(
                "Undeployed address should have correct prefix",
                undeployedAddress.startsWith("mn_addr_undeployed1")
            )
            assertEquals(
                "mn_addr_preview19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpq8xczf2",
                previewAddress
            )

            derivedKey.clear()
        } finally {
            Arrays.fill(seed, 0.toByte())
        }
    }
}
