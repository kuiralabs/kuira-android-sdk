package com.midnight.kuira.dapp.sigil

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import com.midnight.kuira.sdk.walletseed.SigilSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

/**
 * Self-contained sigil-identity bookkeeper for [SigilStatusPanel].
 *
 * Owns two flows:
 *  - **Forge** ([forgeSigil]): create a passkey via Credential Manager,
 *    then derive the sigil DID via `SigilIdentityProvider`
 *    (`PRF(passkey, SIGIL_SALT)` → Ed25519 → `did:key:z6Mk…` in the
 *    default impl). Two biometric prompts on first run.
 *  - **Sign in** ([restoreSeed]): one biometric covers BOTH the
 *    sigil DID derivation AND the wallet seed pre-warm via the
 *    multi-salt PRF ceremony in `SigilSession.signIn`. The wallet
 *    panel's first refresh after sign-in hits SeedVault cache
 *    instead of running its own PRF ceremony — no second prompt.
 *    Falls back to two biometrics on authenticators that don't
 *    support multi-salt PRF. Host-app state is restored (best-effort)
 *    via the wallet's silent seed-keyed [com.midnight.kuira.sdk.MidnightWallet.fetchAppState].
 *
 * Backup is no longer a manual action here — host app state is written
 * automatically + silently by the wallet's seed-keyed path (#244). The
 * old PRF `AppStateBackup` backup/restore was retired (it clobbered the
 * same Block Store slot with a different key).
 *
 * **Held in BBoard's VM** (not migrated):
 *  - `authorizeAccessKey` — needs a `MidnightSdk` instance to derive
 *    the access key being authorized. The host bridges sigil + wallet
 *    for that flow.
 */
@HiltViewModel
class SigilPanelViewModel @Inject constructor(
    private val sigilSession: SigilSession,
    private val sigilStateStore: SigilStateStore,
    private val blockStoreStorage: BlockStoreBackupStorage,
    /**
     * The shared wallet provider — sign-in's app-state restore now reads the
     * silent **seed-keyed** Block Store blob via [com.midnight.kuira.sdk.MidnightWallet.fetchAppState]
     * (no biometric), replacing the retired PRF `AppStateBackup` path that
     * clobbered the same slot with a different key.
     */
    private val sdkProvider: MidnightSdkProvider,
    /**
     * Optional host-app hook for round-tripping additional state
     * (active matches, draft data, etc.) through sigil backup/restore.
     * `Optional<>` per Dagger's `@BindsOptionalOf` pattern in
     * [com.midnight.kuira.dapp.di.AppDataBackupModule] — apps that
     * don't implement the interface get an empty Optional, in which
     * case backups contain just the seed (BBoard behavior). Apps that
     * do (e.g. Kicks's `MatchStore`) fill in `appMetadata` and round-
     * trip their state across uninstall.
     */
    private val appDataProvider: Optional<AppDataBackupProvider>,
) : ViewModel() {

    private val _status = MutableStateFlow<SigilStatus>(SigilStatus.Initializing)
    val status: StateFlow<SigilStatus> = _status

    init {
        // Restore any previously-forged sigil so the panel pill shows the DID
        // immediately on next launch instead of "no sigil".
        loadPersistedSigil()
        // If still Initializing (no local sigil), probe Block Store async
        // for an existing cloud backup so the panel can offer
        // Restore-vs-Fresh before any wallet gets auto-created. Probe is
        // biometric-free; biometric only fires when the user later picks
        // Restore (PRF passkey).
        if (_status.value is SigilStatus.Initializing) {
            viewModelScope.launch { probeBlockStoreBackup() }
        }
    }

    /**
     * Off-thread probe — does Block Store currently hold a cloud-backup
     * blob for this app? Only meaningful when the local sigil prefs are
     * empty (fresh install / post-reinstall). Transitions
     * [SigilStatus.Initializing] → [SigilStatus.BackupAvailable] when a
     * blob is found and the user hasn't previously dismissed the prompt.
     * Otherwise → [SigilStatus.None].
     *
     * No biometric. The blob is encrypted at rest under the user's
     * passkey PRF — we never decrypt it here, just verify it exists.
     */
    private suspend fun probeBlockStoreBackup() {
        if (sigilStateStore.isBackupDismissed()) {
            Log.i(TAG, "Backup prompt previously dismissed — skipping probe")
            _status.value = SigilStatus.None
            return
        }
        try {
            val blob = blockStoreStorage.retrieve()
            if (blob != null && blob.isNotEmpty() &&
                _status.value is SigilStatus.Initializing) {
                Log.i(TAG, "Block Store backup detected (${blob.size} bytes) — offering Restore vs Start Fresh")
                _status.value = SigilStatus.BackupAvailable
            } else {
                Log.i(TAG, "No Block Store backup — settling on None")
                if (_status.value is SigilStatus.Initializing) {
                    _status.value = SigilStatus.None
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Block Store probe failed (${e.javaClass.simpleName}); settling on None: ${e.message}")
            if (_status.value is SigilStatus.Initializing) {
                _status.value = SigilStatus.None
            }
        }
    }

    /**
     * User chose "Start fresh" from the [SigilStatus.BackupAvailable]
     * prompt: they acknowledge the cloud backup but want to proceed
     * without restoring. Persist a flag so future launches don't keep
     * nagging, and move to [SigilStatus.None] so the wallet panel
     * unblocks and auto-bootstraps a fresh wallet.
     *
     * The cloud backup itself is **not** deleted — user can still tap
     * "restore from cloud" later in the [SigilStatus.None] sheet if
     * they change their mind. The flag just controls the auto-prompt.
     */
    fun dismissBackup() {
        // SigilStateStore.markBackupDismissed uses .commit() — same
        // durability contract as persistSigil. The status transition +
        // wallet-panel re-evaluation that follow happen on the next
        // composition frame; we want the flag durable before any of
        // that races with a process death.
        sigilStateStore.markBackupDismissed()
        _status.value = SigilStatus.None
        Log.i(TAG, "Backup dismissed — sigil status → None, wallet panel unblocked")
    }

    /**
     * Create a passkey + derive its `did:key`. Triggers the platform's
     * Credential Manager UI on [activity]; the user picks an authenticator
     * (device biometric / hardware key / etc.) and authorizes the create.
     *
     * Emits [SigilStatus.Creating] → [SigilStatus.Forged] on success,
     * [SigilStatus.Error] on cancellation / RP-id mismatch / other failure.
     * Successful forges are persisted via [prefs] so the next session
     * resumes in Forged state without re-running this flow.
     */
    fun forgeSigil(activity: FragmentActivity) {
        viewModelScope.launch {
            _status.value = SigilStatus.Creating("Setting up sigil…")
            try {
                // Reuse-or-forge in one delegated ceremony: establishSigil signs
                // in to an existing sigil for the canonical rpId if one exists
                // (so a second Kuira app converges on the shared identity rather
                // than minting a duplicate), and forges a NEW one only when the
                // platform reports no credential. On forge the P-256 pubkey is
                // kept for KeyAuthorization + the Forged pill; on reuse it's empty
                // (a GET can't return it), matching the sign-in path.
                val result = sigilSession.establishSigil(activity)

                Log.i(TAG, "Sigil ${if (result.reused) "reused" else "forged"} — DID: ${result.did}")
                Log.i(TAG, "  Credential ID: ${result.credentialId}")
                persistSigil(
                    did = result.did,
                    credentialId = result.credentialId,
                    publicKeyHex = result.publicKeyHex,
                )
                _status.value = SigilStatus.Forged(
                    did = result.did,
                    credentialId = result.credentialId,
                    publicKeyHex = result.publicKeyHex,
                )
            } catch (e: Exception) {
                Log.e(TAG, "forgeSigil failed", e)
                _status.value = SigilStatus.Error(e.message ?: "Sigil setup failed")
            }
        }
    }

    /**
     * Sign in with an existing passkey — the post-PRF replacement for
     * the old "restore from cloud" flow.
     *
     * **What changed.** Pre-PRF this method:
     *   1. Pulled an encrypted blob from Block Store
     *   2. Decrypted it to recover (seed, sigil triple, appMetadata)
     *   3. Wrote the seed into SeedVault
     *   4. SIGKILL'd the process so the wallet panel rebuilt cleanly
     *
     * Post-PRF none of (1)–(4) is necessary for the SIGIL ITSELF:
     *   - The seed derives from `PRF(passkey, SEED_SALT)` — `WalletSeedSource`
     *     handles that on the next wallet action, no blob needed.
     *   - The sigil DID derives from `PRF(passkey, SIGIL_SALT)` via
     *     `SigilIdentityProvider` — that's what step 1 below does.
     *   - The SDK lives on a stable PRF-derived seed regardless of how
     *     long ago the user last authenticated, so no SIGKILL needed.
     *
     * The Block Store blob is now narrower in scope: it carries only
     * the host-app's metadata (Kicks's match state, etc.). Step 2
     * below pulls it through to the registered `AppDataBackupProvider`
     * when a blob is available; absent or v1-legacy blobs are silently
     * tolerated.
     */
    fun restoreSeed(activity: FragmentActivity) {
        viewModelScope.launch {
            try {
                // Step 1 — sigil DID + wallet seed in ONE biometric
                // ceremony (multi-salt PRF: SIGIL_SALT first, SEED_SALT
                // second). SigilSession persists the sigil triple and
                // pre-warms SeedVault internally, so the wallet panel's
                // next refresh hits cache.
                //
                // Fallback when the authenticator doesn't support
                // multi-salt PRF: SigilSession transparently runs a
                // second ceremony (two biometrics, same correctness).
                Log.i(TAG, "Starting sign-in via SigilSession (one ceremony, two salts)…")
                val derivation = sigilSession.signIn(activity)
                Log.i(TAG, "  DID: ${derivation.did}")
                Log.i(TAG, "  Credential ID: ${derivation.credentialId}")
                _status.value = SigilStatus.Forged(
                    did = derivation.did,
                    credentialId = derivation.credentialId,
                    publicKeyHex = "",  // assertion doesn't return pubkey
                )

                // Step 2 — app-state restore (best-effort) via the wallet's
                // silent **seed-keyed** path (replaces the retired PRF
                // AppStateBackup). awaitSdk() waits for the wallet panel to build
                // the SDK now that sign-in unblocked it — no extra biometric,
                // SeedVault is already pre-warmed. Skipped when no provider is
                // bound or there's no recoverable blob.
                val provider = appDataProvider.orElse(null)
                if (provider == null) {
                    Log.i(TAG, "  No AppDataBackupProvider bound — skipping app-state restore")
                    return@launch
                }
                val appMetadata = sdkProvider.awaitSdk().wallet.fetchAppState()
                if (appMetadata == null || appMetadata.isEmpty()) {
                    Log.i(TAG, "  No recoverable app state — nothing to hand off")
                    return@launch
                }
                try {
                    Log.i(TAG, "  App metadata: ${appMetadata.size}B — handing to AppDataBackupProvider")
                    runCatching { provider.restore(appMetadata) }
                        .onFailure {
                            Log.e(
                                TAG,
                                "appDataProvider.restore failed; app data not restored " +
                                    "but the sigil is. Host can retry.",
                                it,
                            )
                        }
                } finally {
                    appMetadata.fill(0)
                }
            } catch (e: BackupException) {
                Log.e(TAG, "Sign-in failed: ${e.message}", e)
                _status.value = SigilStatus.Error(e.message ?: "Sign-in failed")
            } catch (e: Exception) {
                Log.e(TAG, "Sign-in failed", e)
                _status.value = SigilStatus.Error(e.message ?: "Sign-in failed")
            }
        }
    }

    // ── Persistence ──

    private fun loadPersistedSigil() {
        val snapshot = sigilStateStore.snapshot() ?: return
        _status.value = SigilStatus.Forged(
            did = snapshot.did,
            credentialId = snapshot.credentialId,
            publicKeyHex = snapshot.publicKeyHex,
        )
        Log.i(TAG, "Loaded persisted sigil: did=${snapshot.did.take(30)}…")
    }

    private fun persistSigil(did: String, credentialId: String, publicKeyHex: String) {
        // SigilStateStore.persistSigil uses .commit() — durability
        // contract documented there. Cost is negligible since this
        // runs once per forge / restore, not on a hot path.
        sigilStateStore.persistSigil(did = did, credentialId = credentialId, publicKeyHex = publicKeyHex)
    }

    companion object {
        private const val TAG = "SigilPanel"

        // Pref schema (file name, key names) lives on
        // [com.midnight.kuira.core.identity.sigil.SigilStateStore]
        // since the same schema is consumed from non-UI modules
        // (e.g. WalletSeedSource).
    }
}
