// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.compact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the dust-spend `ctime` anchoring that keeps a contract-call balance inside the
 * node's `[tblock - grace, tblock]` validity window.
 *
 * Regression target: the idle-dust contract call. With the old `ctime = sync_time` code,
 * an idle wallet's frozen `sync_time` drifts past the grace window and the node rejects the
 * tx with custom error 171 (`OutOfDustValidityWindow`). These assertions fail against that
 * code and pass with [DustCtime.anchorMs].
 *
 * The complementary direction — error 170, a racing tip with unapplied dust events — is
 * protected by keeping a RECENT `sync_time` verbatim (the "in-window" cases below).
 */
class DustCtimeTest {

    // A fixed, readable epoch-ms tip (2024-01-01T00:00:00Z) so the arithmetic is obvious.
    private val tip = 1_704_067_200_000L

    @Test
    fun `stale sync time is clamped into the validity window near the tip`() {
        // Idle wallet: sync_time frozen 2h behind the tip — well past the 30-min safety
        // window, the case that produced node error 171. ctime must move forward to near
        // the tip (NOT stay at the stale sync_time).
        val staleSyncTime = tip - 2 * 60 * 60 * 1000L // 2 hours behind

        val ctime = DustCtime.anchorMs(dustSyncTimeMs = staleSyncTime, tipMs = tip)

        // Must NOT be the stale sync time (that's the bug); must be the clamped near-tip value.
        assertTrue("ctime must move forward off the stale sync_time", ctime > staleSyncTime)
        assertEquals("clamped to tip minus the backoff", tip - DustCtime.TIP_BACKOFF_MS, ctime)
        // And it must land strictly inside the window: ctime <= tip and ctime > tip - grace.
        assertTrue("ctime must be <= tip", ctime <= tip)
        assertTrue("ctime must sit above the stale sync_time, inside the window", ctime > staleSyncTime)
    }

    @Test
    fun `recent in-window sync time is kept verbatim (preserves the 170 fix)`() {
        // A tip that raced ahead with UNAPPLIED dust events is always recent; keeping
        // sync_time here is exactly the error-170 protection — do not clamp it.
        val recentSyncTime = tip - 5 * 60 * 1000L // 5 minutes behind, inside the 30-min window

        val ctime = DustCtime.anchorMs(dustSyncTimeMs = recentSyncTime, tipMs = tip)

        assertEquals("recent sync_time must be kept unchanged", recentSyncTime, ctime)
    }

    @Test
    fun `absent sync time anchors to the tip`() {
        assertEquals("zero sync_time -> tip", tip, DustCtime.anchorMs(dustSyncTimeMs = 0L, tipMs = tip))
        assertEquals("negative sync_time -> tip", tip, DustCtime.anchorMs(dustSyncTimeMs = -1L, tipMs = tip))
    }

    @Test
    fun `sync time at or ahead of the tip clamps down to the tip`() {
        // Clock skew or a freshly-synced state: sync_time must never exceed the tip
        // (ctime > tblock is the node's other rejection branch).
        assertEquals(tip, DustCtime.anchorMs(dustSyncTimeMs = tip, tipMs = tip))
        assertEquals(tip, DustCtime.anchorMs(dustSyncTimeMs = tip + 1000L, tipMs = tip))
    }

    @Test
    fun `sync time exactly at the safety-window edge is still kept`() {
        // Boundary: sync_time exactly `safetyWindow` behind the tip is treated as recent.
        val edgeSyncTime = tip - DustCtime.SAFETY_WINDOW_MS
        assertEquals(edgeSyncTime, DustCtime.anchorMs(dustSyncTimeMs = edgeSyncTime, tipMs = tip))
    }

    @Test
    fun `clamp never produces a ctime below the sync time`() {
        // With a custom, very small safety window AND a small drift, the clamp must not
        // invert below sync_time (which would resolve a stale predecessor root).
        val syncTime = tip - 40_000L // 40s behind
        val ctime = DustCtime.anchorMs(
            dustSyncTimeMs = syncTime,
            tipMs = tip,
            safetyWindowMs = 10_000L, // 10s window -> 40s drift counts as "drifted"
            tipBackoffMs = 60_000L, // backoff larger than the drift -> would underflow without the floor
        )
        assertTrue("ctime must never fall below sync_time", ctime >= syncTime)
        assertTrue("ctime must never exceed the tip", ctime <= tip)
    }
}
