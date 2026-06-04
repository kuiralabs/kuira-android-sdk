package com.midnight.kuira.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parsing the native balancer's `<txHex>;<n1>,<n2>` return value. Guards the wire
 * contract between the Rust FFI and the SDK (field order, delimiters, empty list).
 */
class BalanceEnvelopeTest {

    @Test
    fun `parses tx hex and a single spent nullifier`() {
        val parsed = BalanceEnvelope.parse("00a1b2c3;73636bf3fed6")
        assertEquals("00a1b2c3", parsed.txHex)
        assertEquals(listOf("73636bf3fed6"), parsed.spentNullifiers)
    }

    @Test
    fun `parses multiple spent nullifiers in order`() {
        val parsed = BalanceEnvelope.parse("deadbeef;aaaa,bbbb,cccc")
        assertEquals("deadbeef", parsed.txHex)
        assertEquals(listOf("aaaa", "bbbb", "cccc"), parsed.spentNullifiers)
    }

    @Test
    fun `empty nullifier list yields tx hex and no nullifiers`() {
        val parsed = BalanceEnvelope.parse("deadbeef;")
        assertEquals("deadbeef", parsed.txHex)
        assertEquals(emptyList<String>(), parsed.spentNullifiers)
    }

    @Test
    fun `missing separator treats whole string as tx hex`() {
        val parsed = BalanceEnvelope.parse("deadbeef")
        assertEquals("deadbeef", parsed.txHex)
        assertEquals(emptyList<String>(), parsed.spentNullifiers)
    }
}
