package com.midnight.kuira.sdk.walletseed

import android.app.Activity
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.sigil.SigilDerivation
import com.midnight.kuira.core.identity.sigil.SigilIdentityProvider
import com.midnight.kuira.core.identity.sigil.SigilStateStore
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
        val did = sigilIdentityProvider.deriveFromPrfOutput(sigilPrf)
        // Wipe the sigil PRF output now that we have the DID.
        sigilPrf.fill(0)

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

    private companion object {
        const val TAG = "SigilSession"
        const val CHALLENGE_SIZE = 32
    }
}
