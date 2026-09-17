package com.midnight.kuira.core.compact

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/**
 * A witness provider's buffer must survive being used for a proof.
 *
 * kuira-sdk-android#6: the SDK zeroized the [WitnessResult] a provider returned, and
 * because a Kotlin `ByteArray` is a reference that reached the provider's own memory. A
 * provider handing back sibling nodes out of a live Merkle tree had the tree wiped by the
 * first proof; the second failed with "not on the roll", with nothing pointing at the
 * witness layer.
 */
class WitnessBufferOwnershipTest {

    @Test
    fun `serializing a witness leaves the provider's array intact`() {
        val callerOwned = byteArrayOf(1, 2, 3, 4)
        val provided = WitnessResult(null, callerOwned, WitnessKind.BYTES)

        CircuitExecutor.serializeWitness(provided)

        assertArrayEquals(
            "the provider still owns this array; the SDK must wipe only its own copy",
            byteArrayOf(1, 2, 3, 4),
            callerOwned,
        )
    }

    @Test
    fun `a reused buffer serializes identically across repeated proofs`() {
        // The actual reported symptom: the second proof saw zeros.
        val treeNode = byteArrayOf(9, 8, 7)
        val provider = WitnessProvider { WitnessResult(null, treeNode, WitnessKind.BYTES) }

        val first = CircuitExecutor.serializeWitness(provider.provide(null))
        val second = CircuitExecutor.serializeWitness(provider.provide(null))

        assertEquals("null|BYTES|9,8,7", first)
        assertEquals("the second proof must see the same bytes as the first", first, second)
    }

    @Test
    fun `the serialized form carries the bytes before the copy is wiped`() {
        val data = byteArrayOf(42, 0, 255.toByte())
        assertEquals(
            "null|BYTES|42,0,255",
            CircuitExecutor.serializeWitness(WitnessResult(null, data, WitnessKind.BYTES)),
        )
    }
}

/**
 * kuira-android-sdk#4, constraint 1: a contract's generated constructor resolves every
 * declared witness when it is built, so omitting one the called circuit never invokes
 * still fails — and it fails at construction, nowhere near the call being made. The
 * runtime names the witness; these tests pin the guidance that explains the rule.
 */
class WitnessGapExplanationTest {

    private val runtimeError =
        "CompactError: first (witnesses) argument to Contract constructor " +
            "does not contain a function-valued field named enrolmentPath"

    @Test
    fun `names the missing witness and states the all-witnesses rule`() {
        val explained = CircuitExecutor.explainWitnessGap(
            runtimeError,
            setOf("localSecretKey", "birthYear"),
        )

        assertTrue("keeps the original runtime text", explained.contains(runtimeError))
        assertTrue("names the missing witness", explained.contains("'enrolmentPath'"))
        assertTrue("lists what was supplied", explained.contains("birthYear, localSecretKey"))
        assertTrue(
            "explains that unused witnesses still have to be declared",
            explained.contains("never") && explained.contains("invokes"),
        )
    }

    @Test
    fun `says so plainly when no witnesses were supplied at all`() {
        val explained = CircuitExecutor.explainWitnessGap(runtimeError, emptySet())
        assertTrue(explained.contains("the witnesses map is empty"))
    }

    @Test
    fun `passes unrelated errors through untouched`() {
        val other = "CompactError: failed assert: not on the roll"
        assertEquals(other, CircuitExecutor.explainWitnessGap(other, setOf("sib0")))
    }
}
