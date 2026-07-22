package com.midnight.kuira.sdk.walletruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification text/percentage seam. The notification rendering is
 * Android-framework, but the (text, percent) mapping from a resolved label +
 * fraction is pure and load-bearing — it's what the user reads in the status
 * bar. The label itself is now resolved from string resources (see
 * SyncPhaseLabels); this seam only formats the already-resolved text.
 */
class SyncNotifierTest {

    @Test
    fun `determinate fraction yields a percent and a labelled text`() {
        val (text, percent) = syncNotificationText("Syncing dust", 0.45f)
        assertEquals(45, percent)
        assertTrue("text shows the percent: $text", text.contains("45%"))
        assertTrue("text keeps the label: $text", text.contains("Syncing dust"))
    }

    @Test
    fun `indeterminate (null fraction) yields no percent and the bare label`() {
        val (text, percent) = syncNotificationText("Finalizing…", null)
        assertNull(percent)
        assertEquals("Finalizing…", text)
    }

    @Test
    fun `fraction is clamped between 0 and 100`() {
        assertEquals(100, syncNotificationText("x", 1.5f).second)
        assertEquals(0, syncNotificationText("x", -0.5f).second)
    }
}
