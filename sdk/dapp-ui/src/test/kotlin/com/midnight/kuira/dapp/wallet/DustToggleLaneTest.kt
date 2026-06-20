package com.midnight.kuira.dapp.wallet

import com.midnight.kuira.dapp.backup.BackupLaneState
import com.midnight.kuira.sdk.CloudBackupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dust-backup Switch must reflect the user's DURABLE choice, not the live cloud-sync status.
 * Otherwise a balance refresh (which re-runs the dust sync) churns the status — UpToDate→Syncing→
 * Idle — and the Switch flips off→on and spins, the bug these pin closed via [dustToggleLane].
 */
class DustToggleLaneTest {

    private fun toggle(state: BackupLaneState) = state as BackupLaneState.Toggle

    @Test
    fun `a background sync does not move or spin an enabled toggle (the refresh-churn bug)`() {
        // Enabled, NOT user-enabling. As a balance refresh drives the status through these, the
        // Switch must stay ON and never spin — including the momentary Idle/Syncing it passes through.
        val churn = listOf(
            CloudBackupStatus.UpToDate(atEpochMs = 0L),
            CloudBackupStatus.Syncing, // the background sync — must be SILENT on the toggle
            CloudBackupStatus.Idle,    // the momentarily re-emitted default — must NOT flip it off
            CloudBackupStatus.Offline,
        )
        for (status in churn) {
            val t = toggle(dustToggleLane(enabled = true, enabling = false, status = status))
            assertEquals("on must stay true for status=$status", true, t.on)
            assertEquals("busy must be false (silent background sync) for status=$status", false, t.busy)
            assertNull("no error detail for status=$status", t.detail)
        }
    }

    @Test
    fun `a user-initiated enable is the only thing that spins the toggle`() {
        // enabling wins regardless of enabled/status — on + spinner.
        val t = toggle(dustToggleLane(enabled = false, enabling = true, status = CloudBackupStatus.Idle))
        assertEquals(true, t.on)
        assertEquals(true, t.busy)
    }

    @Test
    fun `opted-out (not enabled) is off regardless of live status`() {
        val statuses = listOf(
            CloudBackupStatus.Idle,
            CloudBackupStatus.Syncing, // a background sync must NOT show on a disabled toggle
            CloudBackupStatus.UpToDate(atEpochMs = 0L),
            CloudBackupStatus.NeedsConsent,
        )
        for (status in statuses) {
            val t = toggle(dustToggleLane(enabled = false, enabling = false, status = status))
            assertEquals("off for status=$status", false, t.on)
            assertEquals(false, t.busy)
        }
    }

    @Test
    fun `a persistent failure surfaces as a quiet detail - still on, never spinning`() {
        val t = toggle(
            dustToggleLane(enabled = true, enabling = false, status = CloudBackupStatus.Failed("boom")),
        )
        assertEquals("still the user's choice — on", true, t.on)
        assertEquals("no spinner for a background failure", false, t.busy)
        assertNotNull("a failure shows a detail", t.detail)
    }
}
