package com.midnight.kuira.sdk.walletseed

import android.app.Activity
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.passkey.NoPasskeyCredentialException
import com.midnight.kuira.core.identity.passkey.PasskeyException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.sigil.SigilDerivation
import com.midnight.kuira.core.identity.sigil.SigilIdentityProvider
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-biometric bootstrap orchestrator for sign-in flows.
 *
 * **The problem this solves.** Post-A2, sign-in derives the sigil
 * DID via `PRF(passkey, SIGIL_SALT)` and the wallet seed via
 * `PRF(passkey, SEED_SALT)`. Each PRF salt is a separate parameter
 * to a separate WebAuthn assertion — two ceremonies, two biometric
 * prompts. After tapping "sign in" the user immediately taps "read
 * balance" and gets prompted again. That's the regression
 * [WalletSeedSource.ensureSeedReady] inherits from the post-A2
 * design.
 *
 * **The fix.** WebAuthn's PRF extension supports evaluating TWO
 * salts in one assertion (`eval.first` + `eval.second`). Modern
 * authenticators (Google Password Manager since Chrome 132,
 * hardware tokens) return both outputs in a single response → one
 * biometric prompt.
 *
 * **Flow:**
 *  1. Single PRF ceremony with `prfSaltFirst = SIGIL_SALT` and
 *     `prfSaltSecond = SEED_SALT`. **One biometric.**
 *  2. Derive sigil DID via [SigilIdentityProvider.deriveFromPrfOutput].
 *  3. Pre-warm SeedVault via [WalletSeedSource.acceptPreDerivedSeed]
 *     so the wallet's first refresh hits cache instead of running
 *     its own PRF ceremony.
 *  4. Persist the sigil triple in [SigilStateStore].
 *
 * **Fallback.** If the authenticator only evaluates `first` (older
 * GMS Core, etc.), [PasskeyManager.authenticateWithPrf] returns
 * `prfOutputSecond = null`. We then run a second ceremony for
 * SEED_SALT (= two biometrics, same as the pre-SigilSession path).
 * UX degrades gracefully; correctness is unchanged.
 *
 * **Lives in `sdk:wallet-seed`** (not `core:identity`) because the
 * orchestrator depends on [WalletSeedSource] for the SeedVault
 * pre-warm — and `sdk:wallet-seed` already sits above `core:identity`
 * in the dependency graph, so this is the natural seam.
 */
@Singleton
class SigilSession @Inject constructor(
    private val passkeyManager: PasskeyManager,
    private val sigilIdentityProvider: SigilIdentityProvider,
    private val sigilStateStore: SigilStateStore,
    private val walletSeedSource: WalletSeedSource,
) {

    /**
     * Sign in with an existing passkey and pre-warm the wallet seed.
     *
     * On a modern authenticator: **one biometric prompt** covers both
     * the sigil DID derivation and the wallet seed pre-warm. On older
     * authenticators: two biometric prompts (graceful fallback).
     *
     * Side effects:
     *  - [SigilStateStore] gets the new sigil triple persisted
     *    (publicKeyHex stays empty — the assertion ceremony doesn't
     *    return the passkey's P-256 pubkey).
     *  - [WalletSeedSource] populates SeedVault with the derived
     *    BIP-39 seed so subsequent wallet ops hit cache.
     *
     * Returns the [SigilDerivation] for the caller (typically the
     * sigil panel ViewModel) to surface as `SigilStatus.Forged`.
     *
     * @throws BackupException if PRF is unavailable.
     * @throws com.midnight.kuira.core.identity.passkey.PasskeyException
     *   if the ceremony itself fails (cancellation, RP-id mismatch,
     *   missing credential).
     */
    suspend fun signIn(activity: FragmentActivity): SigilDerivation {
        // Step 1: single multi-salt PRF ceremony.
        val challenge = ByteArray(CHALLENGE_SIZE).also { SecureRandom().nextBytes(it) }
        val result = passkeyManager.authenticateWithPrf(
            activity = activity,
            challenge = challenge,
            prfSalt = sigilIdentityProvider.prfSalt,
            prfSaltSecond = SeedDeriver.SEED_SALT,
        )
        val sigilPrf = result.prfOutput
            ?: throw BackupException("PRF not available — authenticator does not support PRF extension")

        // Step 2: derive the sigil DID locally (pure compute).
        // try/finally so the PRF output is wiped even if
        // deriveFromPrfOutput throws (defensive — its `require(size==32)`
        // shouldn't fire in normal operation, but key material must not
        // outlive a thrown exception under any code path).
        val did = try {
            sigilIdentityProvider.deriveFromPrfOutput(sigilPrf)
        } finally {
            sigilPrf.fill(0)
        }

        // Step 3: resolve the seed PRF output. Multi-salt hit → use
        // the secondary output. Multi-salt miss → fall back to a
        // second ceremony.
        val seedPrf = result.prfOutputSecond ?: run {
            Log.w(TAG, "Multi-salt PRF not supported by authenticator — falling back to second ceremony")
            val fallbackChallenge = ByteArray(CHALLENGE_SIZE).also { SecureRandom().nextBytes(it) }
            val fallback = passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = fallbackChallenge,
                prfSalt = SeedDeriver.SEED_SALT,
            )
            fallback.prfOutput
                ?: throw BackupException("PRF fallback failed — second ceremony returned no output")
        }

        // Step 4: pre-warm SeedVault. Caller never sees the seed
        // here — WalletSeedSource owns the cache. ensureSeedReady
        // on the wallet panel's first read will hit cache + decrypt
        // (Keystore biometric, often silent within the auth window).
        try {
            walletSeedSource.acceptPreDerivedSeed(activity, seedPrf)
                .fill(0) // wipe the seed we received; vault owns the cache now
        } finally {
            seedPrf.fill(0)
        }

        // Step 5: persist the sigil triple. publicKeyHex stays empty
        // — assertion doesn't return the passkey pubkey, and the
        // panel UI hides the empty row.
        sigilStateStore.persistSigil(
            did = did,
            credentialId = result.credentialId,
            publicKeyHex = "",
        )

        Log.i(TAG, "Sign-in complete — DID: $did")
        return SigilDerivation(did = did, credentialId = result.credentialId)
    }

    /**
     * Forge a NEW sigil — create a fresh passkey AND derive its identity (+
     * pre-warm the wallet seed) in a **single biometric ceremony** via
     * PRF-on-create.
     *
     * **Why this exists.** The old forge ran `createPasskey` then a SEPARATE
     * PRF GET to derive the DID. That GET could fail on the *just-created*
     * credential — `Cannot find credential in local KeyStore or database` —
     * until a backup/sync pass (`bmgr run`) propagated it, and it was a second
     * biometric prompt besides. Requesting the PRF salts during the create
     * ceremony returns the outputs in the same response → no follow-up GET, no
     * race, one prompt.
     *
     * **Fallback.** Authenticators that don't evaluate PRF on create return no
     * PRF output; we then derive via a GET ceremony (after a short delay to let
     * the new credential become discoverable). Two prompts, same correctness.
     *
     * Mirrors [signIn], but for a brand-new credential. Unlike sign-in, the
     * caller persists the sigil triple — the create returns the passkey's P-256
     * pubkey (for `KeyAuthorization`), which the caller surfaces + stores.
     *
     * @throws BackupException if PRF is unavailable on either path.
     * @throws PasskeyException if the create ceremony fails (cancellation, etc.).
     */
    suspend fun forge(activity: FragmentActivity): ForgeResult {
        // Step 1: create the passkey, evaluating BOTH salts in the same
        // ceremony (SIGIL_SALT → DID, SEED_SALT → wallet seed). One biometric.
        //
        // The user handle + display name are deterministic SDK constants, NOT
        // per-app values: every Kuira app forges under the SAME (rpId, user.id),
        // so GPM keys them to one passkey identity instead of minting a fresh
        // random credential — and therefore a different seed/sigil — per app.
        // That shared identity is what lets a cloud backup made in one app
        // restore in another. (Reuse-vs-reforge is gated by the sign-in-if-
        // exists guard; an existing credential is found via the GET path
        // regardless of user.id, so this does not strand pre-existing sigils.)
        val create = passkeyManager.createPasskey(
            activity = activity,
            userId = SIGIL_USER_HANDLE,
            userName = SIGIL_DISPLAY_NAME,
            prfSalt = sigilIdentityProvider.prfSalt,
            prfSaltSecond = SeedDeriver.SEED_SALT,
        )
        val publicKeyHex = create.publicKey.compressedHex()
        val prfOnCreate = create.prfOutput != null

        // Step 2: resolve the sigil + seed PRF outputs. Prefer the create
        // response (no GET → no race); fall back to a GET ceremony otherwise.
        var sigilPrf = create.prfOutput
        var seedPrf = create.prfOutputSecond
        if (sigilPrf == null) {
            Log.w(TAG, "PRF-on-create unsupported — deriving via GET (second prompt)")
            // The just-created credential can be momentarily undiscoverable; a
            // brief settle before the GET dodges "cannot find credential".
            delay(POST_CREATE_SETTLE_MS)
            val asserted = try {
                passkeyManager.authenticateWithPrf(
                    activity = activity,
                    challenge = ByteArray(CHALLENGE_SIZE).also { SecureRandom().nextBytes(it) },
                    prfSalt = sigilIdentityProvider.prfSalt,
                    prfSaltSecond = SeedDeriver.SEED_SALT,
                )
            } catch (e: PasskeyException) {
                throw BackupException("Forge PRF derivation failed: ${e.message}")
            }
            sigilPrf = asserted.prfOutput
                ?: throw BackupException("PRF not available — authenticator does not support the PRF extension")
            seedPrf = asserted.prfOutputSecond
        }

        // Step 3: derive the sigil DID (pure compute). try/finally wipes the
        // PRF output even if derivation throws.
        val did = try {
            sigilIdentityProvider.deriveFromPrfOutput(sigilPrf)
        } finally {
            sigilPrf.fill(0)
        }

        // Step 4: pre-warm SeedVault when we have the seed PRF (multi-salt hit).
        // When null (single-salt authenticator), the wallet derives the seed on
        // its first op — no worse than the pre-#23 forge, which never pre-warmed.
        seedPrf?.let { seed ->
            try {
                walletSeedSource.acceptPreDerivedSeed(activity, seed).fill(0)
            } finally {
                seed.fill(0)
            }
        }

        Log.i(TAG, "Forge complete — DID: $did (prfOnCreate=$prfOnCreate)")
        return ForgeResult(
            did = did,
            credentialId = create.credentialId,
            publicKeyHex = publicKeyHex,
        )
    }

    /**
     * Establish the sigil for the canonical rpId: **reuse an existing one if
     * present, forge a new one only when none is.**
     *
     * Tries [signIn] first — a GET with no `allowCredentials`, so the platform
     * satisfies it from ANY passkey for the rpId (e.g. one a sibling Kuira app
     * already forged and GPM synced across the device's account). Only when the
     * platform reports NO credential ([NoPasskeyCredentialException]) does it
     * [forge] a new one.
     *
     * This is what stops a second Kuira app from minting a DUPLICATE sigil (a
     * different seed) instead of converging on the shared identity. Any other
     * failure (cancellation, RP-id mismatch) propagates unchanged — we never
     * forge over a real error.
     *
     * On reuse, [signIn] has already persisted the sigil triple (publicKeyHex
     * empty — a GET cannot return the P-256 pubkey). On forge, the caller
     * persists the returned triple, exactly as it does for [forge].
     */
    suspend fun establishSigil(activity: FragmentActivity): EstablishResult =
        try {
            val derivation = signIn(activity)
            Log.i(TAG, "Sigil reused via sign-in — DID: ${derivation.did}")
            EstablishResult(
                did = derivation.did,
                credentialId = derivation.credentialId,
                publicKeyHex = "",
                reused = true,
            )
        } catch (e: NoPasskeyCredentialException) {
            Log.i(TAG, "No existing sigil for this relying party — forging a new one")
            val forged = forge(activity)
            EstablishResult(
                did = forged.did,
                credentialId = forged.credentialId,
                publicKeyHex = forged.publicKeyHex,
                reused = false,
            )
        }

    private companion object {
        const val TAG = "SigilSession"
        const val CHALLENGE_SIZE = 32
        const val USER_ID_BYTES = 16
        /** Settle delay before the fallback GET, so a fresh credential is discoverable. */
        const val POST_CREATE_SETTLE_MS = 1_500L

        /** Display name shown for the shared sigil in the passkey / GPM UI. */
        const val SIGIL_DISPLAY_NAME = "Kuira Sigil"

        /**
         * Canonical Kuira sigil user handle (WebAuthn `user.id`): deterministic
         * and identical across every Kuira app, so GPM keys the credential to one
         * shared identity under the canonical rpId instead of a fresh random
         * handle per forge. Opaque, non-PII, not secret. Derived from a versioned
         * label so a future rotation can ship a v2 without colliding with v1
         * credentials; truncated to [USER_ID_BYTES].
         */
        val SIGIL_USER_HANDLE: ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest("kuira:sigil:user-handle:v1".toByteArray(Charsets.UTF_8))
                .copyOf(USER_ID_BYTES)
    }
}

/**
 * Result of [SigilSession.forge]: the new sigil's DID + credential ID, plus the
 * passkey's P-256 public key hex (kept for `KeyAuthorization`). The caller
 * persists the triple via [SigilStateStore].
 */
data class ForgeResult(
    val did: String,
    val credentialId: String,
    val publicKeyHex: String,
)

/**
 * Result of [SigilSession.establishSigil]: the sigil's DID + credential ID, the
 * passkey's P-256 public key hex (empty when an existing sigil was **reused** —
 * a GET ceremony does not return the pubkey), and whether it was reused vs newly
 * forged. The caller persists the triple via [SigilStateStore].
 */
data class EstablishResult(
    val did: String,
    val credentialId: String,
    val publicKeyHex: String,
    val reused: Boolean,
)
