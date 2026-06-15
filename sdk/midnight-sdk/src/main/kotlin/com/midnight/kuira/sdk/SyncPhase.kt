package com.midnight.kuira.sdk

/**
 * The single source of truth for which kind of wallet sync is running.
 *
 * This is a *state*, not a display string — the SDK never emits user-facing
 * English. The presentation layer (the background notification, the in-app
 * `WalletSyncIndicator`) maps each phase to a localized label via Android string
 * resources, so the same phase shown in two places resolves through one resource.
 *
 * Carried by [SyncStatus.Syncing.phase].
 */
enum class SyncPhase {
    /** Full dust replay (cold/large stream — [MidnightWallet.syncDust]). */
    DustFull,

    /** Incremental dust delta on the persisted checkpoint (proactive tip-advance). */
    DustDelta,

    /** Shielded-balance resync + dust delta ([MidnightWallet.refresh]). */
    ShieldedRefresh,

    /** Clean rebuild from genesis (error-170 recovery — stale roots). */
    GenesisRebuild,

    /** The native replay tail where a determinate percentage isn't available. */
    Finalizing,
}
