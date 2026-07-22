// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.address

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Bech32m] encoder/decoder.
 *
 * **Test Vectors:**
 * These test vectors are from the Midnight SDK (@midnight-ntwrk/wallet-sdk-address-format)
 * to ensure 100% compatibility.
 *
 * **Reference:**
 * - BIP-350 test vectors: https://github.com/bitcoin/bips/blob/master/bip-0350.mediawiki
 * - Midnight SDK: the Midnight TypeScript SDK
 */
class Bech32mTest {

    /**
     * Test basic Bech32m encoding/decoding round-trip.
     */
    @Test
    fun `test basic Bech32m round-trip`() {
        val testCases = listOf(
            "test" to ByteArray(20) { it.toByte() },
            "example" to ByteArray(32) { it.toByte() },
            "midnight" to ByteArray(64) { (it * 2).toByte() }
        )

        testCases.forEach { (hrp, data) ->
            val encoded = Bech32m.encode(hrp, data)
            val (decodedHrp, decodedData) = Bech32m.decode(encoded)

            assertEquals("HRP should match", hrp, decodedHrp)
            assertArrayEquals("Data should match after round-trip", data, decodedData)
        }
    }

    /**
     * Test Midnight SDK shielded coin public key encoding.
     * From: the Midnight TypeScript SDK
     */
    @Test
    fun `test Midnight SDK shielded coin public key encoding`() {
        // From addresses.json entry 0 (mainnet):
        // "shieldedCPK": {
        //  "hex": "064e092a80b33bee23404c46cfc48fec75a2356a9b01178dd6a62c29f5896f67",
        //  "bech32m": "mn_shield-cpk1qe8qj25qkva7ug6qf3rvl3y0a366ydt2nvq30rwk5ckznavfdans20del8"
        // }

        val hex = "064e092a80b33bee23404c46cfc48fec75a2356a9b01178dd6a62c29f5896f67"
        val expectedBech32m = "mn_shield-cpk1qe8qj25qkva7ug6qf3rvl3y0a366ydt2nvq30rwk5ckznavfdans20del8"

        val data = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Encode
        val encoded = Bech32m.encode("mn_shield-cpk", data)

        assertEquals("Encoded address must match Midnight SDK", expectedBech32m, encoded)

        // Decode and verify round-trip
        val (hrp, decoded) = Bech32m.decode(expectedBech32m)

        assertEquals("HRP should match", "mn_shield-cpk", hrp)
        assertArrayEquals("Decoded data should match original", data, decoded)
    }

    /**
     * Test Midnight SDK shielded coin public key with custom network.
     * From: addresses.json entry 1 (my-private-net):
     */
    @Test
    fun `test Midnight SDK shielded coin public key with network`() {
        // From addresses.json entry 1 (my-private-net):
        // "shieldedCPK": {
        //  "hex": "064e092a80b33bee23404c46cfc48fec75a2356a9b01178dd6a62c29f5896f67",
        //  "bech32m": "mn_shield-cpk_my-private-net1qe8qj25qkva7ug6qf3rvl3y0a366ydt2nvq30rwk5ckznavfdansj6mrqy"
        // }

        val hex = "064e092a80b33bee23404c46cfc48fec75a2356a9b01178dd6a62c29f5896f67"
        val expectedBech32m = "mn_shield-cpk_my-private-net1qe8qj25qkva7ug6qf3rvl3y0a366ydt2nvq30rwk5ckznavfdansj6mrqy"

        val data = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Encode
        val encoded = Bech32m.encode("mn_shield-cpk_my-private-net", data)

        assertEquals("Encoded address with network must match Midnight SDK", expectedBech32m, encoded)

        // Decode and verify round-trip
        val (hrp, decoded) = Bech32m.decode(expectedBech32m)

        assertEquals("HRP should include network", "mn_shield-cpk_my-private-net", hrp)
        assertArrayEquals("Decoded data should match original", data, decoded)
    }

    /**
     * Test encoding with various data lengths (32 bytes is common for Midnight addresses).
     */
    @Test
    fun `test encoding different data lengths`() {
        val testCases = listOf(
            "mn_test" to ByteArray(20) { it.toByte() }, // 20 bytes
            "mn_test" to ByteArray(32) { it.toByte() }, // 32 bytes (Midnight address length)
            "mn_test" to ByteArray(64) { it.toByte() } // 64 bytes
        )

        testCases.forEach { (hrp, data) ->
            // Encode
            val encoded = Bech32m.encode(hrp, data)

            // Decode and verify round-trip
            val (decodedHrp, decodedData) = Bech32m.decode(encoded)

            assertEquals("HRP should match", hrp, decodedHrp)
            assertArrayEquals("Data should match after round-trip (${data.size} bytes)", data, decodedData)
        }
    }

    /**
     * Test that invalid checksums are rejected.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `test invalid checksum throws exception`() {
        // Valid address with corrupted checksum
        val invalidAddress = "mn_shield-cpk1qe8qj25qkva7ug6qf3rvl3y0a366ydt2nvq30rwk5ckznavfdansxxxxxx"
        Bech32m.decode(invalidAddress)
    }

    /**
     * Test that mixed case throws exception.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `test mixed case throws exception`() {
        val mixedCaseAddress = "Mn_shield-cpk1QE8QJ25QKVA7UG6QF3RVL3Y0A366YDT2NVQ30RWK5CKZNAVFDANS20DEL8"
        Bech32m.decode(mixedCaseAddress)
    }

    /**
     * Test that empty HRP throws exception.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `test empty HRP throws exception`() {
        val data = ByteArray(32) { 0 }
        Bech32m.encode("", data)
    }

    /**
     * Test case insensitivity (both lowercase and uppercase should work).
     */
    @Test
    fun `test case insensitivity`() {
        val lowercase = "mn_shield-cpk1qe8qj25qkva7ug6qf3rvl3y0a366ydt2nvq30rwk5ckznavfdans20del8"
        val uppercase = lowercase.uppercase()

        val (hrpLower, dataLower) = Bech32m.decode(lowercase)
        val (hrpUpper, dataUpper) = Bech32m.decode(uppercase)

        assertEquals("HRP should be lowercase", hrpLower.lowercase(), hrpLower)
        assertEquals("HRP should be lowercase", hrpUpper.lowercase(), hrpUpper)
        assertEquals("HRP should match", hrpLower, hrpUpper)
        assertArrayEquals("Data should match regardless of case", dataLower, dataUpper)
    }
}
