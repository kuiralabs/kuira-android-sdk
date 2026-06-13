package com.midnight.kuira.sdk

/**
 * Optional cross-device backup of host **app state** (e.g. Kicks match
 * witnesses, draft data) — distinct from the wallet seed/identity (carried by
 * the passkey, reconstructed locally) and from the dust checkpoint
 * ([DustCloudBackup]).
 *
 * The blob is encrypted under a key derived from the wallet seed
 * (`SeedDerivedKeyDeriver.deriveAppStateBackupKey`), so backup and restore need
 * only the in-hand seed — no per-backup passkey/biometric ceremony — which is
 * what lets app-state backup run **automatically** in the background. The
 * concrete implementation lives in `sdk:wallet-runtime`.
 *
 * Best-effort + idempotent by contract: [uploadAppState] hash-guards a redundant
 * upload (skips the write when unchanged); [fetchAppState] returns null on any
 * problem so a caller degrades cleanly to "no app state".
 */
interface AppStateCloudBackup {
    /** The decrypted app metadata previously backed up, or null if none / unavailable. */
    suspend fun fetchAppState(): ByteArray?

    /** Encrypt + store [appMetadata]; skips the write when unchanged since the last upload. */
    suspend fun uploadAppState(appMetadata: ByteArray)
}
