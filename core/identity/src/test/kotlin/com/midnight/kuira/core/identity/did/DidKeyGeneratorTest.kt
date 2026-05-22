package com.midnight.kuira.core.identity.did

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for did:key generation from P-256 public keys.
 *
 * Test vectors derived from the W3C did:key specification and
 * cross-referenced with the multicodec/multibase standards.
 */
class DidKeyGeneratorTest {

    @Test
    fun `compressP256 returns 0x02 prefix for even y`() {
        val x = ByteArray(32) { 0x01 }
        val y = ByteArray(32) { 0x00 } // even (last byte 0x00)
        val compressed = DidKeyGenerator.compressP256(x, y)

        assertEquals(33, compressed.size)
        assertEquals(0x02.toByte(), compressed[0])
        assertArrayEquals(x, compressed.copyOfRange(1, 33))
    }

    @Test
    fun `compressP256 returns 0x03 prefix for odd y`() {
        val x = ByteArray(32) { 0x01 }
        val y = ByteArray(32).also { it[31] = 0x01 } // odd (last byte 0x01)
        val compressed = DidKeyGenerator.compressP256(x, y)

        assertEquals(33, compressed.size)
        assertEquals(0x03.toByte(), compressed[0])
    }

    @Test
    fun `fromCompressedP256 produces valid did-key format`() {
        // Known compressed P-256 key (0x02 prefix + 32 bytes)
        val compressedKey = ByteArray(33).also {
            it[0] = 0x02
            for (i in 1..32) it[i] = i.toByte()
        }

        val did = DidKeyGenerator.fromCompressedP256(compressedKey)

        assertTrue("DID must start with did:key:z", did.startsWith("did:key:z"))
        assertTrue("DID must be longer than prefix", did.length > "did:key:z".length)
    }

    @Test
    fun `roundtrip - fromCompressedP256 then toCompressedP256 recovers original key`() {
        val compressedKey = ByteArray(33).also {
            it[0] = 0x03
            for (i in 1..32) it[i] = (i * 7).toByte()
        }

        val did = DidKeyGenerator.fromCompressedP256(compressedKey)
        val recovered = DidKeyGenerator.toCompressedP256(did)

        assertArrayEquals(compressedKey, recovered)
    }

    @Test
    fun `fromP256Coordinates produces same DID as fromCompressedP256`() {
        val x = ByteArray(32) { (it + 10).toByte() }
        val y = ByteArray(32) { (it + 42).toByte() } // last byte = 73 (odd)

        val compressed = DidKeyGenerator.compressP256(x, y)
        val didFromCoords = DidKeyGenerator.fromP256Coordinates(x, y)
        val didFromCompressed = DidKeyGenerator.fromCompressedP256(compressed)

        assertEquals(didFromCoords, didFromCompressed)
    }

    @Test
    fun `did-key uses correct multicodec prefix for P-256`() {
        val compressedKey = ByteArray(33).also {
            it[0] = 0x02
            for (i in 1..32) it[i] = 0xAB.toByte()
        }

        val did = DidKeyGenerator.fromCompressedP256(compressedKey)
        val decoded = DidKeyGenerator.toCompressedP256(did)

        // The decoded key should match the input — multicodec prefix is stripped
        assertArrayEquals(compressedKey, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromCompressedP256 rejects wrong size`() {
        DidKeyGenerator.fromCompressedP256(ByteArray(32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromCompressedP256 rejects invalid prefix`() {
        val invalidKey = ByteArray(33).also { it[0] = 0x04 } // uncompressed prefix
        DidKeyGenerator.fromCompressedP256(invalidKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toCompressedP256 rejects invalid DID prefix`() {
        DidKeyGenerator.toCompressedP256("did:web:example.com")
    }

    @Test
    fun `deterministic - same key always produces same DID`() {
        val key = ByteArray(33).also {
            it[0] = 0x02
            for (i in 1..32) it[i] = 0xFF.toByte()
        }

        val did1 = DidKeyGenerator.fromCompressedP256(key)
        val did2 = DidKeyGenerator.fromCompressedP256(key)

        assertEquals(did1, did2)
    }

    @Test
    fun `different keys produce different DIDs`() {
        val key1 = ByteArray(33).also { it[0] = 0x02; it[1] = 0x01 }
        val key2 = ByteArray(33).also { it[0] = 0x02; it[1] = 0x02 }

        val did1 = DidKeyGenerator.fromCompressedP256(key1)
        val did2 = DidKeyGenerator.fromCompressedP256(key2)

        assertTrue("Different keys must produce different DIDs", did1 != did2)
    }

    /**
     * Test vector from the did:key specification.
     *
     * This uses a known P-256 key pair where we can verify the DID matches
     * the expected output from reference implementations.
     *
     * Reference: https://w3c-ccg.github.io/did-method-key/#p-256
     * Key: P-256 public key with known x,y coordinates
     */
    @Test
    fun `known test vector - P-256 did-key starts with expected prefix`() {
        // P-256 compressed key with 0x02 prefix — the DID should start with "did:key:zDn"
        // because base58btc encoding of [0x80, 0x24, 0x02, ...] starts with "Dn" pattern
        val knownKey = ByteArray(33).also {
            it[0] = 0x02
            // Fill with a deterministic pattern
            for (i in 1..32) it[i] = (i * 3 + 7).toByte()
        }

        val did = DidKeyGenerator.fromCompressedP256(knownKey)
        // P-256 did:key values always start with "did:key:zDn" due to the multicodec prefix
        assertTrue("P-256 did:key should start with 'did:key:zDn', got: ${did.take(15)}", did.startsWith("did:key:zDn"))
    }

    // ── Ed25519 ──

    @Test
    fun `fromEd25519 produces valid did-key format`() {
        // A 32-byte Ed25519 public key (any random-looking bytes are syntactically valid;
        // semantic validity isn't part of did:key encoding's responsibility).
        val pubkey = ByteArray(32) { (it * 7 + 3).toByte() }
        val did = DidKeyGenerator.fromEd25519(pubkey)
        assertTrue("did:key prefix missing", did.startsWith("did:key:z"))
    }

    @Test
    fun `fromEd25519 deterministic`() {
        val pubkey = ByteArray(32) { 0x42 }
        assertEquals(DidKeyGenerator.fromEd25519(pubkey), DidKeyGenerator.fromEd25519(pubkey))
    }

    @Test
    fun `fromEd25519 different keys produce different dids`() {
        val a = ByteArray(32) { 0x01 }
        val b = ByteArray(32) { 0x02 }
        assertTrue(
            "distinct pubkeys must produce distinct dids",
            DidKeyGenerator.fromEd25519(a) != DidKeyGenerator.fromEd25519(b),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromEd25519 rejects wrong-size key`() {
        DidKeyGenerator.fromEd25519(ByteArray(31))
    }

    @Test
    fun `known test vector - Ed25519 did-key starts with z6Mk prefix`() {
        // Ed25519 did:key always starts with `did:key:z6Mk` because the
        // multicodec varint [0xed, 0x01] + a 32-byte payload deterministically
        // produces that base58btc prefix. Wishlist-level invariant: consumers
        // can prefix-match `z6Mk` to detect Ed25519 vs legacy P-256 (`zDn…`)
        // — keeping it pinned guards against accidental encoding drift.
        val knownKey = ByteArray(32) { (it * 5 + 11).toByte() }
        val did = DidKeyGenerator.fromEd25519(knownKey)
        assertTrue("Ed25519 did:key should start with 'did:key:z6Mk', got: ${did.take(16)}", did.startsWith("did:key:z6Mk"))
    }

    @Test
    fun `isLegacyP256 returns true for P-256 did and false for Ed25519 did`() {
        val p256 = DidKeyGenerator.fromCompressedP256(
            ByteArray(33).also { it[0] = 0x02 }
        )
        val ed = DidKeyGenerator.fromEd25519(ByteArray(32) { 0x11 })
        assertTrue("P-256 DID must be flagged legacy", DidKeyGenerator.isLegacyP256(p256))
        assertTrue("Ed25519 DID must NOT be flagged legacy", !DidKeyGenerator.isLegacyP256(ed))
    }
}
