package com.midnight.kuira.dapp.sigil

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.dapp.BuildConfig
import com.midnight.kuira.core.auth.BiometricGate
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.backup.SigilBackup
import com.midnight.kuira.core.identity.did.DidKeyGenerator
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Mirrors `WalletPanelViewModel`'s shape and lifecycle. This step (#2 of
 * the migration) brings over the **forge** flow: create a passkey via
 * Credential Manager, derive a `did:key` from the resulting P-256 public
 * key, persist the triple (DID + credentialId + publicKeyHex) so the
 * sigil survives app restarts.
 *
 * **Subsequent steps will migrate:**
 *  - Backup to cloud (`SigilBackup` + Block Store)
 *  - Restore from cloud
 *  - Test PRF
 *
 * **Stays in BBoard's VM** (not migrated):
 *  - `authorizeAccessKey` — needs a `MidnightSdk` instance to derive the
 *    access key being authorized, which the sigil panel doesn't (and
 *    shouldn't) own. The host bridges sigil + wallet for that flow.
 */
@HiltViewModel
class SigilPanelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val passkeyManager: PasskeyManager,
    private val walletKeyManager: WalletKeyManager,
    private val biometricGate: BiometricGate,
    private val seedVault: SeedVault,
    private val sigilStateStore: SigilStateStore,
    private val sigilBackup: SigilBackup,
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
    fun forgeSigil(activity: Activity) {
        viewModelScope.launch {
            _status.value = SigilStatus.Creating("Creating passkey…")
            try {
                // Random 16-byte user id. In a production app this would come
                // from the host's user system so the same human gets the same
                // userId on a re-install; for the canary panel we generate per
                // forge and the persisted state carries the resulting DID.
                val userId = ByteArray(USER_ID_BYTES).also { SecureRandom().nextBytes(it) }
                val result = passkeyManager.createPasskey(
                    activity = activity,
                    userId = userId,
                    userName = hostAppLabel(),
                )
                val did = DidKeyGenerator.fromCompressedP256(result.publicKey.compressed)
                val publicKeyHex = result.publicKey.compressedHex()

                // Full diagnostic block: DID, credential ID, raw P-256 pubkey hex.
                // All public material — DID and pubkey by definition, credential ID
                // is just an authenticator-side opaque identifier (not a secret).
                // Useful for cross-referencing with the authenticator's stored
                // credentials or verifying the DID-from-pubkey derivation by hand.
                Log.i(TAG, "Sigil forged — DID: $did")
                Log.i(TAG, "  Credential ID: ${result.credentialId}")
                Log.i(TAG, "  P-256 pubkey: $publicKeyHex")
                persistSigil(did = did, credentialId = result.credentialId, publicKeyHex = publicKeyHex)
                _status.value = SigilStatus.Forged(
                    did = did,
                    credentialId = result.credentialId,
                    publicKeyHex = publicKeyHex,
                )
            } catch (e: Exception) {
                Log.e(TAG, "forgeSigil failed", e)
                _status.value = SigilStatus.Error(e.message ?: "Passkey creation failed")
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
                Log.i(TAG, "Starting PRF backup of the SeedVault-stored seed…")
                Log.i(TAG, "  DID: ${sigil.did}")
                Log.i(TAG, "  Credential ID: ${sigil.credentialId}")
                val plaintext = seedVault.loadSeed(activity)
                val entropy = plaintext.mnemonicEntropy.copyOf()
                val bip39Seed = plaintext.bip39Seed.copyOf()
                plaintext.wipe()
                Log.i(TAG, "  Seed loaded from SeedVault: ${bip39Seed.size}B (entropy: ${entropy.size}B)")

                // Capture host-app state alongside the seed. Provider
                // is allowed to return null (nothing to round-trip)
                // and to throw — we log and treat exceptions as "no
                // app metadata", since a backup-without-app-data still
                // protects the user's seed (the load-bearing material).
                val appMetadata: ByteArray? = appDataProvider.orElse(null)?.let { provider ->
                    runCatching { provider.snapshot() }
                        .onFailure { Log.w(TAG, "appDataProvider.snapshot failed; backup will omit app data", it) }
                        .getOrNull()
                }
                if (appMetadata != null) {
                    Log.i(TAG, "  App metadata: ${appMetadata.size}B from host-provided AppDataBackupProvider")
                }

                sigilBackup.backup(
                    activity = activity,
                    entropy = entropy,
                    bip39Seed = bip39Seed,
                    did = sigil.did,
                    credentialId = sigil.credentialId,
                    publicKeyHex = sigil.publicKeyHex,
                    appMetadata = appMetadata,
                )
                Log.i(
                    TAG,
                    "Backup SUCCESS — seed + sigil identity stored in Block Store " +
                        "(${if (appMetadata != null) "with ${appMetadata.size}B appMetadata" else "no appMetadata"})",
                )
            } catch (e: BackupException) {
                Log.e(TAG, "Backup failed: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
            }
        }
    }

    /**
     * Restore sigil + seed from Block Store. Pipeline: pull encrypted blob →
     * PRF-authenticate against the passkey to derive the AES key → decrypt
     * → restore the sigil triple into our prefs + emit Forged.
     *
     * The restored seed bytes are wiped after the function returns — this
     * VM doesn't surface them to consumers; the wallet panel's SeedVault
     * is the only place that retains seed material across calls.
     */
    fun restoreSeed(activity: FragmentActivity) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Starting PRF restore…")
                val restored = sigilBackup.restore(activity)
                try {
                    Log.i(TAG, "Restore SUCCESS!")
                    Log.i(TAG, "  Restored seed size: ${restored.bip39Seed.size} bytes")
                    val did = restored.did
                    if (did != null) {
                        val credentialId = restored.credentialId.orEmpty()
                        val publicKeyHex = restored.publicKeyHex.orEmpty()
                        Log.i(TAG, "  DID: $did")
                        Log.i(TAG, "  Credential ID: $credentialId")
                        Log.i(TAG, "  Public key: $publicKeyHex")
                        persistSigil(did = did, credentialId = credentialId, publicKeyHex = publicKeyHex)
                        // Overwrite the fresh seed (or write into an empty
                        // vault) so the wallet panel rebootstraps with the
                        // recovered wallet on next launch. Without this the
                        // sigil DID is restored but the wallet stays on the
                        // freshly-generated address with 0 balance.
                        restoreSeedIntoVault(activity, restored.entropy, restored.bip39Seed)

                        // Hand the host-app blob (if any) to the registered
                        // provider BEFORE the SIGKILL below. Code after
                        // killProcess never runs, so this MUST happen here
                        // — past sigil restores logged the blob's size in
                        // a block placed after the kill, which never
                        // executed and silently dropped the bytes.
                        val meta = restored.appMetadata
                        if (meta != null) {
                            Log.i(TAG, "  App metadata: ${meta.size}B — handing to AppDataBackupProvider")
                            appDataProvider.orElse(null)?.let { provider ->
                                runCatching { provider.restore(meta) }
                                    .onFailure {
                                        Log.e(
                                            TAG,
                                            "appDataProvider.restore failed; app data not restored " +
                                                "but seed + sigil are. Continuing to relaunch.",
                                            it,
                                        )
                                    }
                            } ?: Log.w(
                                TAG,
                                "appMetadata present (${meta.size}B) but no AppDataBackupProvider bound — dropping",
                            )
                        }

                        _status.value = SigilStatus.Forged(
                            did = did,
                            credentialId = credentialId,
                            publicKeyHex = publicKeyHex,
                        )
                        Log.i(TAG, "  Sigil identity + wallet seed restored — restarting app for clean reload")
                        // CRITICAL: kill the process before any other code (or
                        // the user) can interact with the wallet panel. The
                        // wallet panel's in-memory SDK is still built on the
                        // pre-restore seed; SeedVault now holds the recovered
                        // seed. If the user funds and then backs up in this
                        // desynced window, the backup captures the WRONG seed
                        // and the funded wallet becomes unrecoverable. Killing
                        // the process guarantees the next launch bootstraps
                        // from SeedVault and the panel + storage agree.
                        //
                        // UX: queue a launcher intent BEFORE killing so Android
                        // auto-relaunches a fresh process. Without this the
                        // app just vanishes and the user has to find the icon
                        // again — that looks indistinguishable from a crash.
                        // startActivity dispatches the intent to system_server
                        // synchronously; when we SIGKILL ourselves a moment
                        // later, system_server already has the intent on its
                        // queue and starts a new process to fulfill it.
                        val relaunch = activity.packageManager
                            .getLaunchIntentForPackage(activity.packageName)
                            ?.apply {
                                addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                )
                            }
                        if (relaunch != null) activity.startActivity(relaunch)
                        activity.finishAffinity()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } else {
                        Log.w(TAG, "Restore returned a seed but no sigil triple — leaving status unchanged")
                    }
                } finally {
                    restored.wipe()
                }
            } catch (e: BackupException) {
                Log.e(TAG, "Restore failed: ${e.message}", e)
                _status.value = SigilStatus.Error(e.message ?: "Restore failed")
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                _status.value = SigilStatus.Error(e.message ?: "Restore failed")
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

    /**
     * Label the passkey prompt shows for the credential — derived from the host
     * app's manifest `android:label` so every dApp embedding this panel sees
     * its own name (Kicks → "Midnight Kicks", BBoard → "BBoard", etc.) without
     * any per-host wiring.
     */
    private fun hostAppLabel(): String =
        context.packageManager.getApplicationLabel(context.applicationInfo).toString()

    /**
     * Persist the recovered seed into [SeedVault] so the wallet panel
     * bootstraps from it on next launch. If a fresh seed was generated
     * earlier this session (the "No seed → generate fresh" path), wipe it
     * first — `storeSeed` refuses to overwrite. The biometric prompt fires
     * again here because storeSeed is biometric-gated; the user just
     * authenticated for the Block Store retrieve, so this is a second tap.
     *
     * Note: this only persists the seed. The currently-active SDK instance
     * inside `WalletPanelViewModel` was already built on the fresh seed and
     * will keep using it for this process lifetime. The user has to relaunch
     * the app for the wallet panel to rebootstrap on the recovered seed.
     */
    internal suspend fun restoreSeedIntoVault(
        activity: FragmentActivity,
        recoveredEntropy: ByteArray,
        recoveredBip39Seed: ByteArray,
    ) {
        try {
            // SeedVault.storeSeed encrypts under the Keystore master key,
            // which WalletKeyManager creates lazily — but only on the
            // WalletPanel's auto-bootstrap path. With the Problem A gate
            // (9465ea4), the wallet panel stays gated during
            // BackupAvailable, so a *first-ever* install hits this restore
            // path without anyone having generated the master key yet.
            // Without this, cipherForEncrypt throws "Master key not found"
            // and the recovered seed silently fails to persist — the
            // catch below logs but the relaunch then auto-creates a fresh
            // wallet over the restored DID, wiping the user's funds out
            // of view.
            if (!walletKeyManager.hasKey()) {
                val strongBox = walletKeyManager.generateKey()
                Log.i(TAG, "  Generated Keystore master key (${if (strongBox) "StrongBox" else "TEE"}) for restored seed")
            }
            if (seedVault.hasSeed()) {
                Log.i(TAG, "  Replacing existing SeedVault entry with recovered seed")
                seedVault.deleteSeed()
            }
            // Copy the recovered bytes — storeSeed's producer lambda wipes
            // the PlaintextSeed it receives, and we must not wipe the caller's
            // RestoredSigil here (its own `wipe()` runs in the outer finally).
            seedVault.storeSeed(activity) {
                PlaintextSeed(recoveredEntropy.copyOf(), recoveredBip39Seed.copyOf())
            }
            Log.i(TAG, "  Recovered seed persisted to SeedVault")
        } catch (e: Exception) {
            Log.e(TAG, "  Failed to persist recovered seed into SeedVault: ${e.message}", e)
            // Re-throw so callers (restoreSeed) move to SigilStatus.Error
            // instead of silently SIGKILLing into a fresh-wallet state.
            // The silent fall-through was what hid this bug from view.
            throw e
        }
    }

    companion object {
        private const val TAG = "SigilPanel"

        /** Length of the random user id bound to the new credential. */
        private const val USER_ID_BYTES = 16

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
