package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.sdk.walletruntime.ReceiveCheckpointStore.Companion.receivedDelta
import com.midnight.kuira.sdk.walletruntime.ReceiveCheckpointStore.Companion.shouldRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Pure tests for [ReceiveCheckpointStore]'s receipt-delta logic — what the background
 * receive poll uses to tell a genuine new receipt from a spend / resync dip.
 */
class ReceiveCheckpointStoreTest {

    private fun bi(v: Long) = BigInteger.valueOf(v)

    @Test
    fun `first observation is the baseline — no receipt, but recorded`() {
        assertNull(receivedDelta(storedHigh = null, current = bi(100)))
        assertTrue(shouldRecord(storedHigh = null, current = bi(100)))
        assertTrue("even a zero baseline is recorded", shouldRecord(storedHigh = null, current = BigInteger.ZERO))
    }

    @Test
    fun `a balance increase is a receipt of the difference, and is recorded`() {
        assertEquals(bi(30), receivedDelta(storedHigh = bi(100), current = bi(130)))
        assertTrue(shouldRecord(storedHigh = bi(100), current = bi(130)))
    }

    @Test
    fun `a dip (spend or resync transient) is not a receipt and not recorded`() {
        assertNull(receivedDelta(storedHigh = bi(100), current = bi(70)))
        assertFalse(shouldRecord(storedHigh = bi(100), current = bi(70)))
    }

    @Test
    fun `an unchanged balance is not a receipt and not recorded`() {
        assertNull(receivedDelta(storedHigh = bi(100), current = bi(100)))
        assertFalse(shouldRecord(storedHigh = bi(100), current = bi(100)))
    }

    @Test
    fun `dip-then-recover to the same high does not re-fire as a receipt`() {
        // high stays 100 through a dip to 70; recovering back to 100 is still <= high.
        assertNull(receivedDelta(storedHigh = bi(100), current = bi(70)))
        assertNull(receivedDelta(storedHigh = bi(100), current = bi(100)))
    }

    @Test
    fun `recovering ABOVE the prior high fires only the genuinely new amount`() {
        // high=100, dip to 70 (not recorded), then 120 → receipt of 20, not 50.
        assertEquals(bi(20), receivedDelta(storedHigh = bi(100), current = bi(120)))
    }
}
