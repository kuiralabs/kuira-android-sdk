package com.midnight.kuira.dapp.sigil

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.midnight.kuira.dapp.BuildConfig
import com.midnight.kuira.core.auth.BiometricGate
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.SigilBackup
import com.midnight.kuira.core.identity.did.DidKeyGenerator
import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

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
class SigilPanelViewModel(private val app: Application) : AndroidViewModel(app) {

    private val passkeyManager = PasskeyManager(
        config = PasskeyConfig(rpId = DEFAULT_RP_ID),
    )

    /**
     * Lazy chain for SeedVault access. The sigil panel needs the wallet seed
     * to back it up + the matching slot to restore into. Lazy because most
     * sigil interactions (forge, view DID) don't touch the seed, and we want
     * to defer the Keystore + biometric prompt setup until the user actually
     * taps backup/restore.
     *
     * Same primitives the wallet panel uses — both panels can hold their own
     * instances against the same underlying Keystore-protected store.
     */
    private val walletKeyManager by lazy { WalletKeyManager() }
    private val biometricGate by lazy { BiometricGate(walletKeyManager) }
    private val seedVault by lazy { SeedVault(app, biometricGate) }
    private val sigilBackup by lazy {
        SigilBackup(
            passkeyManager = passkeyManager,
            storage = BlockStoreBackupStorage(app),
        )
    }

    /**
     * On-disk store for the sigil triple. SharedPreferences is enough — the
     * DID + credentialId + public key are public material (the private half
     * never leaves the device's passkey secure store). MODE_PRIVATE so it's
     * scoped to this app's UID.
     */
    private val prefs by lazy {
        app.getSharedPreferences(SIGIL_PREFS_NAME, Context.MODE_PRIVATE)
    }

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
        if (prefs.getBoolean(KEY_BACKUP_DISMISSED, false)) {
            Log.i(TAG, "Backup prompt previously dismissed — skipping probe")
            _status.value = SigilStatus.None
            return
        }
        try {
            val blob = BlockStoreBackupStorage(app).retrieve()
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
        // commit() not apply() — symmetric with persistSigil. The status
        // transition + the wallet-panel re-evaluation that follows
        // happen on the next composition frame; we want the flag
        // durable before any of that races with a process death.
        prefs.edit().putBoolean(KEY_BACKUP_DISMISSED, true).commit()
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
     * `appMetadata` is intentionally null here: the sigil panel module
     * doesn't own any host-specific metadata. Hosts that need to round-trip
     * extra bytes through the backup will wire that themselves in a follow-up.
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
                sigilBackup.backup(
                    activity = activity,
                    entropy = entropy,
                    bip39Seed = bip39Seed,
                    did = sigil.did,
                    credentialId = sigil.credentialId,
                    publicKeyHex = sigil.publicKeyHex,
                    appMetadata = null,
                )
                Log.i(TAG, "Backup SUCCESS — seed + sigil identity stored in Block Store (no appMetadata)")
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
                    // App metadata is intentionally null on the backup side
                    // (panel doesn't manage host-specific blobs). If a future
                    // host wires metadata through, surface its size here.
                    val meta = restored.appMetadata
                    if (meta != null) {
                        Log.i(TAG, "  App metadata: ${meta.size} bytes (raw, host-specific encoding)")
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
        val did = prefs.getString(KEY_DID, null) ?: return
        val credentialId = prefs.getString(KEY_CREDENTIAL_ID, null) ?: return
        val publicKeyHex = prefs.getString(KEY_PUBLIC_KEY_HEX, null) ?: return
        _status.value = SigilStatus.Forged(did = did, credentialId = credentialId, publicKeyHex = publicKeyHex)
        Log.i(TAG, "Loaded persisted sigil: did=${did.take(30)}…")
    }

    private fun persistSigil(did: String, credentialId: String, publicKeyHex: String) {
        // commit() not apply(): the restore flow SIGKILLs the process within
        // a few ms of this call (post-2026-05-18 once SeedVault.storeSeed
        // became silent inside the auth-validity window — see
        // docs/security/SECURITY_NOTES.md). apply()'s async write never
        // fsyncs the prefs file before the kill, so the next launch reads
        // an empty file and the sigil pill shows "no sigil" despite a
        // successful restore. commit() blocks until durable. Cost is
        // negligible — this runs once per forge/restore, not on a hot path.
        prefs.edit()
            .putString(KEY_DID, did)
            .putString(KEY_CREDENTIAL_ID, credentialId)
            .putString(KEY_PUBLIC_KEY_HEX, publicKeyHex)
            .commit()
    }

    /**
     * Label the passkey prompt shows for the credential — derived from the host
     * app's manifest `android:label` so every dApp embedding this panel sees
     * its own name (Kicks → "Midnight Kicks", BBoard → "BBoard", etc.) without
     * any per-host wiring.
     */
    private fun hostAppLabel(): String =
        app.packageManager.getApplicationLabel(app.applicationInfo).toString()

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
    private suspend fun restoreSeedIntoVault(
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

        /**
         * Relying-Party id for passkey ceremonies. Must match the
         * `assetlinks.json` hosted at this domain so Credential Manager
         * accepts the create/authenticate. See `docs.midnight.network`
         * or the parent Kuira app's setup for the production value.
         */
        private const val DEFAULT_RP_ID = "nel349.github.io"

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

        /**
         * SharedPreferences file name. Intentionally matches the name BBoard's
         * pre-migration `BBoardViewModel` used so any sigil forged via the
         * legacy `SigilCard` shows up in the panel without a migration step.
         * Keys (did / credentialId / publicKeyHex) also match. Once BBoard's
         * legacy card is removed in step 4, this file becomes single-owner.
         */
        private const val SIGIL_PREFS_NAME = "sigil_identity"
        private const val KEY_DID = "did"
        private const val KEY_CREDENTIAL_ID = "credentialId"
        private const val KEY_PUBLIC_KEY_HEX = "publicKeyHex"
        /** Set by [dismissBackup] so the BackupAvailable prompt stops re-appearing on every launch. */
        private const val KEY_BACKUP_DISMISSED = "backupDismissed"

        /** Factory for `viewModel(factory = SigilPanelViewModel.Factory)`. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                SigilPanelViewModel(app)
            }
        }
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
