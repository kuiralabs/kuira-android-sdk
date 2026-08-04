// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.shielded

import com.midnight.kuira.core.crypto.address.Bech32m
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [OfferFile] — the Bech32m Offer File codec.
 *
 * These are pure JVM unit tests (no native library): the codec only wraps and
 * unwraps bytes, so its correctness can be pinned down entirely here.
 */
class OfferFileTest {

    @Test
    fun `round-trips arbitrary offer bytes`() {
        val offer = ByteArray(256) { (it * 7 + 3).toByte() }

        val file = OfferFile.encode(offer)
        val decoded = OfferFile.decode(file)

        assertArrayEquals("Bytes must survive encode -> decode", offer, decoded)
    }

    @Test
    fun `file starts with the zswapoffer prefix`() {
        val file = OfferFile.encode(ByteArray(32) { it.toByte() })

        assertTrue("Offer File must be self-describing", file.startsWith("zswapoffer1"))
        assertEquals("zswapoffer", OfferFile.HRP)
    }

    @Test
    fun `round-trips a realistic ~10 KB offer and expands to ~16 KB of text`() {
        // A real 1-input/1-output Zswap offer is ~10,143 bytes; check the codec
        // handles payloads far beyond the 90-char cap of address-style bech32.
        val offer = ByteArray(10_143) { ((it * 31) xor 0x5A).toByte() }

        val file = OfferFile.encode(offer)
        val decoded = OfferFile.decode(file)

        assertArrayEquals(offer, decoded)
        // 8 bits -> 5 bits inflates length by ~1.6x, plus prefix + checksum.
        assertTrue(
            "Expected ~16k chars, got ${file.length}",
            file.length in 16_000..16_500,
        )
    }

    @Test
    fun `round-trips through the hex convenience helpers`() {
        val offerHex = "deadbeef0123456789abcdeffeedface"

        val file = OfferFile.encodeHex(offerHex)
        val back = OfferFile.decodeToHex(file)

        assertEquals(offerHex, back)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty offer bytes`() {
        OfferFile.encode(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a corrupted checksum`() {
        val file = OfferFile.encode(ByteArray(64) { it.toByte() })
        // Flip one data character; Bech32m's checksum must catch it.
        val corrupted = file.dropLast(1) + if (file.last() == 'q') 'p' else 'q'
        OfferFile.decode(corrupted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a valid bech32m string with the wrong HRP`() {
        // A well-formed address is valid Bech32m but is NOT an Offer File.
        val address = Bech32m.encode("mn_addr_preview", ByteArray(32) { it.toByte() })
        OfferFile.decode(address)
    }

    @Test
    fun `isOfferFile distinguishes offers from other text`() {
        val file = OfferFile.encode(ByteArray(48) { it.toByte() })
        val address = Bech32m.encode("mn_addr_preview", ByteArray(32) { it.toByte() })

        assertTrue(OfferFile.isOfferFile(file))
        assertTrue("Surrounding whitespace is tolerated", OfferFile.isOfferFile("  $file \n"))
        assertFalse(OfferFile.isOfferFile(address))
        assertFalse(OfferFile.isOfferFile("just a normal sentence"))
    }

    @Test
    fun `findAll extracts offers embedded in a social post`() {
        val fileA = OfferFile.encode(ByteArray(40) { it.toByte() })
        val fileB = OfferFile.encode(ByteArray(40) { (it + 100).toByte() })
        val post = "gm! selling my rock $fileA and here's another $fileB — dm me"

        val found = OfferFile.findAll(post)

        assertEquals(listOf(fileA, fileB), found)
    }

    @Test
    fun `findAll returns nothing when there is no offer`() {
        assertTrue(OfferFile.findAll("no offers here, just vibes").isEmpty())
    }
}
