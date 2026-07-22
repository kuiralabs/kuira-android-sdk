package com.midnight.kuira.sdk.walletruntime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lifecycle matrix for [decideForegroundService] (#261-264, generalizing ) — the
 * wallet foreground service's start/stop policy. Pure function, so every
 * (activeOps × syncing × hardLocked × running) combination that matters is pinned here.
 * The notification shows whenever an operation is in flight OR the wallet is syncing
 * (foreground OR background); only a HARD lock (the SDK actually torn down) suppresses it —
 * a soft-lock (app backgrounded, SDK kept alive) must NOT. The sync-only rows
 * (activeOps = false) are the regression guard.
 */
class WalletForegroundServicePolicyTest {

    // ── Sync dimension (activeOps = false) — preserves the behaviour ──

    @Test
    fun `syncing starts the service`() {
        assertEquals(
            ForegroundServiceAction.Start,
            decideForegroundService(activeOps = false, syncing = true, hardLocked = false, running = false),
        )
    }

    @Test
    fun `syncing while already running updates`() {
        assertEquals(
            ForegroundServiceAction.Update,
            decideForegroundService(activeOps = false, syncing = true, hardLocked = false, running = true),
        )
    }

    @Test
    fun `idle and not running is a no-op`() {
        assertEquals(
            ForegroundServiceAction.None,
            decideForegroundService(activeOps = false, syncing = false, hardLocked = false, running = false),
        )
    }

    // ── Operation dimension (syncing = false) — the generalization ──

    @Test
    fun `an active operation starts the service`() {
        assertEquals(
            ForegroundServiceAction.Start,
            decideForegroundService(activeOps = true, syncing = false, hardLocked = false, running = false),
        )
    }

    @Test
    fun `an active operation while running updates`() {
        assertEquals(
            ForegroundServiceAction.Update,
            decideForegroundService(activeOps = true, syncing = false, hardLocked = false, running = true),
        )
    }

    @Test
    fun `nothing in flight while running stops`() {
        assertEquals(
            ForegroundServiceAction.Stop,
            decideForegroundService(activeOps = false, syncing = false, hardLocked = false, running = true),
        )
    }

    // ── Both dimensions at once ──

    @Test
    fun `an operation and a sync together start the service`() {
        assertEquals(
            ForegroundServiceAction.Start,
            decideForegroundService(activeOps = true, syncing = true, hardLocked = false, running = false),
        )
    }

    // ── HARD lock suppresses everything (privacy) ──

    @Test
    fun `hard-locked always tears down (running) and never starts (not running)`() {
        assertEquals(
            ForegroundServiceAction.Stop,
            decideForegroundService(activeOps = true, syncing = true, hardLocked = true, running = true),
        )
        assertEquals(
            ForegroundServiceAction.None,
            decideForegroundService(activeOps = true, syncing = true, hardLocked = true, running = false),
        )
    }

    // ── Soft-lock must NOT tear down a live operation (#261-264 regression guard) ──

    @Test
    fun `a soft-lock (not hard) keeps the service up while an operation is in flight`() {
        // App backgrounded + soft-locked (SDK still alive) while a send/contract op runs: the FGS
        // must KEEP the process alive. hardLocked = false, so an active op still drives Update/Start
        // — the regression that -264 process-survival depends on not having.
        assertEquals(
            ForegroundServiceAction.Update,
            decideForegroundService(activeOps = true, syncing = false, hardLocked = false, running = true),
        )
        assertEquals(
            ForegroundServiceAction.Start,
            decideForegroundService(activeOps = true, syncing = false, hardLocked = false, running = false),
        )
    }

}
