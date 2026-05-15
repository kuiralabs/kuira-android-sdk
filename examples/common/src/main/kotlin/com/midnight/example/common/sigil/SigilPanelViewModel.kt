package com.midnight.example.common.sigil

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
import com.midnight.kuira.core.auth.BiometricGate
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

    private val _status = MutableStateFlow<SigilStatus>(SigilStatus.None)
    val status: StateFlow<SigilStatus> = _status

    init {
        // Restore any previously-forged sigil so the panel pill shows the DID
        // immediately on next launch instead of "no sigil".
        loadPersistedSigil()
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
                    userName = DEFAULT_USER_NAME,
                )
                val did = DidKeyGenerator.fromCompressedP256(result.publicKey.compressed)
                val publicKeyHex = result.publicKey.compressedHex()

                Log.i(TAG, "Sigil forged — did=${did.take(30)}…")
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
     * and logs whether the outputs match. No state change — purely a debug
     * affordance to verify a freshly forged authenticator supports PRF
     * before relying on it for backup.
     */
    fun testPrf(activity: Activity) {
        viewModelScope.launch {
            try {
                val challenge = ByteArray(PRF_CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
                val salt = MessageDigest.getInstance("SHA-256")
                    .digest(PRF_BACKUP_PURPOSE.toByteArray(Charsets.UTF_8))

                Log.i(TAG, "Testing PRF (purpose=$PRF_BACKUP_PURPOSE)")
                val first = passkeyManager.authenticateWithPrf(
                    activity = activity, challenge = challenge, prfSalt = salt,
                )
                val firstOut = first.prfOutput
                if (firstOut == null || firstOut.size != PRF_OUTPUT_BYTES) {
                    Log.w(TAG, "PRF returned null/wrong size — extension not supported on this authenticator")
                    return@launch
                }
                Log.i(TAG, "PRF first output: ${firstOut.size}B")
                val second = passkeyManager.authenticateWithPrf(
                    activity = activity, challenge = challenge, prfSalt = salt,
                )
                val secondOut = second.prfOutput
                if (secondOut == null) {
                    Log.w(TAG, "PRF second authenticate returned null")
                    return@launch
                }
                val deterministic = firstOut.contentEquals(secondOut)
                Log.i(TAG, "PRF determinism: ${if (deterministic) "PASS" else "FAIL (outputs differ)"}")
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
                Log.i(TAG, "Starting PRF backup…")
                val plaintext = seedVault.loadSeed(activity)
                val entropy = plaintext.mnemonicEntropy.copyOf()
                val bip39Seed = plaintext.bip39Seed.copyOf()
                plaintext.wipe()
                sigilBackup.backup(
                    activity = activity,
                    entropy = entropy,
                    bip39Seed = bip39Seed,
                    did = sigil.did,
                    credentialId = sigil.credentialId,
                    publicKeyHex = sigil.publicKeyHex,
                    appMetadata = null,
                )
                Log.i(TAG, "Backup SUCCESS — seed + sigil identity stored in Block Store")
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
                    val did = restored.did
                    if (did != null) {
                        val credentialId = restored.credentialId.orEmpty()
                        val publicKeyHex = restored.publicKeyHex.orEmpty()
                        persistSigil(did = did, credentialId = credentialId, publicKeyHex = publicKeyHex)
                        _status.value = SigilStatus.Forged(
                            did = did,
                            credentialId = credentialId,
                            publicKeyHex = publicKeyHex,
                        )
                        Log.i(TAG, "Sigil restored: did=${did.take(30)}…")
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
        val did = prefs.getString(KEY_DID, null) ?: return
        val credentialId = prefs.getString(KEY_CREDENTIAL_ID, null) ?: return
        val publicKeyHex = prefs.getString(KEY_PUBLIC_KEY_HEX, null) ?: return
        _status.value = SigilStatus.Forged(did = did, credentialId = credentialId, publicKeyHex = publicKeyHex)
        Log.i(TAG, "Loaded persisted sigil: did=${did.take(30)}…")
    }

    private fun persistSigil(did: String, credentialId: String, publicKeyHex: String) {
        prefs.edit()
            .putString(KEY_DID, did)
            .putString(KEY_CREDENTIAL_ID, credentialId)
            .putString(KEY_PUBLIC_KEY_HEX, publicKeyHex)
            .apply()
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

        /** Default display name shown in the passkey-create prompt. */
        private const val DEFAULT_USER_NAME = "BBoard Test User"

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
