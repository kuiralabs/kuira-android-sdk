package com.midnight.kuira.core.identity.sigil

import android.app.Activity
import com.midnight.kuira.core.identity.passkey.PasskeyManager

/**
 * Derives the user's sigil DID — the stable identity string a Kuira
 * dApp uses to recognise a user across sessions and apps.
 *
 * The interface is intentionally **opaque** about how the DID is
 * computed. Today the default implementation
 * ([Ed25519PrfSigilProvider]) derives a deterministic Ed25519
 * keypair from `PRF(passkey, SIGIL_SALT)` and encodes its public key
 * as a W3C `did:key:z6Mk…`. Future implementations (e.g. zk-passport
 * integration, midnightOS-Passkeys interop, multi-credential
 * aggregation) plug in here by replacing the Hilt binding.
 *
 * **Contract:**
 *  - The returned DID MUST be deterministic in `(passkey, implementation)`.
 *    The same user authenticating the same credential MUST get the same
 *    DID on every device, in every Kuira app sharing the relying party.
 *  - The DID is treated as an **opaque identifier** by every consumer
 *    in the codebase today — no caller extracts a public key from it,
 *    verifies it cryptographically, or uses it to validate signatures.
 *    Implementations are free to choose any encoding.
 *  - Implementations MUST NOT persist their own state beyond what the
 *    [PasskeyManager] ceremony already records. The caller persists
 *    via `SigilStateStore`.
 *
 * **Why an interface (not a concrete class):** identity primitives
 * shift on long horizons (W3C did spec, midnightOS interop, ZK
 * proofs). Owning this seam now means future changes are a Hilt
 * binding swap, not a refactor through five call sites.
 */
interface SigilIdentityProvider {

    /**
     * Authenticate the user's passkey and return the derived sigil
     * identity.
     *
     * Triggers exactly one biometric prompt via Credential Manager.
     * Throws whatever [PasskeyManager.authenticateWithPrf] throws when
     * the ceremony fails (cancellation, RP-id mismatch, missing PRF
     * support, etc.); callers are responsible for translating.
     *
     * The returned [SigilDerivation.did] is the load-bearing sigil
     * identity. [SigilDerivation.credentialId] is a ceremony artifact
     * exposed for callers (like `SigilPanelViewModel`) that want to
     * persist it alongside the DID — both for display and for
     * downstream flows that need to address the specific credential
     * (e.g. `KeyAuthorization` records).
     */
    suspend fun deriveSigilDid(
        activity: Activity,
        passkeyManager: PasskeyManager,
    ): SigilDerivation
}

/**
 * Result of a [SigilIdentityProvider.deriveSigilDid] ceremony.
 *
 * Pure data — no native handles, no resources to close. Safe to
 * pass across coroutine boundaries.
 */
data class SigilDerivation(
    /** The user's sigil DID (e.g. `did:key:z6Mk…` for the default Ed25519+PRF impl). */
    val did: String,
    /** Credential ID from the WebAuthn assertion that produced this DID. */
    val credentialId: String,
)
