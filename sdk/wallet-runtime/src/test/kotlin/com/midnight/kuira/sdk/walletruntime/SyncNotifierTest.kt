package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.sdk.DustSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification text/percentage seam (#235). The notification rendering is
 * Android-framework, but the (text, percent) mapping from a [DustSyncStatus.Syncing]
 * is pure and load-bearing — it's what the user reads in the status bar.
 */
class SyncNotifierTest {

    @Test
    fun `determinate fraction yields a percent and a labelled text`() {
        val (text, percent) = DustSyncStatus.Syncing(0.45f, 45, 100, "Syncing dust").toNotificationText()
        assertEquals(45, percent)
        assertTrue("text shows the percent: $text", text.contains("45%"))
        assertTrue("text keeps the label: $text", text.contains("Syncing dust"))
    }

    @Test
    fun `indeterminate (null fraction) yields no percent and the bare label`() {
        val (text, percent) = DustSyncStatus.Syncing(null, -1, 0, "Finalizing dust…").toNotificationText()
        assertNull(percent)
        assertEquals("Finalizing dust…", text)
    }

    @Test
    fun `fraction is clamped between 0 and 100`() {
        assertEquals(100, DustSyncStatus.Syncing(1.5f, 0, 0, "x").toNotificationText().second)
        assertEquals(0, DustSyncStatus.Syncing(-0.5f, 0, 0, "x").toNotificationText().second)
    }
}
