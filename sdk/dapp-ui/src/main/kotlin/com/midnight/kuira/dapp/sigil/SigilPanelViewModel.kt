package com.midnight.kuira.dapp.sigil

import android.app.Activity
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.dapp.BuildConfig
import com.midnight.kuira.core.identity.backup.AppStateBackup
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.sigil.SigilIdentityProvider
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import com.midnight.kuira.sdk.walletseed.SigilSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Optional
import javax.inject.Inject

/**
 * Self-contained sigil-identity bookkeeper for [SigilStatusPanel].
 *
 * Owns three flows:
 *  - **Forge** ([forgeSigil]): create a passkey via Credential Manager,
 *    then derive the sigil DID via [SigilIdentityProvider]
 *    (`PRF(passkey, SIGIL_SALT)` → Ed25519 → `did:key:z6Mk…` in the
 *    default impl). Two biometric prompts on first run.
 *  - **Sign in** ([restoreSeed]): one biometric covers BOTH the
 *    sigil DID derivation AND the wallet seed pre-warm via the
 *    multi-salt PRF ceremony in `SigilSession.signIn`. The wallet
 *    panel's first refresh after sign-in hits SeedVault cache
 *    instead of running its own PRF ceremony — no second prompt.
 *    Falls back to two biometrics on authenticators that don't
 *    support multi-salt PRF. Optionally restores host-app state
 *    via [AppStateBackup] after the sigil is forged.
 *  - **Backup** ([backupSeed]): write host-app state (not the seed)
 *    to Block Store via [AppStateBackup].
 *
 * **Held in BBoard's VM** (not migrated):
 *  - `authorizeAccessKey` — needs a `MidnightSdk` instance to derive
 *    the access key being authorized. The host bridges sigil + wallet
 *    for that flow.
 */
@HiltViewModel
class SigilPanelViewModel @Inject constructor(
    private val passkeyManager: PasskeyManager,
    private val sigilIdentityProvider: SigilIdentityProvider,
    private val sigilSession: SigilSession,
    private val sigilStateStore: SigilStateStore,
    private val appStateBackup: AppStateBackup,
    private val blockStoreStorage: BlockStoreBackupStorage,
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
     * Diagnostic — derive the SeedDeriver PRF output for the user's
     * passkey and emit the 32-byte hex to logcat (tag `PrfProbe`).
     * Used to verify that PRF is deterministic across Kuira ecosystem
     * apps that share an RP via `assetlinks.json`, before relying on
     * it as the wallet seed.
     *
     * Run on two installs of any Kuira app (Kicks + BBoard, two
     * emulators sharing one Google account, etc.); identical outputs
     * mean PRF-derived seed is viable.
     *
     * No-op outside debug builds. The action is gated by
     * `BuildConfig.DEBUG` so production builds don't ship a passkey
     * prompt that leaks 32 bytes of derived material to logcat.
     */
    fun probePrfDeterminism(activity: Activity) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "probePrfDeterminism: refusing to run outside debug builds")
            return
        }
        viewModelScope.launch {
            try {
                val entropy = SeedDeriver.derivePrfEntropy(activity, passkeyManager)
                val hex = entropy.joinToString("") { "%02x".format(it) }
                Log.i("PrfProbe", "SEED_SALT PRF output (32 bytes): $hex")
                Log.i("PrfProbe", "Compare this across devices/apps; identical = deterministic.")
                entropy.fill(0)
            } catch (e: Exception) {
                Log.e("PrfProbe", "probe failed: ${e.message}", e)
            }
        }
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
     * Probe the passkey's PRF extension. Builds a deterministic salt from a
     * versioned purpose string, runs an assertion twice with the same salt,
     * and reports whether the outputs match — used during canary to confirm
     * an authenticator supports CTAP2's hmac-secret extension before relying
     * on it for backup.
     *
     * **Logging gates.** Two tiers:
     *
     *  - **Always-on (`Log.i`):** PASS / FAIL determinism verdict, plus the
     *    salt (a SHA-256 of a public purpose string — not sensitive). Safe
     *    for production.
     *  - **Debug-only (`debugLog`):** the raw PRF output bytes in hex. These
     *    ARE the key material the backup blob is encrypted under — combined
     *    with the salt, anyone with logcat access can decrypt a captured
     *    blob. Gated behind `BuildConfig.DEBUG`, which is `const val false`
     *    in release builds; R8 dead-code-eliminates the whole conditional
     *    including the `.toHex()` allocations.
     */
    fun testPrf(activity: Activity) {
        viewModelScope.launch {
            try {
                val challenge = ByteArray(PRF_CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
                val salt = MessageDigest.getInstance("SHA-256")
                    .digest(PRF_BACKUP_PURPOSE.toByteArray(Charsets.UTF_8))

                Log.i(TAG, "Testing PRF with salt: ${salt.toHex()} (purpose=$PRF_BACKUP_PURPOSE)")
                val first = passkeyManager.authenticateWithPrf(
                    activity = activity, challenge = challenge, prfSalt = salt,
                )
                val firstOut = first.prfOutput
                if (firstOut == null || firstOut.size != PRF_OUTPUT_BYTES) {
                    Log.w(TAG, "PRF returned null/wrong size — extension not supported on this authenticator")
                    debugLog(TAG) { "Full response: ${first.assertionResponseJson}" }
                    return@launch
                }
                debugLog(TAG) { "PRF first output (${firstOut.size} bytes): ${firstOut.toHex()}" }

                // Same salt second auth — same authenticator should produce
                // the same output. CTAP2 hmac-secret is defined deterministic;
                // any mismatch here is either an authenticator bug or a Credential
                // Manager quirk we want to know about.
                Log.i(TAG, "Testing PRF determinism (same salt, second auth)…")
                val second = passkeyManager.authenticateWithPrf(
                    activity = activity, challenge = challenge, prfSalt = salt,
                )
                val secondOut = second.prfOutput
                if (secondOut == null) {
                    Log.w(TAG, "PRF second authenticate returned null")
                    return@launch
                }
                val deterministic = firstOut.contentEquals(secondOut)
                Log.i(TAG, "PRF determinism: ${if (deterministic) "PASS (same output)" else "FAIL (different output)"}")
                debugLog(TAG) { "  First:  ${firstOut.toHex()}" }
                debugLog(TAG) { "  Second: ${secondOut.toHex()}" }
            } catch (e: Exception) {
                Log.e(TAG, "PRF test failed", e)
            }
        }
    }

    /**
     * Back up the current sigil identity + the locally-stored seed to Google
     * Block Store. Pipeline: load seed via [seedVault] (biometric) →
     * derive a PRF-encrypted AES key from the passkey →
     * [sigilBackup.backup] uploads the encrypted blob.
     *
     * No-op if status isn't [SigilStatus.Forged] (without a sigil there's
     * nothing to bind the encryption to). Errors log + leave status
     * unchanged — backup is a side operation, doesn't transition state.
     *
     * `appMetadata` is sourced from the host-provided
     * [AppDataBackupProvider] when one is bound. When no provider is
     * bound (BBoard et al.), the field stays null and only the seed
     * goes up.
     */
    fun backupSeed(activity: FragmentActivity) {
        viewModelScope.launch {
            val sigil = _status.value as? SigilStatus.Forged
            if (sigil == null) {
                Log.w(TAG, "backupSeed skipped — sigil status is ${_status.value::class.simpleName}, not Forged")
                return@launch
            }
            try {
                // Post-PRF the backup blob carries ONLY app-specific
                // state — the seed and the sigil identity are both
                // PRF-derived from the passkey and reconstructed
                // locally on any device. So this flow is purely about
                // round-tripping the host's appMetadata (Kicks's match
                // state, future agent state, etc.).
                Log.i(TAG, "Starting AppState backup for DID: ${sigil.did}")
                val appMetadata: ByteArray? = appDataProvider.orElse(null)?.let { provider ->
                    runCatching { provider.snapshot() }
                        .onFailure { Log.w(TAG, "appDataProvider.snapshot failed; backup will store empty appMetadata", it) }
                        .getOrNull()
                }
                if (appMetadata != null) {
                    Log.i(TAG, "  App metadata: ${appMetadata.size}B from host-provided AppDataBackupProvider")
                } else {
                    Log.i(TAG, "  No app metadata — backup is a sigil-exists sentinel only")
                }

                appStateBackup.backup(activity = activity, appMetadata = appMetadata)
                Log.i(
                    TAG,
                    "Backup SUCCESS — appState stored in Block Store " +
                        "(${if (appMetadata != null) "${appMetadata.size}B appMetadata" else "no appMetadata"})",
                )
            } catch (e: BackupException) {
                Log.e(TAG, "Backup failed: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
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
     *     [SigilIdentityProvider] — that's what step 1 below does.
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

                // Step 2 — app-state restore (best-effort). Skipped
                // when no provider bound or no recoverable blob.
                val provider = appDataProvider.orElse(null)
                if (provider == null) {
                    Log.i(TAG, "  No AppDataBackupProvider bound — skipping app-state restore")
                    return@launch
                }
                val restored = try {
                    appStateBackup.restore(activity)
                } catch (e: BackupException) {
                    Log.i(TAG, "  App-state restore unavailable: ${e.message}")
                    return@launch
                }
                try {
                    if (restored.appMetadata.isEmpty()) {
                        Log.i(TAG, "  Backup contained no appMetadata — nothing to hand off")
                        return@launch
                    }
                    Log.i(TAG, "  App metadata: ${restored.appMetadata.size}B — handing to AppDataBackupProvider")
                    runCatching { provider.restore(restored.appMetadata) }
                        .onFailure {
                            Log.e(
                                TAG,
                                "appDataProvider.restore failed; app data not restored " +
                                    "but the sigil is. Host can retry.",
                                it,
                            )
                        }
                } finally {
                    restored.wipe()
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

        /** Random challenge size used by [testPrf]. 32 bytes — the spec recommendation. */
        private const val PRF_CHALLENGE_BYTES = 32

        /** Expected PRF output size from CTAP2's hmac-secret extension. */
        private const val PRF_OUTPUT_BYTES = 32

        /**
         * Purpose-bound salt source for the PRF probe + the backup pipeline.
         * Versioned so a future rotation can ship `v2` and not collide with
         * already-deployed encrypted blobs. Must match what `SigilBackup`
         * uses internally — they're SHA-256'd into the same salt.
         */
        private const val PRF_BACKUP_PURPOSE = "kuira:backup:v1"

        // Pref schema (file name, key names) lives on
        // [com.midnight.kuira.core.identity.sigil.SigilStateStore]
        // since the same schema is consumed from non-UI modules
        // (e.g. WalletSeedSource).
    }
}

/**
 * `0x05ff…` style hex render for byte arrays. Kept local to the panel module
 * — only used for diagnostic logging. Lowercase to match the rest of the
 * Kuira tooling (CLI output, indexer dumps) so values grep cleanly across logs.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Inline `Log.i` wrapper that's a no-op in release builds.
 *
 * `BuildConfig.DEBUG` is a `const val false` generated by AGP for release
 * variants, so the compiler folds `if (false) …` to nothing and R8 then
 * strips the whole conditional — including the lazy [message] lambda
 * (eliminated as unused) and any `.toHex()` / string-template work inside
 * it. Zero overhead, zero leakage in release APKs.
 *
 * Use this for log lines that carry sensitive intermediate values (raw
 * PRF outputs, derived key bytes, etc.) where a canary-grade trace is
 * valuable but production logging is not. For genuinely public info
 * (DID, credentialId, pubkey hex) just call `Log.i` directly.
 */
private inline fun debugLog(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) Log.i(tag, message())
}
