// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.compact

/**
 * Sizes a transaction's intent TTL to the live chain limit.
 *
 * The node rejects an intent whose TTL sits more than the chain's `global_ttl`
 * ahead of chain time (custom error 182 / `IntentTtlTooFarInFuture`). That ceiling
 * varies enormously by network — a localnet runs ~100s, PreProd ~1h — so a fixed
 * 30-minute window silently overshoots a tight localnet by ~18x and every tx is
 * rejected. These helpers derive a window that fits whatever `global_ttl` the chain
 * actually reports ([ContractRuntime.ledgerGlobalTtlSeconds]).
 */
object IntentTtl {
    /**
     * Headroom (seconds) subtracted from `global_ttl`. Absorbs the gap between the
     * client's clock and the chain's latest block time (a few seconds of block lag)
     * plus the time the tx spends in flight before the node validates it, so the TTL
     * lands strictly inside the ceiling instead of on its edge.
     */
    private const val MARGIN_SECS = 15L

    /**
     * Upper bound on the window even when `global_ttl` is generous (e.g. PreProd's ~1h).
     * Matches the wallet's historical 30-minute default — long enough to cover proving +
     * submission + inclusion, short enough that a stuck tx expires promptly.
     */
    private const val MAX_WINDOW_SECS = 30L * 60L

    /**
     * Window used when `global_ttl` is unknown (decode failed). The historical default —
     * correct on chains with a generous ceiling and no worse than the old fixed behavior
     * on a tight one.
     */
    private const val FALLBACK_WINDOW_SECS = MAX_WINDOW_SECS

    private const val MILLIS_PER_SECOND = 1000L

    /**
     * TTL window in seconds that fits under [globalTtlSecs] with [MARGIN_SECS] of headroom,
     * capped at [MAX_WINDOW_SECS]. Falls back to half the ceiling if the margin would
     * underflow (a pathologically small `global_ttl`), and to [FALLBACK_WINDOW_SECS] when
     * the ceiling is unknown.
     */
    fun windowSeconds(globalTtlSecs: Long?): Long {
        if (globalTtlSecs == null || globalTtlSecs <= 0L) return FALLBACK_WINDOW_SECS
        val capped = (globalTtlSecs - MARGIN_SECS).coerceAtMost(MAX_WINDOW_SECS)
        return if (capped > 0L) capped else globalTtlSecs / 2
    }

    /**
     * Absolute intent TTL (seconds since epoch): [nowMs] anchored + the adaptive window.
     */
    fun ttlSeconds(nowMs: Long, globalTtlSecs: Long?): Long =
        nowMs / MILLIS_PER_SECOND + windowSeconds(globalTtlSecs)
}
