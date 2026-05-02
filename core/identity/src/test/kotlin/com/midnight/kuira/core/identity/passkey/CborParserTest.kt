package com.midnight.kuira.core.identity.passkey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Tests for the minimal CBOR parser used in WebAuthn attestation parsing.
 *
 * Constructs CBOR byte sequences manually to verify parsing without
 * depending on external CBOR libraries.
 */
class CborParserTest {

    /**
     * Tests extracting P-256 x/y from a COSE_Key CBOR map.
     *
     * Constructs the CBOR manually:
     * {1: 2, 3: -7, -1: 1, -2: <32 bytes x>, -3: <32 bytes y>}
     */
    @Test
    fun `extractP256FromCoseKey parses valid EC2 P-256 COSE key`() {
        val x = ByteArray(32) { (it + 1).toByte() }
        val y = ByteArray(32) { (it + 33).toByte() }

        val coseKey = buildCoseKeyP256(x, y)
        val result = CborParser.extractP256FromCoseKey(coseKey)

        assertArrayEquals(x, result.x)
        assertArrayEquals(y, result.y)
    }

    @Test(expected = AttestationParseException::class)
    fun `extractP256FromCoseKey rejects non-EC2 key type`() {
        val x = ByteArray(32)
        val y = ByteArray(32)
        // kty = 1 (OKP) instead of 2 (EC2)
        val coseKey = buildCoseKey(kty = 1, alg = -7, crv = 1, x = x, y = y)
        CborParser.extractP256FromCoseKey(coseKey)
    }

    @Test(expected = AttestationParseException::class)
    fun `extractP256FromCoseKey rejects non-P256 curve`() {
        val x = ByteArray(32)
        val y = ByteArray(32)
        // crv = 2 (P-384) instead of 1 (P-256)
        val coseKey = buildCoseKey(kty = 2, alg = -7, crv = 2, x = x, y = y)
        CborParser.extractP256FromCoseKey(coseKey)
    }

    @Test
    fun `extractAuthData parses CBOR map with authData key`() {
        val authData = ByteArray(37) { it.toByte() }
        val attestationObject = buildAttestationObject(authData)

        val result = CborParser.extractAuthData(attestationObject)

        assertArrayEquals(authData, result)
    }

    @Test(expected = AttestationParseException::class)
    fun `extractAuthData throws for missing authData key`() {
        // Build a CBOR map with only "fmt" key, no "authData"
        val cbor = buildCborMap(mapOf("fmt" to "none"))
        CborParser.extractAuthData(cbor)
    }

    // --- CBOR construction helpers ---

    /**
     * Builds a COSE_Key CBOR map for EC2 P-256.
     */
    private fun buildCoseKeyP256(x: ByteArray, y: ByteArray): ByteArray {
        return buildCoseKey(kty = 2, alg = -7, crv = 1, x = x, y = y)
    }

    /**
     * Builds a COSE_Key CBOR map with configurable fields.
     * Map with 5 entries: {1: kty, 3: alg, -1: crv, -2: x, -3: y}
     */
    private fun buildCoseKey(kty: Int, alg: Int, crv: Int, x: ByteArray, y: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(256)

        // CBOR map with 5 items
        buf.put(0xA5.toByte())

        // 1: kty
        buf.put(0x01) // unsigned int 1
        buf.put(kty.toByte()) // unsigned int value

        // 3: alg
        buf.put(0x03) // unsigned int 3
        encodeCborNegInt(buf, alg) // negative int -7

        // -1: crv
        buf.put(0x20.toByte()) // negative int -1 (major type 1, value 0)
        buf.put(crv.toByte()) // unsigned int value

        // -2: x
        buf.put(0x21.toByte()) // negative int -2 (major type 1, value 1)
        encodeCborByteString(buf, x)

        // -3: y
        buf.put(0x22.toByte()) // negative int -3 (major type 1, value 2)
        encodeCborByteString(buf, y)

        return buf.array().copyOfRange(0, buf.position())
    }

    /**
     * Builds a minimal attestation object CBOR: {"fmt": "none", "attStmt": {}, "authData": <bytes>}
     */
    private fun buildAttestationObject(authData: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(512)

        // CBOR map with 3 items
        buf.put(0xA3.toByte())

        // "fmt": "none"
        encodeCborTextString(buf, "fmt")
        encodeCborTextString(buf, "none")

        // "attStmt": {}
        encodeCborTextString(buf, "attStmt")
        buf.put(0xA0.toByte()) // empty map

        // "authData": <bytes>
        encodeCborTextString(buf, "authData")
        encodeCborByteString(buf, authData)

        return buf.array().copyOfRange(0, buf.position())
    }

    /**
     * Builds a simple CBOR map from string keys and string values.
     */
    private fun buildCborMap(entries: Map<String, String>): ByteArray {
        val buf = ByteBuffer.allocate(512)
        val count = entries.size
        buf.put((0xA0 + count).toByte()) // map with N items

        for ((key, value) in entries) {
            encodeCborTextString(buf, key)
            encodeCborTextString(buf, value)
        }

        return buf.array().copyOfRange(0, buf.position())
    }

    private fun encodeCborByteString(buf: ByteBuffer, data: ByteArray) {
        val len = data.size
        when {
            len < 24 -> buf.put((0x40 + len).toByte())
            len < 256 -> { buf.put(0x58.toByte()); buf.put(len.toByte()) }
            else -> { buf.put(0x59.toByte()); buf.putShort(len.toShort()) }
        }
        buf.put(data)
    }

    private fun encodeCborTextString(buf: ByteBuffer, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        when {
            len < 24 -> buf.put((0x60 + len).toByte())
            len < 256 -> { buf.put(0x78.toByte()); buf.put(len.toByte()) }
            else -> { buf.put(0x79.toByte()); buf.putShort(len.toShort()) }
        }
        buf.put(bytes)
    }

    private fun encodeCborNegInt(buf: ByteBuffer, value: Int) {
        if (value >= 0) {
            // unsigned
            if (value < 24) buf.put(value.toByte())
            else { buf.put(0x18.toByte()); buf.put(value.toByte()) }
        } else {
            // negative: CBOR encodes -1-N as major type 1 with value N
            val n = -1 - value
            if (n < 24) buf.put((0x20 + n).toByte())
            else { buf.put(0x38.toByte()); buf.put(n.toByte()) }
        }
    }

    // --- Edge case / safety tests ---

    @Test(expected = AttestationParseException::class)
    fun `extractAuthData throws on empty input`() {
        CborParser.extractAuthData(ByteArray(0))
    }

    @Test(expected = AttestationParseException::class)
    fun `extractP256FromCoseKey throws on empty input`() {
        CborParser.extractP256FromCoseKey(ByteArray(0))
    }

    @Test(expected = AttestationParseException::class)
    fun `extractAuthData throws on non-map CBOR`() {
        // 0x41 = byte string of length 1 (not a map)
        CborParser.extractAuthData(byteArrayOf(0x41, 0x00))
    }

    @Test(expected = AttestationParseException::class)
    fun `extractP256FromCoseKey rejects truncated data`() {
        // Valid map header but truncated content
        CborParser.extractP256FromCoseKey(byteArrayOf(0xA5.toByte()))
    }

    @Test(expected = AttestationParseException::class)
    fun `readByteString rejects length exceeding remaining buffer`() {
        // CBOR: map with 1 entry, key="x", value=byte string claiming 255 bytes but buffer has 0
        val buf = ByteBuffer.allocate(20)
        buf.put(0xA1.toByte()) // map of 1
        buf.put(0x61.toByte()); buf.put('x'.code.toByte()) // text "x"
        buf.put(0x58.toByte()); buf.put(0xFF.toByte()) // bstr length 255 — no data follows

        CborParser.extractAuthData(buf.array().copyOfRange(0, buf.position()))
    }
}
