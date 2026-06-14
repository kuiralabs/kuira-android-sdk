package com.midnight.kuira.core.identity.sigil

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.midnight.kuira.core.identity.did.DidKeyGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for sigil identity persistence.
 *
 * Owns the `sigil_identity` SharedPreferences file that previously
 * lived inside `SigilPanelViewModel`. Pulled into `core:identity` so
 * non-UI consumers — `WalletSeedSource` (sdk:wallet-seed), future
 * agent runtimes — can query "is a sigil forged?" without taking a
 * dependency on `:sdk:dapp-ui`.
 *
 * **Schema:**
 *  - `did` — `did:key` derived from the passkey's compressed P-256 pubkey
 *  - `credentialId` — Credential Manager's opaque credential identifier
 *  - `publicKeyHex` — compressed P-256 pubkey, hex
 *  - `backupDismissed` — user chose "start fresh" over restoring a cloud
 *    backup; suppress the prompt on subsequent launches
 *
 * **Durability contract:** writes use `.commit()` rather than `.apply()`.
 * The restore flow SIGKILLs the process within a few ms of writing the
 * sigil triple (see `docs/security/SECURITY_NOTES.md` 2026-05-18 entry).
 * `.apply()`'s async write doesn't fsync before the kill, so the next
 * launch reads an empty file and the sigil pill shows "no sigil"
 * despite a successful restore. `.commit()` blocks until durable —
 * cost is negligible since this runs once per forge / restore, not on
 * a hot path.
 *
 * **Visibility:** all DID + credentialId + pubkey are PUBLIC material.
 * The passkey's private half never leaves the device's authenticator
 * store. SharedPreferences `MODE_PRIVATE` is sufficient.
 */
@Singleton
class SigilStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _snapshotFlow: MutableStateFlow<SigilSnapshot?> = MutableStateFlow(null)

    /**
     * Observable view of the persisted sigil triple — emits the current
     * value on subscribe + a new value after every [persistSigil] or
     * [clear] call.
     *
     * Cross-VM consumers ([WalletPanelViewModel] in particular) collect
     * this to react to sigil identity changes. Specifically, the wallet
     * panel transitions out of [WalletStatus.SigilRequired] when this
     * flow emits a non-null snapshot — without it, the wallet stays
     * stuck on "sigil required" until the user manually re-taps a
     * wallet action.
     *
     * Backed by an internal [MutableStateFlow] that's updated on every
     * write through this class. We don't rely on
     * [SharedPreferences.OnSharedPreferenceChangeListener] because it
     * fires on the main thread with no delivery guarantees under
     * pressure — this is more direct and predictable.
     */
    val snapshotFlow: StateFlow<SigilSnapshot?> = _snapshotFlow.asStateFlow()

    init {
        migrateLegacyP256DidIfPresent()
        // Seed the flow with whatever's on disk after migration. Every
        // subscriber gets this initial value immediately on collect.
        _snapshotFlow.value = readSnapshotFromPrefs()
    }

    /**
     * One-time migration: if the persisted DID is the legacy P-256
     * format (`did:key:zDn…`, derived from the passkey's pubkey
     * before the PRF-Ed25519 switch), wipe the sigil triple so the
     * sigil panel re-derives via [SigilIdentityProvider] on next
     * forge / sign-in. The wallet seed (PRF-derived) is unaffected
     * because it lives in `WalletSeedSource`, not here.
     *
     * Auto-replace silently per the migration decision (no real
     * users at the time of the switch). Orphaned `AuthorizationStore`
     * rows keyed by the old DID become unfindable; the user
     * re-authorizes on next access-key request.
     *
     * Runs in the constructor so every consumer of the store sees a
     * consistent post-migration view from the first call. Cheap
     * (one SharedPreferences read in the no-op case).
     */
    private fun migrateLegacyP256DidIfPresent() {
        val did = prefs.getString(KEY_DID, null) ?: return
        if (!DidKeyGenerator.isLegacyP256(did)) return
        Log.w(
            "SigilStateStore",
            "Legacy P-256 DID detected — clearing sigil prefs so next forge/sign-in re-derives via PRF→Ed25519",
        )
        // Direct prefs.edit (not clear()) because this runs BEFORE
        // _snapshotFlow is seeded — the post-init `_snapshotFlow.value
        // = readSnapshotFromPrefs()` line reads the now-empty file and
        // initializes the flow to null correctly.
        prefs.edit()
            .remove(KEY_DID)
            .remove(KEY_CREDENTIAL_ID)
            .remove(KEY_PUBLIC_KEY_HEX)
            .commit()
    }

    /**
     * True when a sigil has been forged (or restored). Cheapest gate —
     * checks the credential id field which is set last in the
     * persistence atomic, so its presence implies a complete triple.
     */
    fun hasSigil(): Boolean =
        prefs.getString(KEY_CREDENTIAL_ID, null) != null

    /** `did:key` for the forged sigil, or null when no sigil. */
    fun getDid(): String? = prefs.getString(KEY_DID, null)

    /** Credential Manager identifier, or null when no sigil. */
    fun getCredentialId(): String? = prefs.getString(KEY_CREDENTIAL_ID, null)

    /** Compressed P-256 pubkey hex, or null when no sigil. */
    fun getPublicKeyHex(): String? = prefs.getString(KEY_PUBLIC_KEY_HEX, null)

    /**
     * Snapshot of the persisted sigil triple, or null when no sigil
     * is forged. Convenience for consumers that want to load all three
     * fields atomically (the restore flow's hydration step).
     *
     * For reactive subscribers, prefer [snapshotFlow] — its current
     * value mirrors what this returns, and it emits on every update.
     */
    fun snapshot(): SigilSnapshot? = _snapshotFlow.value

    /**
     * Persist the sigil triple atomically — see the class-level
     * "durability contract" KDoc for the `.commit()` rationale.
     *
     * Updates [snapshotFlow] after the on-disk commit so every
     * observer (e.g. [WalletPanelViewModel] auto-retry) sees the new
     * identity as soon as the next coroutine continuation runs.
     *
     * **Overwrite guard (#15).** Writing a triple for a DIFFERENT
     * [credentialId] over an existing one is refused unless [replace] is
     * explicitly true — a guard against a forge/restore path silently
     * clobbering a live sigil (one wrong tap = sigil gone). Re-persisting
     * the SAME credential is always allowed (idempotent), so legitimate
     * re-establish flows — sign-in, the reuse branch of `establishSigil` —
     * never trip the guard. The only intentional replacement today is
     * "start fresh", which passes `replace = true`.
     *
     * @throws SigilOverwriteException when a different sigil already exists
     *   and [replace] is false.
     */
    fun persistSigil(
        did: String,
        credentialId: String,
        publicKeyHex: String,
        replace: Boolean = false,
    ) {
        val existing = prefs.getString(KEY_CREDENTIAL_ID, null)
        if (!replace && existing != null && existing != credentialId) {
            throw SigilOverwriteException(
                "Refusing to overwrite the existing sigil with a different credential " +
                    "(replace=false). Sign out first to forge or restore a new identity.",
            )
        }
        prefs.edit()
            .putString(KEY_DID, did)
            .putString(KEY_CREDENTIAL_ID, credentialId)
            .putString(KEY_PUBLIC_KEY_HEX, publicKeyHex)
            .commit()
        _snapshotFlow.value = SigilSnapshot(did, credentialId, publicKeyHex)
    }

    /**
     * Drop the sigil triple. The cloud backup (Block Store) is NOT
     * affected — only this local pointer to "we have a sigil." Use
     * during wallet reset / restore-from-fresh flows.
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_DID)
            .remove(KEY_CREDENTIAL_ID)
            .remove(KEY_PUBLIC_KEY_HEX)
            .commit()
        _snapshotFlow.value = null
    }

    /** True when the user chose "start fresh" from the backup prompt. */
    fun isBackupDismissed(): Boolean =
        prefs.getBoolean(KEY_BACKUP_DISMISSED, false)

    /**
     * Mark the cloud-backup prompt dismissed so future launches don't
     * keep re-asking. Cloud blob itself stays — the user can still
     * restore later from the [SigilStatus.None] sheet.
     */
    fun markBackupDismissed() {
        prefs.edit().putBoolean(KEY_BACKUP_DISMISSED, true).commit()
    }

    /** Read the sigil triple straight from prefs — used to seed the flow on init. */
    private fun readSnapshotFromPrefs(): SigilSnapshot? {
        val did = prefs.getString(KEY_DID, null) ?: return null
        val credentialId = prefs.getString(KEY_CREDENTIAL_ID, null) ?: return null
        val publicKeyHex = prefs.getString(KEY_PUBLIC_KEY_HEX, null) ?: return null
        return SigilSnapshot(did = did, credentialId = credentialId, publicKeyHex = publicKeyHex)
    }

    companion object {
        /**
         * SharedPreferences file name. Stays in sync with what
         * `SigilPanelViewModel` used pre-extraction so installs that
         * forged a sigil before this refactor read their existing state
         * without a migration step.
         */
        const val PREFS_NAME = "sigil_identity"
        const val KEY_DID = "did"
        const val KEY_CREDENTIAL_ID = "credentialId"
        const val KEY_PUBLIC_KEY_HEX = "publicKeyHex"
        const val KEY_BACKUP_DISMISSED = "backupDismissed"
    }
}

/** Immutable snapshot of a persisted sigil triple. */
data class SigilSnapshot(
    val did: String,
    val credentialId: String,
    val publicKeyHex: String,
)

/**
 * Thrown by [SigilStateStore.persistSigil] when a write would replace an
 * existing sigil with a DIFFERENT credential and `replace` was not set — the
 * data-layer backstop against silently clobbering a live identity.
 */
class SigilOverwriteException(message: String) : Exception(message)
