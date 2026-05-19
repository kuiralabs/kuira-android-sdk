package com.midnight.kuira.dapp.backup

/**
 * Hook for host apps to round-trip arbitrary state through the Sigil
 * cloud-backup pipeline. The seed payload is always included; this
 * interface is the seam for everything *else* the app wants to
 * preserve across uninstall / device hop (active matches, draft
 * messages, contract-specific session data, etc.).
 *
 * Wire-up:
 *  1. Implement [snapshot] + [restore] in a host-app class.
 *  2. Bind the implementation as `@Provides @Singleton AppDataBackupProvider`
 *     in the host's Hilt graph.
 *  3. The SDK's `SigilPanelViewModel` discovers the binding via
 *     `Optional<AppDataBackupProvider>` and includes the snapshot
 *     bytes in `SigilBackup.appMetadata` on the next backup, then
 *     hands restored bytes back via [restore] on the next sigil
 *     restore.
 *
 * Apps that don't bind a provider get a default empty Optional — the
 * Sigil backup contains only the seed (current BBoard behavior).
 *
 * **Encoding:** opaque to the SDK. Producers pick a format (JSON,
 * Protobuf, custom binary) and ideally include a schema version so a
 * future revision can decline to restore an unrecognized blob rather
 * than crash on parse. See `MatchStore.snapshotBytes` for an example.
 *
 * **Sensitivity:** the bytes are encrypted under the user's passkey
 * PRF before reaching Block Store — same protection that secures the
 * seed itself. Implementations don't need to encrypt at this layer.
 *
 * Both methods are `suspend` so implementations can do IO (read
 * EncryptedSharedPreferences, query a Room DB, etc.) without forcing
 * blocking work on the caller's thread.
 */
interface AppDataBackupProvider {

    /**
     * Produce the app-data blob to include in the next sigil backup.
     * Return `null` when there's nothing to back up — the SDK then
     * passes `appMetadata = null` to `SigilBackup.backup`, keeping the
     * blob lean.
     *
     * Called from the backup pipeline, inside a coroutine on
     * `Dispatchers.IO` by convention but not enforced. Implementations
     * should not assume any particular dispatcher.
     */
    suspend fun snapshot(): ByteArray?

    /**
     * Apply a previously-captured blob. Called from the sigil restore
     * pipeline immediately after the seed is unsealed. The provider
     * decides whether to replace, merge, or skip — the SDK doesn't
     * impose semantics. Errors should be caught and logged inside the
     * implementation: a malformed blob shouldn't fail the whole sigil
     * restore.
     */
    suspend fun restore(bytes: ByteArray)
}
