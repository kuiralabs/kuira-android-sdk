// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.bip32

import com.midnight.kuira.core.crypto.bip39.BIP39
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [HDWallet] BIP-32 key derivation.
 *
 * **Test Vectors:**
 * These test vectors are derived from our Node.js reference implementation
 * using the official Midnight SDK (@midnight-ntwrk/wallet-sdk-hd).
 *
 * **Mnemonic:**
 * "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
 *
 *
 * **Expected Results (from Node.js with Lace compatibility):**
 * - Seed (64 bytes, Lace compatible): 408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf705489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840
 * - Private Key at m/44'/2400'/0'/0/0: af7a998947b1b1fd12d99cb40ee98a739e6a2518d8965690781d85ea0e3a5e13
 */
class HDWalletTest {

    /**
     * Standard BIP-39 test mnemonic (24 words).
     */
    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon art"

    /**
     * Expected seed (full 64 bytes) from BIP-39 derivation.
     * This matches our Node.js reference.
     */
    private val expectedSeedHex = "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf70" +
        "5489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840"

    /**
     * Expected private key at path m/44'/2400'/0'/0/0 (NightExternal role, index 0).
     * This is the critical test - must match Lace-compatible Node.js exactly.
     */
    private val expectedPrivateKeyHex = "af7a998947b1b1fd12d99cb40ee98a739e6a2518d8965690781d85ea0e3a5e13"

    private val walletsToClean = mutableListOf<HDWallet>()
    private val keysToClean = mutableListOf<DerivedKey>()

    @After
    fun cleanup() {
        // Clean up all keys and wallets
        keysToClean.forEach { it.clear() }
        walletsToClean.forEach { it.clear() }
        keysToClean.clear()
        walletsToClean.clear()
    }

    @Test
    fun `test BIP-39 seed generation matches reference`() {
        // Arrange & Act
        val seed = BIP39.mnemonicToSeed(testMnemonic, passphrase = "")

        // Assert - Full 64-byte PBKDF2 output (Lace compatible)
        assertEquals("Seed should be 64 bytes (Lace compatibility)", 64, seed.size)

        val seedHex = seed.joinToString("") { "%02x".format(it) }
        assertEquals(
            "Seed must match Lace-compatible Node.js reference",
            expectedSeedHex,
            seedHex
        )
    }

    @Test
    fun `test HD wallet creation from seed`() {
        // Arrange
        val seed = BIP39.mnemonicToSeed(testMnemonic)

        // Act
        val wallet = HDWallet.fromSeed(seed)
        walletsToClean.add(wallet)

        // Assert
        assertNotNull("Wallet should be created successfully", wallet)
    }

    @Test
    fun `test derive key at path m-44-2400-0-0-0 matches reference`() {
        // Arrange
        val seed = BIP39.mnemonicToSeed(testMnemonic)
        val wallet = HDWallet.fromSeed(seed)
        walletsToClean.add(wallet)

        // Act - Derive at path: m/44'/2400'/0'/0/0
        val key = wallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
            .deriveKeyAt(0)
        keysToClean.add(key)

        // Assert - Private key must match Node.js exactly
        @Suppress("DEPRECATION")
        val privateKeyHex = key.privateKeyHexDebugOnly()
        assertEquals(
            "Private key at m/44'/2400'/0'/0/0 must match Node.js reference",
            expectedPrivateKeyHex,
            privateKeyHex
        )

        // Assert - Path should be correctly formatted
        assertEquals(
            "Derivation path should be m/44'/2400'/0'/0/0",
            "m/44'/2400'/0'/0/0",
            key.path
        )

        // Assert - Public key should be compressed format (33 bytes = 66 hex chars)
        val publicKeyHex = key.publicKeyHex()
        assertEquals(
            "Public key should be 66 hex characters (33 bytes compressed)",
            66,
            publicKeyHex.length
        )
        assertTrue(
            "Public key should start with 02 or 03 (compressed format)",
            publicKeyHex.startsWith("02") || publicKeyHex.startsWith("03")
        )
    }

    @Test
    fun `test derive keys at different roles`() {
        // Arrange
        val seed = BIP39.mnemonicToSeed(testMnemonic)
        val wallet = HDWallet.fromSeed(seed)
        walletsToClean.add(wallet)
        val account = wallet.selectAccount(0)

        // Act & Assert - Test all roles
        val roles = listOf(
            MidnightKeyRole.NIGHT_EXTERNAL to "m/44'/2400'/0'/0/0",
            MidnightKeyRole.NIGHT_INTERNAL to "m/44'/2400'/0'/1/0",
            MidnightKeyRole.DUST to "m/44'/2400'/0'/2/0",
            MidnightKeyRole.ZSWAP to "m/44'/2400'/0'/3/0",
            MidnightKeyRole.METADATA to "m/44'/2400'/0'/4/0"
        )

        roles.forEach { (role, expectedPath) ->
            val key = account.selectRole(role).deriveKeyAt(0)
            keysToClean.add(key)

            assertEquals("Path should match for role ${role.name}", expectedPath, key.path)
            assertEquals("Private key should be 32 bytes", 32, key.privateKeyBytes.size)
            assertEquals("Public key should be 33 bytes (compressed)", 33, key.publicKeyBytes.size)
            assertEquals("Chain code should be 32 bytes", 32, key.chainCode.size)
        }
    }

    @Test
    fun `test derive multiple addresses within same role`() {
        // Arrange
        val seed = BIP39.mnemonicToSeed(testMnemonic)
        val wallet = HDWallet.fromSeed(seed)
        walletsToClean.add(wallet)
        val role = wallet.selectAccount(0).selectRole(MidnightKeyRole.NIGHT_EXTERNAL)

        // Act - Derive first 5 addresses
        val keys = (0 until 5).map { index ->
            role.deriveKeyAt(index).also { keysToClean.add(it) }
        }

        // Assert - All keys should be different
        @Suppress("DEPRECATION")
        val privateKeys = keys.map { it.privateKeyHexDebugOnly() }
        assertEquals("Should have 5 keys", 5, privateKeys.size)
        assertEquals("All private keys should be unique", 5, privateKeys.toSet().size)

        // Assert - Paths should increment correctly
        keys.forEachIndexed { index, key ->
            assertEquals("Path should be m/44'/2400'/0'/0/$index", "m/44'/2400'/0'/0/$index", key.path)
        }
    }

    @Test
    fun `test derive keys for multiple accounts`() {
        // Arrange
        val seed = BIP39.mnemonicToSeed(testMnemonic)
        val wallet = HDWallet.fromSeed(seed)
        walletsToClean.add(wallet)

        // Act - Derive keys for accounts 0, 1, 2
        val keys = (0..2).map { accountIndex ->
            wallet.selectAccount(accountIndex)
                .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
                .deriveKeyAt(0)
                .also { keysToClean.add(it) }
        }

        // Assert - All keys should be different
        @Suppress("DEPRECATION")
        val privateKeys = keys.map { it.privateKeyHexDebugOnly() }
        assertEquals("All account keys should be unique", 3, privateKeys.toSet().size)

        // Assert - Account index should be reflected in path
        keys.forEachIndexed { accountIndex, key ->
            assertEquals(
                "Path should include account $accountIndex",
                "m/44'/2400'/$accountIndex'/0/0",
                key.path
            )
        }
    }

    @Test
    fun `test key clearing`() {
        // Arrange
        val seed = BIP39.mnemonicToSeed(testMnemonic)
        val wallet = HDWallet.fromSeed(seed)
        walletsToClean.add(wallet)
        val key = wallet.selectAccount(0).selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(0)

        // Assert - Key should NOT be cleared initially
        assertEquals("Key should not be cleared before clear() is called", false, key.isCleared())

        // Capture original private key
        @Suppress("DEPRECATION")
        val originalPrivateKeyHex = key.privateKeyHexDebugOnly()
        assertNotNull("Private key should exist before clearing", originalPrivateKeyHex)

        // Act - Clear the key
        key.clear()

        // Assert - Key should be marked as cleared
        assertEquals("Key should be cleared after clear() is called", true, key.isCleared())

        // Assert - Accessing cleared key should throw exception
        try {
            @Suppress("DEPRECATION")
            key.privateKeyHexDebugOnly()
            // If we get here, the test should fail
            throw AssertionError("Expected IllegalStateException when accessing cleared key")
        } catch (e: IllegalStateException) {
            // Expected: accessing cleared key throws IllegalStateException
            assertEquals("Cannot access cleared key", e.message)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `test seed length less than 16 bytes throws exception`() {
        // Arrange - Create seed with length < 16 bytes
        val invalidSeed = ByteArray(15)

        // Act & Assert
        HDWallet.fromSeed(invalidSeed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `test seed length greater than 64 bytes throws exception`() {
        // Arrange - Create seed with length > 64 bytes
        val invalidSeed = ByteArray(65)

        // Act & Assert
        HDWallet.fromSeed(invalidSeed)
    }

    @Test
    fun `test valid seed lengths from 16 to 64 bytes work`() {
        // Test minimum length (16 bytes)
        val seed16 = ByteArray(16) { it.toByte() }
        val wallet16 = HDWallet.fromSeed(seed16)
        walletsToClean.add(wallet16)
        assertNotNull("Wallet should be created with 16-byte seed", wallet16)

        // Test middle length (32 bytes)
        val seed32 = ByteArray(32) { it.toByte() }
        val wallet32 = HDWallet.fromSeed(seed32)
        walletsToClean.add(wallet32)
        assertNotNull("Wallet should be created with 32-byte seed", wallet32)

        // Test maximum length (64 bytes) - this is BIP-39 standard
        val seed64 = BIP39.mnemonicToSeed(testMnemonic)
        val wallet64 = HDWallet.fromSeed(seed64)
        walletsToClean.add(wallet64)
        assertNotNull("Wallet should be created with 64-byte seed", wallet64)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `test negative account index throws exception`() {
        // Arrange
        val seed = BIP39.mnemonicToSeed(testMnemonic)
        val wallet = HDWallet.fromSeed(seed)
        walletsToClean.add(wallet)

        // Act & Assert
        wallet.selectAccount(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `test negative address index throws exception`() {
        // Arrange
        val seed = BIP39.mnemonicToSeed(testMnemonic)
        val wallet = HDWallet.fromSeed(seed)
        walletsToClean.add(wallet)
        val role = wallet.selectAccount(0).selectRole(MidnightKeyRole.NIGHT_EXTERNAL)

        // Act & Assert
        role.deriveKeyAt(-1)
    }
}
