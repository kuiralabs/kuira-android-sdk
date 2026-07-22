package com.midnight.kuira.core.compact

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for [MidnightLedger] typed accessors.
 *
 * These pin the accessor contract against hand-built `Map<String, Any?>`
 * snapshots — the exact shape [LedgerEvaluator] would produce after
 * marshalling the contract's `ledger()` return values. End-to-end
 * coverage against the live runtime is in the instrumented test
 * (see `MidnightLedgerInstrumentedTest`).
 *
 * Coverage:
 *  - Each typed getter (Uint8/Uint64/UintBig/Boolean/Bytes/VectorUint8)
 *  decodes the correct Kotlin type from the marshalled value.
 *  - Range / shape mismatches throw [WrongLedgerFieldTypeException]
 *  with a descriptive message.
 *  - Missing field names throw [MissingLedgerFieldException] carrying
 *  the known-fields list.
 *  - `*OrNull` variants return null on missing fields, NOT on zero
 *  values (zero values are real data, not absence).
 *  - Zero values round-trip correctly (the bug-class this whole work
 *  set out to eliminate — see PLAN.md wishlist post-mortem).
 */
class MidnightLedgerTest {

    // ── getRaw / fieldNames ─────────────────────────────────────────────

    @Test
    fun `fieldNames exposes every key from the underlying map`() {
        val ledger = MidnightLedger(
            mapOf(
                "phase" to BigInteger.ZERO,
                "p1Score" to BigInteger.valueOf(3),
                "p1Shoots" to listOf<BigInteger>(),
            ),
        )
        assertEquals(setOf("phase", "p1Score", "p1Shoots"), ledger.fieldNames())
    }

    @Test
    fun `getRaw returns the unwrapped value for an existing field`() {
        val ledger = MidnightLedger(mapOf("phase" to BigInteger.valueOf(5)))
        assertEquals(BigInteger.valueOf(5), ledger.getRaw("phase"))
    }

    @Test
    fun `getRaw throws MissingLedgerFieldException for unknown name`() {
        val ledger = MidnightLedger(mapOf("phase" to BigInteger.ZERO))
        val ex = assertThrowsMissing { ledger.getRaw("notAField") }
        assertEquals("notAField", ex.fieldName)
        assertTrue(
            "known-fields list should appear in the message",
            ex.message?.contains("phase") == true,
        )
    }

    @Test
    fun `getRawOrNull returns null for unknown name`() {
        val ledger = MidnightLedger(mapOf("phase" to BigInteger.ZERO))
        assertNull(ledger.getRawOrNull("notAField"))
    }

    // ── Uint8 ───────────────────────────────────────────────────────────

    @Test
    fun `getUint8 decodes a BigInteger in 0_255 to Int`() {
        val ledger = MidnightLedger(
            mapOf(
                "min" to BigInteger.ZERO,
                "mid" to BigInteger.valueOf(127),
                "max" to BigInteger.valueOf(255),
            ),
        )
        assertEquals(0, ledger.getUint8("min"))
        assertEquals(127, ledger.getUint8("mid"))
        assertEquals(255, ledger.getUint8("max"))
    }

    @Test
    fun `getUint8 zero value is real data, not missing`() {
        // The bug class this whole work eliminates: a zero-valued
        // field should round-trip as 0, NOT as a missing field.
        // Without this guarantee, parsers silently drop zero
        // positions in Vector decoding (the 2026-05-20 bug).
        val ledger = MidnightLedger(mapOf("p1SdShoot" to BigInteger.ZERO))
        assertEquals(0, ledger.getUint8("p1SdShoot"))
        assertEquals(0, ledger.getUint8OrNull("p1SdShoot"))
    }

    @Test
    fun `getUint8 throws WrongType when value exceeds 255`() {
        val ledger = MidnightLedger(mapOf("tooBig" to BigInteger.valueOf(256)))
        val ex = assertThrowsWrongType { ledger.getUint8("tooBig") }
        assertEquals("tooBig", ex.fieldName)
        assertTrue(ex.expected.contains("0..255"))
    }

    @Test
    fun `getUint8 throws WrongType when value is negative`() {
        val ledger = MidnightLedger(mapOf("neg" to BigInteger.valueOf(-1)))
        assertThrowsWrongType { ledger.getUint8("neg") }
    }

    @Test
    fun `getUint8 throws WrongType when raw is not a BigInteger`() {
        val ledger = MidnightLedger(mapOf("wrong" to "not a number"))
        val ex = assertThrowsWrongType { ledger.getUint8("wrong") }
        assertTrue(ex.expected.contains("Uint<8>"))
    }

    @Test
    fun `getUint8OrNull returns null for missing field, value for present`() {
        val ledger = MidnightLedger(mapOf("present" to BigInteger.valueOf(42)))
        assertEquals(42, ledger.getUint8OrNull("present"))
        assertNull(ledger.getUint8OrNull("missing"))
    }

    // ── Uint64 ──────────────────────────────────────────────────────────

    @Test
    fun `getUint64 decodes values up to Long_MAX_VALUE`() {
        val ledger = MidnightLedger(
            mapOf(
                "small" to BigInteger.valueOf(42),
                "max" to BigInteger.valueOf(Long.MAX_VALUE),
            ),
        )
        assertEquals(42L, ledger.getUint64("small"))
        assertEquals(Long.MAX_VALUE, ledger.getUint64("max"))
    }

    @Test
    fun `getUint64 throws on value beyond Long_MAX_VALUE — caller should use getUintBig`() {
        val ledger = MidnightLedger(
            mapOf("tooBig" to BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
        )
        val ex = assertThrowsWrongType { ledger.getUint64("tooBig") }
        // Caller-visible hint to switch to getUintBig.
        assertTrue(
            "exception should suggest getUintBig: ${ex.expected}",
            ex.expected.contains("getUintBig"),
        )
    }

    // ── UintBig ─────────────────────────────────────────────────────────

    @Test
    fun `getUintBig handles arbitrary-precision values`() {
        val huge = BigInteger("123456789012345678901234567890")
        val ledger = MidnightLedger(mapOf("huge" to huge))
        assertEquals(huge, ledger.getUintBig("huge"))
    }

    @Test
    fun `getUintBig rejects negative values`() {
        val ledger = MidnightLedger(mapOf("neg" to BigInteger.valueOf(-1)))
        assertThrowsWrongType { ledger.getUintBig("neg") }
    }

    // ── Boolean ─────────────────────────────────────────────────────────

    @Test
    fun `getBoolean decodes true and false`() {
        val ledger = MidnightLedger(mapOf("isReady" to true, "isDraw" to false))
        assertTrue(ledger.getBoolean("isReady"))
        assertFalse(ledger.getBoolean("isDraw"))
    }

    @Test
    fun `getBoolean accepts BigInteger 0 and 1 as forward-compat fallback`() {
        // Some Compact runtime paths historically returned 0/1 for
        // Boolean cells; the JS marshaller also normalizes integer
        // Numbers to bigint envelopes. getBoolean tolerates both
        // shapes so a runtime change doesn't surface as a
        // WrongLedgerFieldTypeException everywhere.
        val ledger = MidnightLedger(
            mapOf("trueAsBig" to BigInteger.ONE, "falseAsBig" to BigInteger.ZERO),
        )
        assertTrue(ledger.getBoolean("trueAsBig"))
        assertFalse(ledger.getBoolean("falseAsBig"))
    }

    @Test
    fun `getBoolean rejects BigInteger values other than 0 or 1`() {
        val ledger = MidnightLedger(mapOf("notBool" to BigInteger.valueOf(2)))
        val ex = assertThrowsWrongType { ledger.getBoolean("notBool") }
        assertTrue(
            "exception should hint at the accepted shape: ${ex.expected}",
            ex.expected.contains("BigInteger 0/1"),
        )
    }

    @Test
    fun `getBoolean throws WrongType when raw is neither Boolean nor BigInteger`() {
        val ledger = MidnightLedger(mapOf("notBool" to "true"))
        val ex = assertThrowsWrongType { ledger.getBoolean("notBool") }
        assertEquals("Boolean", ex.expected)
    }

    // ── String / Maybe<String> ──────────────────────────────────────────

    @Test
    fun `getString decodes an Opaque String field`() {
        val ledger = MidnightLedger(mapOf("msg" to "hello world"))
        assertEquals("hello world", ledger.getString("msg"))
    }

    @Test
    fun `getString throws WrongType when the field is not a String`() {
        val ledger = MidnightLedger(mapOf("notString" to BigInteger.ONE))
        val ex = assertThrowsWrongType { ledger.getString("notString") }
        assertEquals("String", ex.expected)
    }

    @Test
    fun `getMaybeString returns null when is_some=false`() {
        val ledger = MidnightLedger(
            mapOf("msg" to mapOf("is_some" to false, "value" to "")),
        )
        assertNull(ledger.getMaybeString("msg"))
    }

    @Test
    fun `getMaybeString returns the inner String when is_some=true`() {
        val ledger = MidnightLedger(
            mapOf("msg" to mapOf("is_some" to true, "value" to "hello")),
        )
        assertEquals("hello", ledger.getMaybeString("msg"))
    }

    @Test
    fun `getMaybeString throws WrongType when raw is not a Map`() {
        val ledger = MidnightLedger(mapOf("msg" to "raw string, not wrapped in Maybe"))
        val ex = assertThrowsWrongType { ledger.getMaybeString("msg") }
        assertTrue(ex.expected.contains("Maybe<String>"))
    }

    @Test
    fun `getMaybeString throws when is_some entry is not a Boolean`() {
        val ledger = MidnightLedger(
            mapOf("msg" to mapOf("is_some" to "yes", "value" to "x")),
        )
        assertThrowsWrongType { ledger.getMaybeString("msg") }
    }

    @Test
    fun `getMaybeString throws when value is not a String despite is_some=true`() {
        val ledger = MidnightLedger(
            mapOf("msg" to mapOf("is_some" to true, "value" to BigInteger.ONE)),
        )
        assertThrowsWrongType { ledger.getMaybeString("msg") }
    }

    @Test
    fun `getMaybeStringOrNull returns null on missing field`() {
        val ledger = MidnightLedger(emptyMap())
        assertNull(ledger.getMaybeStringOrNull("absent"))
    }

    @Test
    fun `getStringOrNull returns null on missing field`() {
        val ledger = MidnightLedger(emptyMap())
        assertNull(ledger.getStringOrNull("absent"))
    }

    // ── Bytes<N> ────────────────────────────────────────────────────────

    @Test
    fun `getBytes decodes a ByteArray of exact length`() {
        val data = ByteArray(32) { it.toByte() }
        val ledger = MidnightLedger(mapOf("addr" to data))
        val decoded = ledger.getBytes("addr", 32)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `getBytes returns a defensive copy — mutating the result doesn't change the ledger`() {
        val data = ByteArray(32) { it.toByte() }
        val ledger = MidnightLedger(mapOf("addr" to data))
        val copy = ledger.getBytes("addr", 32)
        copy[0] = 99
        assertEquals(0.toByte(), ledger.getBytes("addr", 32)[0])
    }

    @Test
    fun `getBytes throws WrongType when length doesn't match expectedLength`() {
        val ledger = MidnightLedger(mapOf("short" to ByteArray(16)))
        val ex = assertThrowsWrongType { ledger.getBytes("short", 32) }
        assertTrue(ex.expected.contains("Bytes<32>"))
        assertTrue(ex.expected.contains("got 16 bytes"))
    }

    @Test
    fun `getBytes accepts List of BigInteger — the JSON-array on-wire shape`() {
        // The 2026-05-20 live bug: CompactTypeBytes.fromValue returns
        // `val` as-is when val.length == N (JS Array<number> from
        // __nativeContractQuery JSON), not always a Uint8Array. The
        // LedgerEvaluator marshaller's `Array.isArray(v)` branch then
        // produces a List<BigInteger> on the Kotlin side. Without
        // this code path, every Bytes<32> ledger field surfaces as
        // WrongLedgerFieldTypeException on the polling loop.
        val byteValues = listOf(
            29, 81, 167, 38, 108, 17, 207, 0, 192, 173,
            245, 184, 8, 157, 203, 144, 245, 52, 250, 206,
            117, 39, 47, 195, 207, 216, 170, 101, 102, 249,
            90, 127,
        )
        val ledger = MidnightLedger(
            mapOf("player1" to byteValues.map { BigInteger.valueOf(it.toLong()) }),
        )
        val decoded = ledger.getBytes("player1", 32)
        // Unsigned 0..255 round-trip via two's complement Byte values.
        assertEquals(32, decoded.size)
        byteValues.forEachIndexed { i, expected ->
            assertEquals(
                "byte[$i] should round-trip to its signed-byte equivalent",
                expected.toByte(),
                decoded[i],
            )
        }
    }

    @Test
    fun `getBytes accepts a List of mixed Int and Long elements`() {
        // The marshal envelope normalizes integer numbers to bigint,
        // but a hand-built fixture (or a future runtime change) could
        // include Int / Long elements. Tolerate both.
        val ledger = MidnightLedger(
            mapOf(
                "mixed" to listOf<Any>(
                    1,
                    2L,
                    BigInteger.valueOf(3),
                    4,
                ),
            ),
        )
        val decoded = ledger.getBytes("mixed", 4)
        assertEquals(1.toByte(), decoded[0])
        assertEquals(2.toByte(), decoded[1])
        assertEquals(3.toByte(), decoded[2])
        assertEquals(4.toByte(), decoded[3])
    }

    @Test
    fun `getBytes throws when a List element is out of 0_255 range`() {
        val ledger = MidnightLedger(
            mapOf("bad" to listOf(BigInteger.ONE, BigInteger.valueOf(256), BigInteger.ZERO, BigInteger.ZERO)),
        )
        val ex = assertThrowsWrongType { ledger.getBytes("bad", 4) }
        assertTrue(
            "expected hint should mention element index + range: ${ex.expected}",
            ex.expected.contains("element[1]") && ex.expected.contains("0..255"),
        )
    }

    @Test
    fun `getBytes throws when List length doesn't match expectedLength`() {
        val ledger = MidnightLedger(
            mapOf("short" to List(16) { BigInteger.ZERO }),
        )
        val ex = assertThrowsWrongType { ledger.getBytes("short", 32) }
        assertTrue(ex.expected.contains("got 16 elements"))
    }

    // ── Vector<N, Uint<8>> ──────────────────────────────────────────────

    @Test
    fun `getVectorUint8 decodes a List of BigInteger to IntArray`() {
        val ledger = MidnightLedger(
            mapOf(
                "shoots" to listOf(
                    BigInteger.valueOf(0),
                    BigInteger.valueOf(1),
                    BigInteger.valueOf(2),
                    BigInteger.valueOf(1),
                    BigInteger.valueOf(0),
                ),
            ),
        )
        assertArrayEquals(intArrayOf(0, 1, 2, 1, 0), ledger.getVectorUint8("shoots", 5))
    }

    @Test
    fun `getVectorUint8 preserves internal zero positions — the original bug`() {
        // The bug-class this whole architecture exists to solve: a
        // Vector<5, Uint<8>> with zero elements at internal positions
        // MUST round-trip those positions. The runtime-driven path
        // provides this; the old cell-hex parser silently dropped
        // them.
        val internalZeros = listOf(
            BigInteger.valueOf(1),
            BigInteger.valueOf(0),
            BigInteger.valueOf(2),
            BigInteger.valueOf(0),
            BigInteger.valueOf(2),
        )
        val ledger = MidnightLedger(mapOf("p1Keeps" to internalZeros))
        assertArrayEquals(
            intArrayOf(1, 0, 2, 0, 2),
            ledger.getVectorUint8("p1Keeps", 5),
        )
    }

    @Test
    fun `getVectorUint8 all-zero vector decodes to all zeros`() {
        val ledger = MidnightLedger(
            mapOf("zeros" to List(5) { BigInteger.ZERO }),
        )
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), ledger.getVectorUint8("zeros", 5))
    }

    @Test
    fun `getVectorUint8 throws on length mismatch`() {
        val ledger = MidnightLedger(
            mapOf("short" to List(3) { BigInteger.ONE }),
        )
        val ex = assertThrowsWrongType { ledger.getVectorUint8("short", 5) }
        assertTrue(ex.expected.contains("got 3 elements"))
    }

    @Test
    fun `getVectorUint8 throws when element exceeds 255`() {
        val ledger = MidnightLedger(
            mapOf(
                "bad" to listOf(
                    BigInteger.ONE,
                    BigInteger.ONE,
                    BigInteger.valueOf(256), // out of Uint8 range
                    BigInteger.ONE,
                    BigInteger.ONE,
                ),
            ),
        )
        val ex = assertThrowsWrongType { ledger.getVectorUint8("bad", 5) }
        assertTrue(
            "exception should mention element index and range",
            ex.expected.contains("element[2]") && ex.expected.contains("0..255"),
        )
    }

    @Test
    fun `getVectorUint8 throws when element is not a BigInteger`() {
        val ledger = MidnightLedger(
            mapOf(
                "mixed" to listOf<Any>(
                    BigInteger.ONE,
                    "not-a-bigint", // wrong type
                    BigInteger.ONE,
                    BigInteger.ONE,
                    BigInteger.ONE,
                ),
            ),
        )
        val ex = assertThrowsWrongType { ledger.getVectorUint8("mixed", 5) }
        assertTrue(ex.expected.contains("element[1]"))
    }

    // ── Cross-cutting: missing-field handling on every typed getter ─────

    @Test
    fun `every typed getter throws MissingLedgerFieldException on unknown name`() {
        val ledger = MidnightLedger(mapOf("known" to BigInteger.ZERO))
        // Each call should throw; checking each one ensures no
        // accessor accidentally returns 0/false/empty for a missing
        // field (the silent-zero bug class).
        assertThrowsMissing { ledger.getUint8("missing") }
        assertThrowsMissing { ledger.getUint64("missing") }
        assertThrowsMissing { ledger.getUintBig("missing") }
        assertThrowsMissing { ledger.getBoolean("missing") }
        assertThrowsMissing { ledger.getBytes("missing", 32) }
        assertThrowsMissing { ledger.getVectorUint8("missing", 5) }
    }

    @Test
    fun `every OrNull getter returns null on missing name`() {
        val ledger = MidnightLedger(emptyMap())
        assertNull(ledger.getUint8OrNull("missing"))
        assertNull(ledger.getUint64OrNull("missing"))
        assertNull(ledger.getUintBigOrNull("missing"))
        assertNull(ledger.getBooleanOrNull("missing"))
        assertNull(ledger.getBytesOrNull("missing", 32))
        assertNull(ledger.getVectorUint8OrNull("missing", 5))
    }

    // ── Test helpers ────────────────────────────────────────────────────

    private inline fun assertThrowsMissing(
        block: () -> Unit,
    ): MissingLedgerFieldException {
        try {
            block()
            fail("expected MissingLedgerFieldException")
            throw IllegalStateException("unreachable")
        } catch (e: MissingLedgerFieldException) {
            return e
        }
    }

    private inline fun assertThrowsWrongType(
        block: () -> Unit,
    ): WrongLedgerFieldTypeException {
        try {
            block()
            fail("expected WrongLedgerFieldTypeException")
            throw IllegalStateException("unreachable")
        } catch (e: WrongLedgerFieldTypeException) {
            return e
        }
    }
}
