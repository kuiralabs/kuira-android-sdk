package com.midnight.kuira.sdk.walletruntime

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

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

    // ── Incoming-funds detection (#264 inbound) — the nightReceipts seam ──

    private fun n(v: Long) = BigInteger.valueOf(v)

    @Test
    fun `nightReceipts swallows the initial sync ramp, then emits real receipts`() = runTest {
        // (totalNight, idle). Initial sync ramps 0→100 while NOT idle, then settles. After that
        // two receipts arrive, each landing during its own sync cycle (not-idle → idle).
        val deltas = flowOf(
            n(0) to false,    // initial-sync staging — swallowed (initial sync not done)
            n(40) to false,   // "
            n(100) to false,  // "
            n(100) to true,   // initial sync settles → increases from here are receipts
            n(150) to false,  // receipt #1 (+50), mid its own sync
            n(150) to true,   // settles → no change
            n(225) to true,   // receipt #2 (+75)
        ).nightReceipts().toList()
        assertEquals(listOf(n(50), n(75)), deltas)
    }

    @Test
    fun `nightReceipts ignores a decrease (our own send)`() = runTest {
        val deltas = flowOf(
            n(100) to true, // baseline (initial sync done) — no emit
            n(70) to true,  // balance dropped (a send) — not a receipt
        ).nightReceipts().toList()
        assertEquals(emptyList<BigInteger>(), deltas)
    }
}
