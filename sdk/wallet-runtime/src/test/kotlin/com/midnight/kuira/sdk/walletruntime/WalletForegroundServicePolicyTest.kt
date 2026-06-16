package com.midnight.kuira.sdk.walletruntime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lifecycle matrix for [decideForegroundService] (#261-264, generalizing #235) — the
 * wallet foreground service's start/stop policy. Pure function, so every
 * (activeOps × syncing × locked × running) combination that matters is pinned here.
 * The notification shows whenever an operation is in flight OR the wallet is syncing
 * (foreground OR background); only a session lock suppresses it. The sync-only rows
 * (activeOps = false) are the #235 regression guard.
 */
class WalletForegroundServicePolicyTest {

    // ── Sync dimension (activeOps = false) — preserves the #235 behaviour ──

    @Test
    fun `syncing starts the service`() {
        assertEquals(
            ForegroundServiceAction.Start,
            decideForegroundService(activeOps = false, syncing = true, locked = false, running = false),
        )
    }

    @Test
    fun `syncing while already running updates`() {
        assertEquals(
            ForegroundServiceAction.Update,
            decideForegroundService(activeOps = false, syncing = true, locked = false, running = true),
        )
    }

    @Test
    fun `idle and not running is a no-op`() {
        assertEquals(
            ForegroundServiceAction.None,
            decideForegroundService(activeOps = false, syncing = false, locked = false, running = false),
        )
    }

    // ── Operation dimension (syncing = false) — the #261 generalization ──

    @Test
    fun `an active operation starts the service`() {
        assertEquals(
            ForegroundServiceAction.Start,
            decideForegroundService(activeOps = true, syncing = false, locked = false, running = false),
        )
    }

    @Test
    fun `an active operation while running updates`() {
        assertEquals(
            ForegroundServiceAction.Update,
            decideForegroundService(activeOps = true, syncing = false, locked = false, running = true),
        )
    }

    @Test
    fun `nothing in flight while running stops`() {
        assertEquals(
            ForegroundServiceAction.Stop,
            decideForegroundService(activeOps = false, syncing = false, locked = false, running = true),
        )
    }

    // ── Both dimensions at once ──

    @Test
    fun `an operation and a sync together start the service`() {
        assertEquals(
            ForegroundServiceAction.Start,
            decideForegroundService(activeOps = true, syncing = true, locked = false, running = false),
        )
    }

    // ── Lock suppresses everything (privacy) ──

    @Test
    fun `locked always tears down (running) and never starts (not running)`() {
        assertEquals(
            ForegroundServiceAction.Stop,
            decideForegroundService(activeOps = true, syncing = true, locked = true, running = true),
        )
        assertEquals(
            ForegroundServiceAction.None,
            decideForegroundService(activeOps = true, syncing = true, locked = true, running = false),
        )
    }
}
