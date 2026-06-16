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
    fun `nightReceipts emits the delta on each NEW HIGH (the receipt), skipping the baseline`() = runTest {
        // First tick = baseline (already-loaded balance) → no emit. Each later NEW HIGH is a
        // receipt, emitted regardless of sync state (airdrops always land mid-sync).
        val deltas = flowOf(n(100), n(100), n(150), n(150), n(225))
            .nightReceipts().toList()
        assertEquals(listOf(n(50), n(75)), deltas)
    }

    @Test
    fun `nightReceipts ignores a dip-and-recover (flaky shielded resync), only a real new high counts`() = runTest {
        // The shielded subscription resets to a low value on reconnect then re-syncs back up;
        // that recovery must NOT read as a receipt — only a genuinely higher balance does.
        val deltas = flowOf(
            n(100), // baseline
            n(150), // new high → +50
            n(0),   // shielded reset to 0 (a dip, not a send)
            n(150), // recover to the prior high → NO emit
            n(200), // genuine new high → +50
        ).nightReceipts().toList()
        assertEquals(listOf(n(50), n(50)), deltas)
    }

    @Test
    fun `nightReceipts ignores a decrease (our own send)`() = runTest {
        val deltas = flowOf(n(100), n(70)).nightReceipts().toList()
        assertEquals(emptyList<BigInteger>(), deltas)
    }
}
