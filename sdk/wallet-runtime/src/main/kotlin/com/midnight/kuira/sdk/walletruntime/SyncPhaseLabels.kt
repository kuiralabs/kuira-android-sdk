package com.midnight.kuira.sdk.walletruntime

import androidx.annotation.StringRes
import com.midnight.kuira.sdk.SyncPhase

/**
 * Presentation-edge mapping from a [SyncPhase] (domain state) to its localized
 * label resource (#259). The `when` is exhaustive over the enum, so adding a new
 * phase without a label fails to compile — the centralization stays honest.
 *
 * This is the ONE place the phase→label binding lives; the background notifier
 * and (later) the in-app indicator both resolve through it rather than carrying
 * their own copies of the text.
 */
@StringRes
internal fun SyncPhase.labelRes(): Int = when (this) {
    SyncPhase.DustFull -> R.string.kuira_sync_dust
    SyncPhase.DustDelta -> R.string.kuira_sync_dust_delta
    SyncPhase.ShieldedRefresh -> R.string.kuira_sync_shielded
    SyncPhase.GenesisRebuild -> R.string.kuira_sync_genesis
    SyncPhase.Finalizing -> R.string.kuira_sync_finalizing
}
