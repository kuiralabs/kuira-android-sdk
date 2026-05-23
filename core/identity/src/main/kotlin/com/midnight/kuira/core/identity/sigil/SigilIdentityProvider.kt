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
     * The 32-byte PRF salt this provider uses for its ceremony.
     *
     * Exposed so multi-purpose orchestrators (e.g. `SigilSession`
     * collapsing sigil + wallet bootstrap into one biometric) can
     * request a multi-salt PRF assertion that produces the right
     * output for this provider. Callers that don't orchestrate just
     * use [deriveSigilDid] and ignore this.
     */
    val prfSalt: ByteArray

    /**
     * Pure derivation: 32-byte PRF output → DID string. No ceremony,
     * no biometric, no Credential Manager.
     *
     * Used by orchestrators that already ran a (possibly multi-salt)
     * PRF assertion and need to convert the result into a DID without
     * a second ceremony. Implementations are deterministic in the
     * input; the same PRF output always yields the same DID.
     */
    fun deriveFromPrfOutput(prfOutput: ByteArray): String

    /**
     * Convenience one-shot: runs a PRF ceremony with [prfSalt], derives
     * the DID, returns it plus the credential ID from the assertion.
     *
     * Triggers exactly one biometric prompt. Throws whatever
     * [PasskeyManager.authenticateWithPrf] throws when the ceremony
     * fails (cancellation, RP-id mismatch, missing PRF support, etc.).
     *
     * Use [prfSalt] + [deriveFromPrfOutput] when you're already running
     * a multi-salt ceremony for other reasons (e.g. `SigilSession`).
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
