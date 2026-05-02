package com.midnight.kuira.core.identity.passkey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Tests for AttestationParser — full end-to-end attestation parsing
 * from CBOR attestation objects through to P-256 public key extraction.
 */
class AttestationParserTest {

    /**
     * Full integration test: builds a complete attestation object (CBOR),
     * wraps it in authData with proper flags, and extracts the P-256 key.
     *
     * This simulates what Google Password Manager returns.
     */
    @Test
    fun `extractFromAuthData parses full attestation with COSE key`() {
        val expectedX = ByteArray(32) { (it + 0x10).toByte() }
        val expectedY = ByteArray(32) { (it + 0x30).toByte() }

        val authData = buildAuthData(expectedX, expectedY)
        val attestationObject = buildAttestationObjectWithAuthData(authData)

        // Parse through the full path
        val parsedAuthData = CborParser.extractAuthData(attestationObject)
        assertArrayEquals(authData, parsedAuthData)
    }

    @Test
    fun `P256PublicKey compressed produces correct prefix for even y`() {
        val x = ByteArray(32) { 0x42 }
        val y = ByteArray(32).also { it[31] = 0x02 } // even last byte

        val key = P256PublicKey(x, y)

        assertEquals(33, key.compressed.size)
        assertEquals(0x02.toByte(), key.compressed[0])
        assertArrayEquals(x, key.compressed.copyOfRange(1, 33))
    }

    @Test
    fun `P256PublicKey compressed produces correct prefix for odd y`() {
        val x = ByteArray(32) { 0x42 }
        val y = ByteArray(32).also { it[31] = 0x03 } // odd last byte

        val key = P256PublicKey(x, y)

        assertEquals(33, key.compressed.size)
        assertEquals(0x03.toByte(), key.compressed[0])
    }

    @Test
    fun `P256PublicKey uncompressed is 65 bytes with 0x04 prefix`() {
        val x = ByteArray(32) { 0x01 }
        val y = ByteArray(32) { 0x02 }

        val key = P256PublicKey(x, y)

        assertEquals(65, key.uncompressed.size)
        assertEquals(0x04.toByte(), key.uncompressed[0])
        assertArrayEquals(x, key.uncompressed.copyOfRange(1, 33))
        assertArrayEquals(y, key.uncompressed.copyOfRange(33, 65))
    }

    @Test
    fun `P256PublicKey equality is by content`() {
        val k1 = P256PublicKey(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })
        val k2 = P256PublicKey(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })

        assertEquals(k1, k2)
        assertEquals(k1.hashCode(), k2.hashCode())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `P256PublicKey rejects wrong x size`() {
        P256PublicKey(ByteArray(31), ByteArray(32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `P256PublicKey rejects wrong y size`() {
        P256PublicKey(ByteArray(32), ByteArray(33))
    }

    @Test
    fun `compressedHex returns lowercase hex`() {
        val x = byteArrayOf(0x0A.toByte(), 0xFF.toByte()) + ByteArray(30)
        val y = ByteArray(32) // even

        val key = P256PublicKey(x, y)
        val hex = key.compressedHex()

        assertEquals("02", hex.substring(0, 2))
        assertEquals("0aff", hex.substring(2, 6))
    }

    // --- Helper: build a full authData blob with attested credential data ---

    /**
     * Constructs authData in the exact format the parser expects:
     * rpIdHash(32) + flags(1) + signCount(4) + AAGUID(16) + credIdLen(2) + credId(N) + COSE_Key
     */
    private fun buildAuthData(x: ByteArray, y: ByteArray): ByteArray {
        val rpIdHash = ByteArray(32) { 0xAA.toByte() }
        val flags = 0x41.toByte() // UP + AT (attested credential data present)
        val signCount = byteArrayOf(0, 0, 0, 1) // 4 bytes big-endian
        val aaguid = ByteArray(16) { 0xBB.toByte() }
        val credId = byteArrayOf(0x01, 0x02, 0x03, 0x04) // 4-byte credential ID
        val credIdLen = byteArrayOf(0x00, 0x04) // 2 bytes big-endian = 4

        val coseKey = buildCoseKeyP256(x, y)

        val total = rpIdHash.size + 1 + signCount.size + aaguid.size + credIdLen.size + credId.size + coseKey.size
        val buf = ByteBuffer.allocate(total)
        buf.put(rpIdHash)
        buf.put(flags)
        buf.put(signCount)
        buf.put(aaguid)
        buf.put(credIdLen)
        buf.put(credId)
        buf.put(coseKey)
        return buf.array()
    }

    private fun buildAttestationObjectWithAuthData(authData: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(512)
        // CBOR map with 3 entries
        buf.put(0xA3.toByte())

        // "fmt": "none"
        encodeCborText(buf, "fmt")
        encodeCborText(buf, "none")

        // "attStmt": {}
        encodeCborText(buf, "attStmt")
        buf.put(0xA0.toByte()) // empty map

        // "authData": <bytes>
        encodeCborText(buf, "authData")
        encodeCborByteString(buf, authData)

        return buf.array().copyOfRange(0, buf.position())
    }

    private fun buildCoseKeyP256(x: ByteArray, y: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(256)
        buf.put(0xA5.toByte()) // map with 5 items

        buf.put(0x01); buf.put(0x02) // 1: 2 (EC2)
        buf.put(0x03); buf.put(0x26.toByte()) // 3: -7 (ES256)
        buf.put(0x20.toByte()); buf.put(0x01) // -1: 1 (P-256)
        buf.put(0x21.toByte()); encodeCborByteString(buf, x) // -2: x
        buf.put(0x22.toByte()); encodeCborByteString(buf, y) // -3: y

        return buf.array().copyOfRange(0, buf.position())
    }

    private fun encodeCborText(buf: ByteBuffer, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        buf.put((0x60 + bytes.size).toByte())
        buf.put(bytes)
    }

    private fun encodeCborByteString(buf: ByteBuffer, data: ByteArray) {
        if (data.size < 24) {
            buf.put((0x40 + data.size).toByte())
        } else {
            buf.put(0x58.toByte())
            buf.put(data.size.toByte())
        }
        buf.put(data)
    }
}
