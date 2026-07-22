// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.dust

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

/**
 * Unit tests for [DustKeyDeriver] JNI wrapper.
 *
 * **Note:** These tests run WITHOUT the native library loaded (JVM unit tests).
 * For integration tests WITH the native library, see:
 * - Android instrumentation tests (`androidTest/`)
 * - Integration tests with actual dust key derivation
 *
 * **Test Vectors Reference:**
 * - the Midnight TypeScript SDK
 * - TypeScript SDK: packages/dust-wallet/
 */
class DustKeyDeriverTest {

    /**
     * Test Case 1: Library not loaded in unit tests
     */
    @Test
    fun `when checking library loaded status then returns false in unit tests`() {
        // Native library should not be loaded in JVM unit tests
        assertFalse(DustKeyDeriver.isLibraryLoaded())
    }

    /**
     * Test Case 2: Get load error message
     */
    @Test
    fun `when getting load error then returns non-null error message`() {
        // Should have an error message explaining why it's not loaded
        val error = DustKeyDeriver.getLoadError()

        assertNotNull(error)
        assertTrue(error!!.contains("kuira_crypto_ffi"))
    }

    /**
     * Test Case 3: Derivation returns null without native library
     */
    @Test
    fun `given 32-byte seed when deriving without native library then returns null`() {
        // All zeros seed (32 bytes)
        val seed = ByteArray(32) { 0 }

        // Should return null because native library not loaded in unit tests
        val publicKey = DustKeyDeriver.derivePublicKey(seed)

        assertNull("Public key should be null when library not loaded", publicKey)
    }

    /**
     * Test Case 4: Seed not modified by derivation
     */
    @Test
    fun `given seed when deriving then does not modify seed array`() {
        val originalSeed = ByteArray(32) { it.toByte() }
        val seedCopy = originalSeed.copyOf()

        DustKeyDeriver.derivePublicKey(originalSeed)

        // Seed should NOT be modified by derivePublicKey()
        assertArrayEquals("Seed should not be modified", seedCopy, originalSeed)
    }

    /**
     * Test Case 5: Invalid seed length - empty
     */
    @Test(expected = IllegalArgumentException::class)
    fun `given empty seed when deriving then throws`() {
        val seed = ByteArray(0)

        DustKeyDeriver.derivePublicKey(seed)
    }

    /**
     * Test Case 6: Invalid seed length - too short
     */
    @Test(expected = IllegalArgumentException::class)
    fun `given 16-byte seed when deriving then throws`() {
        val seed = ByteArray(16) { it.toByte() }

        DustKeyDeriver.derivePublicKey(seed)
    }

    /**
     * Test Case 7: Invalid seed length - too long
     */
    @Test(expected = IllegalArgumentException::class)
    fun `given 64-byte seed when deriving then throws`() {
        val seed = ByteArray(64) { it.toByte() } // BIP-39 seed size, not dust seed

        DustKeyDeriver.derivePublicKey(seed)
    }

    /**
     * Test Case 8: Error message validation
     */
    @Test
    fun `given invalid seed length when deriving then error mentions 32 bytes`() {
        val seed = ByteArray(16)

        try {
            DustKeyDeriver.derivePublicKey(seed)
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Error message should mention the expected size
            assertTrue(
                "Error message should mention '32': ${e.message}",
                e.message!!.contains("32")
            )
            assertTrue(
                "Error message should mention actual size '16': ${e.message}",
                e.message!!.contains("16")
            )
        }
    }
}
