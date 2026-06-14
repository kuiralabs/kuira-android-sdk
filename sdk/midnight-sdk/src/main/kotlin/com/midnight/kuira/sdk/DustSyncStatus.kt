package com.midnight.kuira.sdk

/**
 * Observable state of dust syncing, exposed by [MidnightWallet.dustSyncStatus].
 *
 * Until #235 progress was only a per-call `syncDust(onProgress)` lambda — fine
 * for the in-app `WalletSyncIndicator`, but a *background* observer (the
 * foreground-service Live-Update notification, or any UI not driving the sync
 * itself) needs an observable source. This is that source.
 *
 * The `eventsProcessed == -1` sentinel from the dust replay maps to
 * `Syncing(fraction = null, …)` — the "replaying" tail where a determinate
 * percentage isn't available.
 */
sealed interface DustSyncStatus {
    /** No sync running (initial state, and after a session lock drops the SDK). */
    data object Idle : DustSyncStatus

    /**
     * A sync is in progress.
     *
     * @property fraction 0f..1f when a count is known; null = indeterminate
     *   (the native replay tail).
     * @property eventsProcessed raw count from the indexer stream (-1 = replaying).
     * @property totalEvents the chain's dust-event tip (indexer `maxId`), 0 if unknown.
     * @property label short status text for the UI / notification.
     */
    data class Syncing(
        val fraction: Float?,
        val eventsProcessed: Int,
        val totalEvents: Int,
        val label: String,
    ) : DustSyncStatus

    /** Sync finished successfully — the cached dust balance is current. */
    data object UpToDate : DustSyncStatus

    /** Sync failed (network/indexer/replay). [message] is a short reason. */
    data class Failed(val message: String) : DustSyncStatus
}
