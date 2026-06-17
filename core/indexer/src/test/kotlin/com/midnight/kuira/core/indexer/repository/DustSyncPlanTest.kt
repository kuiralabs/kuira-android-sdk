package com.midnight.kuira.core.indexer.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [dustSyncPlan] — the pure decision seam behind #279 (#2): the
 * dust delta-sync routing that keeps an at-tip refresh from blocking the full
 * `DELTA_FIRST_EVENT_TIMEOUT_MS` (30s) on a live subscription that has nothing to
 * send.
 *
 * `tip` is the chain's highest dust-event id (the reorg-guard `maxId` probe);
 * `lastEventId` is our checkpoint cursor. The contract:
 *  - `tip < cursor` → REORG  (chain reset under us)
 *  - `tip == cursor` → AT_TIP (caught up — short-circuit, no network)
 *  - `tip > cursor` → DELTA  (real events to fetch)
 *  - `tip == null` → DELTA  (probe timed out → fall back, preserve old behavior)
 */
class DustSyncPlanTest {

    @Test
    fun `tip equal to cursor is AT_TIP (the fix — caught up, skip the 30s wait)`() {
        assertEquals(DustSyncPlan.AT_TIP, dustSyncPlan(tip = 5L, lastEventId = 5L))
    }

    @Test
    fun `tip equal to cursor at genesis (zero) is AT_TIP`() {
        assertEquals(DustSyncPlan.AT_TIP, dustSyncPlan(tip = 0L, lastEventId = 0L))
    }

    @Test
    fun `tip one above cursor is DELTA (a single new event)`() {
        assertEquals(DustSyncPlan.DELTA, dustSyncPlan(tip = 6L, lastEventId = 5L))
    }

    @Test
    fun `tip far above cursor is DELTA`() {
        assertEquals(DustSyncPlan.DELTA, dustSyncPlan(tip = 100L, lastEventId = 5L))
    }

    @Test
    fun `tip one below cursor is REORG (chain reset under us)`() {
        assertEquals(DustSyncPlan.REORG, dustSyncPlan(tip = 4L, lastEventId = 5L))
    }

    @Test
    fun `tip far below cursor is REORG`() {
        assertEquals(DustSyncPlan.REORG, dustSyncPlan(tip = 0L, lastEventId = 10L))
    }

    @Test
    fun `tip of -1 (chain has no dust events) below a real cursor is REORG`() {
        // The reorg guard maps "no dust events on chain" to maxId = -1; against any
        // real checkpoint cursor (>= 0) that's a reset → rebuild from genesis.
        assertEquals(DustSyncPlan.REORG, dustSyncPlan(tip = -1L, lastEventId = 0L))
    }

    @Test
    fun `null tip (probe timed out) is DELTA (fall back, behavior unchanged)`() {
        assertEquals(DustSyncPlan.DELTA, dustSyncPlan(tip = null, lastEventId = 5L))
    }

    @Test
    fun `null tip at a zero cursor is DELTA`() {
        assertEquals(DustSyncPlan.DELTA, dustSyncPlan(tip = null, lastEventId = 0L))
    }
}
