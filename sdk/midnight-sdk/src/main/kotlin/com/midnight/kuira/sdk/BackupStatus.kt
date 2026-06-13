package com.midnight.kuira.sdk

/**
 * Cloud-backup status for one lane (dust checkpoint or app state), surfaced by
 * [MidnightWallet.backupStatus] so the UI can show what's happening instead of a
 * silent log. The SDK stays best-effort/non-crashing — this is observation only.
 *
 * Identity/seed recovery is NOT a lane here: it's the passkey (GPM-synced),
 * sourced from the sigil store at the app layer, not the wallet.
 */
sealed interface CloudBackupStatus {
    /** Nothing attempted yet, or nothing to back up (no coordinator / no data). */
    data object Idle : CloudBackupStatus

    /** An upload is in flight. */
    data object Syncing : CloudBackupStatus

    /** Last upload succeeded at [atEpochMs] (wall-clock, for a "backed up · 2m ago" label). */
    data class UpToDate(val atEpochMs: Long) : CloudBackupStatus

    /** Drive consent hasn't been granted — the UI should offer "enable". Dust only. */
    data object NeedsConsent : CloudBackupStatus

    /** Last upload failed; [message] is for logs/diagnostics, not necessarily UI. */
    data class Failed(val message: String) : CloudBackupStatus
}

/**
 * Snapshot of every cloud-backup lane the wallet drives. Emitted by
 * [MidnightWallet.backupStatus] on each backup attempt.
 */
data class BackupStatusSnapshot(
    val dust: CloudBackupStatus = CloudBackupStatus.Idle,
    val appData: CloudBackupStatus = CloudBackupStatus.Idle,
)
