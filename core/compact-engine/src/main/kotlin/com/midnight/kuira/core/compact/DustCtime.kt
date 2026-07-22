// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.compact

/**
 * Computes the `ctime` a dust spend anchors to, keeping it inside the node's dust
 * validity window so the spend is neither rejected as stale nor as out-of-window.
 *
 * The node validates every dust action against `ctime ∈ [tblock - grace, tblock]`
 * (`MalformedTransaction::OutOfDustValidityWindow`, node error 171; the check lives in
 * the ledger's `dust.rs` and reads `params.dust.dust_grace_period`, default 3h). On top
 * of the window check, the spend's committed dust root must equal `dust.root_history.get(ctime)`.
 *
 * Two failure modes pull `ctime` in opposite directions, so neither a fixed tip nor a
 * fixed sync-time anchor is safe alone:
 *
 *  - **Tip anchor → error 170 (`InvalidDustSpendProof`).** On an active chain the tip
 *  races ahead of our locally-replayed dust state between sync and balance. Anchoring
 *  `ctime` to the tip resolves a *newer* root than the one our proof commits to, so the
 *  node rejects the spend. The cure was to anchor to the dust state's `sync_time`
 *  (the block time of the last replayed dust event), whose predecessor lookup lands on
 *  *our* root.
 *
 *  - **Sync-time anchor → error 171 (`OutOfDustValidityWindow`).** When dust events are
 *  sparse (an idle wallet on a long-running chain), `sync_time` freezes at the last
 *  event's time — hours old — while the tip advances. `ctime = sync_time` then drifts
 *  more than `grace` behind `tblock` and the node rejects it as out-of-window.
 *
 * The resolution: keep `sync_time` while it is *recent* (a racing tip with unapplied
 * events is always recent → preserves the 170 fix), and clamp toward the tip only once
 * `sync_time` has drifted toward the edge of the window (idle dust). When dust is at the
 * tip our replayed root already *is* the current dust root, so any `ctime ≥ sync_time`
 * resolves to it via the predecessor lookup — clamping to just under the tip stays in the
 * window without resolving a foreign root.
 *
 * This is a pure function so the window logic is unit-testable without a live chain; the
 * residual indexer-lag race is covered by the caller's error-171 re-sync-and-retry.
 */
object DustCtime {

    /**
     * Conservative grace assumed when the live `dust_grace_period` isn't plumbed through.
     * The ledger default is 3h; we treat the *usable* window as a fraction of that so a
     * slightly stale sync-time (which still resolves to our root and is preferable for
     * 170 safety) is retained, while a genuinely idle one is clamped well before the real
     * 3h edge. There is no native getter for `dust_grace_period` (only `global_ttl`), so
     * this is intentionally smaller than any realistic grace.
     */
    const val SAFETY_WINDOW_MS = 30L * 60L * 1000L // 30 minutes

    /**
     * Headroom (ms) below the tip when clamping an idle sync-time. The chosen `ctime` must
     * be `≤ tblock` of the block that actually *includes* the tx, which is a few blocks past
     * the tip we read. Backing off a small margin keeps `ctime ≤ tblock` even as the chain
     * advances during proving + submission, staying inside the window's upper edge.
     */
    const val TIP_BACKOFF_MS = 30L * 1000L // 30 seconds

    /**
     * The `ctime` to anchor a dust spend to.
     *
     * @param dustSyncTimeMs the dust state's `sync_time` in ms — the block time of its last
     *  replayed dust event; `<= 0` means the state reported no sync time.
     * @param tipMs the indexer's current block (tip) time in ms.
     * @param safetyWindowMs how recent `sync_time` may be and still be kept verbatim; beyond
     *  this it's treated as idle/drifted and clamped toward the tip. Defaults to
     *  [SAFETY_WINDOW_MS]; injectable so a caller with the real `dust_grace_period` can pass
     *  a window derived from it.
     * @param tipBackoffMs headroom below the tip when clamping (see [TIP_BACKOFF_MS]).
     */
    fun anchorMs(
        dustSyncTimeMs: Long,
        tipMs: Long,
        safetyWindowMs: Long = SAFETY_WINDOW_MS,
        tipBackoffMs: Long = TIP_BACKOFF_MS,
    ): Long {
        // No usable sync time → the tip is always in-window ([tblock - grace, tblock]).
        if (dustSyncTimeMs <= 0L) return tipMs

        // Sync time at/ahead of the tip (clock skew, or freshly synced): the tip itself
        // is the safe upper bound and resolves to our root.
        if (dustSyncTimeMs >= tipMs) return tipMs

        // Recent sync time → keep it verbatim. A tip that raced ahead with UNAPPLIED dust
        // events is always recent (the events are seconds/minutes old), so keeping
        // sync_time here is exactly the error-170 protection.
        if (tipMs - dustSyncTimeMs <= safetyWindowMs) return dustSyncTimeMs

        // Drifted/idle sync time → clamp toward the tip, backing off so ctime stays
        // ≤ the eventual inclusion block. Our replayed root is the current dust root
        // (no events since), so this clamped ctime still resolves to it.
        val clamped = tipMs - tipBackoffMs
        // Never clamp below sync_time (would resolve to a stale predecessor root); never
        // above the tip. Both bounds are already satisfied here (sync_time < tip - window
        // < tip - backoff for any sane window > backoff), but guard explicitly so a small
        // injected window can't invert the result.
        return clamped.coerceIn(dustSyncTimeMs, tipMs)
    }
}
