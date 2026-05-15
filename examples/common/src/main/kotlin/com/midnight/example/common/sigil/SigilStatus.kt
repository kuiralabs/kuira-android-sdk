package com.midnight.example.common.sigil

/**
 * UI state for [SigilStatusPanel] / [SigilPanelViewModel].
 *
 * Mirrors the wallet panel's [com.midnight.example.common.wallet.WalletStatus]
 * shape. A pure identity state — no SDK / network / address coupling. Hosts
 * that want to bridge sigil identity with their wallet SDK (e.g. signing an
 * access-key authorization) wire that themselves; this state machine only
 * covers what the panel can fully own (passkey creation + DID derivation +
 * cloud backup / restore).
 *
 * **Variants:**
 *  - [None]: no passkey + DID yet. Pill renders a "create identity" cue.
 *  - [Creating]: passkey ceremony or DID derivation in flight. Spinner.
 *  - [Forged]: passkey exists; DID + root key derived. Pill shows the
 *    truncated DID (or, when wired up, a `.night` Midnames domain).
 *  - [Error]: a flow failed. Pill renders the error glyph.
 */
sealed class SigilStatus {
    /** No passkey + DID yet. */
    data object None : SigilStatus()

    /** Async flow in progress (passkey create, DID derive, backup, restore). */
    data class Creating(val stage: String) : SigilStatus()

    /**
     * Identity exists.
     *
     * @property did `did:key:zDnae…` form. The full string can be hundreds
     *   of chars; the panel pill renders only the first ~12 after the prefix.
     * @property credentialId Opaque passkey credential id (Base64URL).
     * @property publicKeyHex 64-char hex of the root P-256 public key.
     */
    data class Forged(
        val did: String,
        val credentialId: String,
        val publicKeyHex: String,
    ) : SigilStatus()

    /** Flow failed (passkey rejected, RP id mismatch, network error, etc.). */
    data class Error(val message: String) : SigilStatus()
}
