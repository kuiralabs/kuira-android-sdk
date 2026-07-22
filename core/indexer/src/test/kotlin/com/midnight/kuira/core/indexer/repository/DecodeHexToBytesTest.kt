package com.midnight.kuira.core.indexer.repository

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * : [decodeHexToBytes] replaced `hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()`
 * (tens of MB of List/boxed-Byte churn per multi-MB dust checkpoint) with a streaming decode.
 * The decode gates the dust-state deserialize, so a byte-level bug would corrupt the wallet
 * state (wrong balance / error 115). These pin its correctness and parity with the old impl.
 */
class DecodeHexToBytesTest {

    @Test
    fun `empty hex decodes to empty array`() {
        assertEquals(0, decodeHexToBytes("").size)
    }

    @Test
    fun `decodes known bytes including 0x00 high-nibble and 0xff`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x0f, 0xff.toByte(), 0xa5.toByte(), 0x10),
            decodeHexToBytes("000fffa510"),
        )
    }

    @Test
    fun `lowercase and uppercase hex decode identically`() {
        assertArrayEquals(decodeHexToBytes("deadbeef"), decodeHexToBytes("DEADBEEF"))
    }

    @Test
    fun `covers all 256 byte values`() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        assertArrayEquals(bytes, decodeHexToBytes(hex))
    }

    @Test
    fun `is byte-for-byte identical to the previous chunked implementation`() {
        val hex = "00112233445566778899aabbccddeefff0e1d2c3b4a5968778695a4b3c2d1e0f"
        @Suppress("DEPRECATION")
        val old = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(old, decodeHexToBytes(hex))
    }
}
