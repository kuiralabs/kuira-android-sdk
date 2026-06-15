package com.midnight.kuira.sdk.walletruntime

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.midnight.kuira.sdk.SyncPhase

/**
 * Presentation-edge mapping from a [SyncPhase] (domain state) to its localized
 * label resource (#259). The `when` is exhaustive over the enum, so adding a new
 * phase without a label fails to compile — the centralization stays honest.
 *
 * This is the ONE place the phase→label binding lives; the background notifier
 * AND the in-app wallet-panel indicator both resolve through it rather than
 * carrying their own copies of the text (#259).
 */
@StringRes
fun SyncPhase.labelRes(): Int = when (this) {
    SyncPhase.DustFull -> R.string.kuira_sync_dust
    SyncPhase.DustDelta -> R.string.kuira_sync_dust_delta
    SyncPhase.ShieldedRefresh -> R.string.kuira_sync_shielded
    SyncPhase.GenesisRebuild -> R.string.kuira_sync_genesis
    SyncPhase.Finalizing -> R.string.kuira_sync_finalizing
}

/**
 * Per-phase notification large-icon (#235) — the right-side thumbnail that
 * represents the CURRENT sync state (dust motes / refresh arrows / stacked
 * layers), distinct from the left-side brand K (app identity). Exhaustive `when`
 * keeps it honest as phases are added. Themed via `@color/kuira_sync_accent`.
 */
@DrawableRes
fun SyncPhase.largeIconRes(): Int = when (this) {
    SyncPhase.DustFull, SyncPhase.DustDelta, SyncPhase.Finalizing -> R.drawable.kuira_phase_dust
    SyncPhase.ShieldedRefresh -> R.drawable.kuira_phase_refresh
    SyncPhase.GenesisRebuild -> R.drawable.kuira_phase_genesis
}

/**
 * Short, phase-specific text for the status-bar chip (#235) — the capsule is
 * tiny, so one word per phase ("Dust" / "Balances" / "Genesis" / "Finalizing"),
 * combined with the percent at the call site.
 */
@StringRes
fun SyncPhase.chipLabelRes(): Int = when (this) {
    SyncPhase.DustFull, SyncPhase.DustDelta -> R.string.kuira_sync_chip_dust
    SyncPhase.ShieldedRefresh -> R.string.kuira_sync_chip_balances
    SyncPhase.GenesisRebuild -> R.string.kuira_sync_chip_genesis
    SyncPhase.Finalizing -> R.string.kuira_sync_chip_finalizing
}
