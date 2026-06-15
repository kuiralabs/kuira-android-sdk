package com.midnight.kuira.sdk

/**
 * Observable state of wallet syncing, exposed by [MidnightWallet.syncStatus].
 *
 * Covers EVERY heavy sync the wallet runs — dust replay, shielded-balance
 * refresh, and the genesis rebuild — not just dust. [Syncing.phase] names which
 * one (a [SyncPhase] state, NOT display text) so a single observer (the
 * foreground-service Live-Update notification, or any UI not driving the sync
 * itself) can reflect whichever phase is in flight; the presentation layer maps
 * the phase to a localized label.
 *
 * Until #235 progress was only a per-call `syncDust(onProgress)` lambda — fine
 * for the in-app `WalletSyncIndicator`, but a *background* observer needs an
 * observable source. This is that source.
 *
 * The `eventsProcessed == -1` sentinel from the dust replay maps to
 * `Syncing(fraction = null, phase = Finalizing)` — the "replaying" tail where a
 * determinate percentage isn't available.
 */
sealed interface SyncStatus {
    /** No sync running (initial state, and after a session lock drops the SDK). */
    data object Idle : SyncStatus

    /**
     * A sync is in progress.
     *
     * @property fraction 0f..1f when a count is known; null = indeterminate
     *   (the native replay tail, or a phase with no event count).
     * @property eventsProcessed raw count from the indexer stream (-1 = replaying).
     * @property totalEvents the chain's dust-event tip (indexer `maxId`), 0 if unknown.
     * @property phase which sync is running — the presentation layer resolves the
     *   user-facing label from this.
     */
    data class Syncing(
        val fraction: Float?,
        val eventsProcessed: Int,
        val totalEvents: Int,
        val phase: SyncPhase,
    ) : SyncStatus

    /** Sync finished successfully — the cached balances are current. */
    data object UpToDate : SyncStatus

    /** Sync failed (network/indexer/replay). [message] is a short reason. */
    data class Failed(val message: String) : SyncStatus
}
