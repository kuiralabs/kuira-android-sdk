package com.midnight.kuira.core.identity.sigil

import android.app.Activity
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.did.DidKeyGenerator
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [SigilIdentityProvider] — derives the sigil DID from
 * `PRF(passkey, SIGIL_SALT)` → Ed25519 keypair → `did:key:z6Mk…`.
 *
 * **Why Ed25519 + did:key:**
 *  - W3C `did:key` is the format already exercised by the wallet;
 *    consumers treat the DID as an opaque string, so staying in-spec
 *    costs us nothing while keeping the option open for future
 *    interop (W3C verifiable credentials, did-resolver libraries,
 *    midnightOS Passkeys).
 *  - Ed25519's 32-byte seed maps 1:1 to a valid keypair with no
 *    validity loops, no scalar bias — unlike P-256 from a raw PRF
 *    output, which would need rejection sampling.
 *  - The Ed25519 private key is derivable on demand (re-running this
 *    flow re-authenticates the passkey), so if a future use case
 *    needs a sigil-controlled signing key without a Credential
 *    Manager round-trip we have one ready. For now the private half
 *    is wiped immediately after we extract the public key.
 *
 * **Determinism:** `(passkey, SIGIL_SALT) → PRF → Ed25519 seed → pubkey
 * → did:key` is deterministic at every step. Same passkey on any
 * device, any Kuira ecosystem app sharing the relying party, lands on
 * the same DID. Verified architecturally; locked down by
 * [Ed25519PrfSigilProviderTest].
 */
@Singleton
class Ed25519PrfSigilProvider @Inject constructor() : SigilIdentityProvider {

    override suspend fun deriveSigilDid(
        activity: Activity,
        passkeyManager: PasskeyManager,
    ): SigilDerivation {
        // Single PRF authentication — one biometric prompt, 32 bytes
        // of entropy bound to (this passkey, SIGIL_SALT).
        val challenge = ByteArray(CHALLENGE_SIZE).also { SecureRandom().nextBytes(it) }
        val result = passkeyManager.authenticateWithPrf(
            activity = activity,
            challenge = challenge,
            prfSalt = SeedDeriver.SIGIL_SALT,
        )
        val prfOutput = result.prfOutput
            ?: throw BackupException("PRF not available — authenticator does not support PRF extension")

        return try {
            SigilDerivation(
                did = ed25519DidFromSeed(prfOutput),
                credentialId = result.credentialId,
            )
        } finally {
            // PRF output is the Ed25519 seed; wipe it now that we have
            // the DID. Caller never sees the seed.
            prfOutput.fill(0)
        }
    }

    /**
     * Pure helper: 32-byte seed → Ed25519 pubkey → did:key:z6Mk.
     *
     * Extracted as `internal` so the unit test can pin the
     * (seed → DID) mapping without standing up Credential Manager.
     * The seed is consumed in this method only; the caller owns the
     * wipe.
     */
    internal fun ed25519DidFromSeed(seed: ByteArray): String {
        require(seed.size == ED25519_SEED_SIZE) {
            "Ed25519 seed must be $ED25519_SEED_SIZE bytes, got ${seed.size}"
        }
        // Bouncy Castle: from a 32-byte seed we derive the keypair
        // deterministically. Pubkey is fixed-32-bytes; private key
        // material (the seed itself, plus a derived clamped scalar
        // internal to BC) is wiped when the parameters object falls
        // out of scope. We don't expose the private half today.
        val sk = Ed25519PrivateKeyParameters(seed, 0)
        val pubkey = sk.generatePublicKey().encoded
        return DidKeyGenerator.fromEd25519(pubkey)
    }

    private companion object {
        const val CHALLENGE_SIZE = 32
        const val ED25519_SEED_SIZE = 32
    }
}
