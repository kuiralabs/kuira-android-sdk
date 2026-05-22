package com.midnight.example.bboard

import com.midnight.kuira.core.compact.MidnightLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Pins [BBoardRepository.classify] against the four ledger shapes
 * the contract can present. classify() is pure (no chain, no SDK)
 * so it's testable straight from a `MidnightLedger` snapshot
 * constructed via the SDK's internal constructor (accessed via
 * reflection — same pattern as Kicks).
 *
 * The four shapes:
 *  - state=0 → Vacant
 *  - state=1 + message.is_some=true → Occupied(msg)
 *  - state=1 + message.is_some=false → Error (contract-invariant
 *    violation: `post`/`takeDown` always move both fields together)
 *  - unknown state code → Error
 */
class BBoardRepositoryTest {

    @Test
    fun `state=0 maps to Vacant`() {
        val ledger = ledgerOf(mapOf("state" to BigInteger.ZERO))
        assertEquals(BoardContent.Vacant, BBoardRepository.classify(ledger))
    }

    @Test
    fun `state=1 with is_some=true maps to Occupied with the message`() {
        val ledger = ledgerOf(mapOf(
            "state" to BigInteger.ONE,
            "message" to mapOf("is_some" to true, "value" to "hello chain"),
        ))
        val content = BBoardRepository.classify(ledger)
        assertTrue("expected Occupied, got $content", content is BoardContent.Occupied)
        assertEquals("hello chain", (content as BoardContent.Occupied).message)
    }

    @Test
    fun `state=1 with is_some=false surfaces the contract-invariant violation as Error`() {
        val ledger = ledgerOf(mapOf(
            "state" to BigInteger.ONE,
            "message" to mapOf("is_some" to false, "value" to ""),
        ))
        val content = BBoardRepository.classify(ledger)
        assertTrue("expected Error, got $content", content is BoardContent.Error)
        val reason = (content as BoardContent.Error).reason
        assertTrue(
            "error message should explain the violation: $reason",
            reason.contains("OCCUPIED") && reason.contains("message"),
        )
    }

    @Test
    fun `unknown state code surfaces as Error`() {
        val ledger = ledgerOf(mapOf("state" to BigInteger.valueOf(7)))
        val content = BBoardRepository.classify(ledger)
        assertTrue(content is BoardContent.Error)
        assertTrue((content as BoardContent.Error).reason.contains("7"))
    }

    /** Construct a real [MidnightLedger] from raw fields; SDK ctor is internal. */
    private fun ledgerOf(fields: Map<String, Any?>): MidnightLedger {
        val ctor = MidnightLedger::class.java.getDeclaredConstructor(Map::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(fields)
    }
}
