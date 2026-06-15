package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.sdk.SyncPhase
import com.midnight.kuira.sdk.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lifecycle matrix for [decideSyncService] (#235) — the sync service's start/stop
 * policy. Pure function, so every (status × locked × running) combination that
 * matters is pinned here. The notification shows whenever syncing (foreground OR
 * background); only a session lock suppresses it.
 */
class DustSyncServiceStartPolicyTest {

    private val syncing: SyncStatus = SyncStatus.Syncing(0.5f, 5, 10, SyncPhase.DustFull)
    private val idle: SyncStatus = SyncStatus.Idle
    private val done: SyncStatus = SyncStatus.UpToDate

    @Test
    fun `syncing starts the service`() {
        assertEquals(SyncServiceAction.Start, decideSyncService(syncing, locked = false, running = false))
    }

    @Test
    fun `syncing while already running updates`() {
        assertEquals(SyncServiceAction.Update, decideSyncService(syncing, locked = false, running = true))
    }

    @Test
    fun `locked always tears down (running) and never starts (not running)`() {
        assertEquals(SyncServiceAction.Stop, decideSyncService(syncing, locked = true, running = true))
        // Even syncing, a locked session must not start the FGS.
        assertEquals(SyncServiceAction.None, decideSyncService(syncing, locked = true, running = false))
    }

    @Test
    fun `completion or idle while running stops the service`() {
        assertEquals(SyncServiceAction.Stop, decideSyncService(done, locked = false, running = true))
        assertEquals(SyncServiceAction.Stop, decideSyncService(idle, locked = false, running = true))
    }

    @Test
    fun `idle and not running is a no-op`() {
        assertEquals(SyncServiceAction.None, decideSyncService(idle, locked = false, running = false))
        assertEquals(SyncServiceAction.None, decideSyncService(done, locked = false, running = false))
    }

    @Test
    fun `failed status while running stops the service`() {
        val failed: SyncStatus = SyncStatus.Failed("boom")
        assertEquals(SyncServiceAction.Stop, decideSyncService(failed, locked = false, running = true))
        assertEquals(SyncServiceAction.None, decideSyncService(failed, locked = false, running = false))
    }
}
