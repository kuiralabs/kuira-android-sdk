package com.midnight.example.common.sigil

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.midnight.kuira.core.identity.did.DidKeyGenerator
import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

        // SharedPreferences keys (private to the persisted sigil triple).
        private const val SIGIL_PREFS_NAME = "sigil_panel_identity"
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
