// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.compact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the adaptive intent-TTL sizing — the regression that produced node custom error 182
 * (`IntentTtlTooFarInFuture`) when a fixed 30-min window overshot a tight chain's `global_ttl`.
 */
class IntentTtlTest {

    @Test
    fun `windowSeconds fits under a tight global_ttl with margin`() {
        // localnet ~100s: the window must land strictly under global_ttl or the node rejects it.
        val window = IntentTtl.windowSeconds(100L)
        assertTrue("window $window must be < global_ttl 100", window < 100L)
        assertEquals(85L, window) // 100 - 15s margin
    }

    @Test
    fun `windowSeconds caps a generous global_ttl at the 30-minute default`() {
        // PreProd ~1h: don't hand out a 1h TTL; cap at the historical 30-min window.
        assertEquals(30L * 60L, IntentTtl.windowSeconds(3600L))
    }

    @Test
    fun `windowSeconds falls back to the default window when global_ttl is unknown`() {
        assertEquals(30L * 60L, IntentTtl.windowSeconds(null))
        assertEquals(30L * 60L, IntentTtl.windowSeconds(0L))
    }

    @Test
    fun `windowSeconds halves a pathologically small global_ttl rather than underflow`() {
        // global_ttl below the margin: half the ceiling keeps the window positive and under it.
        val window = IntentTtl.windowSeconds(10L)
        assertEquals(5L, window)
        assertTrue(window < 10L)
    }

    @Test
    fun `ttlSeconds anchors to nowMs plus the adaptive window`() {
        val nowMs = 1_700_000_000_000L
        assertEquals(
            nowMs / 1000L + IntentTtl.windowSeconds(100L),
            IntentTtl.ttlSeconds(nowMs = nowMs, globalTtlSecs = 100L),
        )
    }
}
