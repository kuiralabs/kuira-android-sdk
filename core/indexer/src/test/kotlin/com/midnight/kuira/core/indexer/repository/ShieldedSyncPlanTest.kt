package com.midnight.kuira.core.indexer.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [shieldedSyncPlan] — the pure decision seam behind : the shielded
 * (zswap) sync routing that lets a refresh resume from a saved checkpoint instead of re-replaying
 * every event from genesis.
 *
 * `tip` is the chain's highest zswap event id (the `maxId` probe); `cursor` is our checkpoint's
 * last applied event id. The contract:
 *  - no cursor (first sync) → FULL
 *  - tip null (probe timeout) → FULL
 *  - tip < cursor (chain reset) → FULL
 *  - tip == cursor → AT_TIP (caught up — skip the genesis re-replay)
 *  - tip > cursor → DELTA (replay only the new events)
 */
class ShieldedSyncPlanTest {

    @Test
    fun `no checkpoint cursor is FULL (first sync)`() {
        assertEquals(ShieldedSyncPlan.FULL, shieldedSyncPlan(tip = 500L, cursor = null))
        assertEquals(ShieldedSyncPlan.FULL, shieldedSyncPlan(tip = null, cursor = null))
    }

    @Test
    fun `null tip with a checkpoint is FULL (probe timed out, safe fallback)`() {
        assertEquals(ShieldedSyncPlan.FULL, shieldedSyncPlan(tip = null, cursor = 100L))
    }

    @Test
    fun `tip below cursor is FULL (chain reset below us)`() {
        assertEquals(ShieldedSyncPlan.FULL, shieldedSyncPlan(tip = 90L, cursor = 100L))
        assertEquals(ShieldedSyncPlan.FULL, shieldedSyncPlan(tip = -1L, cursor = 100L))
    }

    @Test
    fun `tip equal to cursor is AT_TIP (the fix — caught up, skip re-replay)`() {
        assertEquals(ShieldedSyncPlan.AT_TIP, shieldedSyncPlan(tip = 100L, cursor = 100L))
    }

    @Test
    fun `tip one above cursor is DELTA`() {
        assertEquals(ShieldedSyncPlan.DELTA, shieldedSyncPlan(tip = 101L, cursor = 100L))
    }

    @Test
    fun `tip far above cursor is DELTA`() {
        assertEquals(ShieldedSyncPlan.DELTA, shieldedSyncPlan(tip = 100_000L, cursor = 100L))
    }
}
