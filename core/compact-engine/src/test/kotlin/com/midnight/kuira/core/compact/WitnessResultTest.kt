package com.midnight.kuira.core.compact

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wire format of [WitnessResult.toJsArrayString] — the
 * pipe-delimited string the SDK hands to the QuickJs witness shim.
 * The JS template in [CircuitExecutor.buildCircuitJs] parses this
 * exact format; the two have to stay in lockstep or every witness
 * call surfaces as a "type mismatch" from the Compact runtime.
 *
 * Regression coverage:
 *  - [WitnessKind.BYTES] default keeps the historical 3-part form
 *    even though older callers may not pass `kind` explicitly.
 *  - [WitnessKind.VECTOR_OF_UINT8] writes the same byte data but
 *    flags the JS side to lift each byte to a `BigInt` array
 *    (matches Compact's `Vector<N, Uint<8>>` type).
 *
 * If this test ever fails, also bump the matching JS template
 * comment — they share a contract that's easy to drift.
 */
class WitnessResultTest {

    @Test
    fun `BYTES kind serializes state, kind, and csv bytes in order`() {
        val w = WitnessResult(
            privateState = null,
            data = byteArrayOf(0x10, 0x20, 0x30),
            // kind defaults to BYTES — explicit here for the test reader.
            kind = WitnessKind.BYTES,
        )
        assertEquals("null|BYTES|16,32,48", w.toJsArrayString())
    }

    @Test
    fun `VECTOR_OF_UINT8 kind serializes the kind marker for the JS branch`() {
        // The actual byte values are unchanged across kinds — the
        // marker is the SDK's signal to the JS shim that this payload
        // should arrive at the Compact runtime as Array<BigInt>, not
        // Uint8Array. Both ends have to agree on the marker name.
        val w = WitnessResult(
            privateState = null,
            data = byteArrayOf(0x00, 0x02, 0x00, 0x02, 0x00),
            kind = WitnessKind.VECTOR_OF_UINT8,
        )
        assertEquals("null|VECTOR_OF_UINT8|0,2,0,2,0", w.toJsArrayString())
    }

    @Test
    fun `default kind is BYTES so existing callers keep working`() {
        // Backward compatibility check — callers that don't pass `kind`
        // (BBoard, every pre-V3 Kicks witness, every consumer not yet
        // aware of the param) get the historical Uint8Array semantics.
        val w = WitnessResult(privateState = null, data = byteArrayOf(0x01))
        assertEquals(WitnessKind.BYTES, w.kind)
        assertEquals("null|BYTES|1", w.toJsArrayString())
    }

    @Test
    fun `privateState is rendered via toString for non-null values`() {
        // Mirrors the existing behavior — privateState.toString() is
        // what the Kotlin side emits; the JS side JSON.parses it.
        // Maps stringify to {key=value, …}, which IS NOT JSON-parseable
        // (Kotlin uses `=`, JSON wants `:`). The existing format expects
        // callers to use Maps that produce JSON-shaped toString — out
        // of scope for this test, but the assertion locks the
        // serialization side so downstream regressions are visible.
        val w = WitnessResult(
            privateState = "{\"secretKey\":\"…\"}",
            data = byteArrayOf(0x07),
        )
        assertEquals("""{"secretKey":"…"}|BYTES|7""", w.toJsArrayString())
    }

    @Test
    fun `empty data still produces a parseable three-part wire form`() {
        // Defensive: empty witness payload (theoretically possible for
        // zero-length Bytes<0>). The CSV section becomes empty string;
        // the JS template's `.split(',')` yields a single-element
        // array of `''` which `.map(Number)` turns into [NaN]. Edge
        // case not exercised in practice today, but the wire form
        // should still round-trip.
        val w = WitnessResult(privateState = null, data = ByteArray(0))
        assertEquals("null|BYTES|", w.toJsArrayString())
    }
}
