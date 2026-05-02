package com.midnight.kuira.core.identity.auth

/**
 * A stored authorization record linking a root identity to an access key.
 *
 * This is the persistent form of a keyAuthorization — saved after the passkey
 * signs the delegation payload during the CredentialManager ceremony.
 *
 * The record is self-verifiable: anyone with the root public key can check
 * the WebAuthn signature against the payload without trusting a server.
 */
class AuthorizationRecord(
    /** Unique identifier for this record. */
    val id: String,

    /** The user's DID (did:key:z...) derived from the root passkey. */
    val did: String,

    /** Credential ID from the passkey registration. */
    val credentialId: String,

    /** The authorization payload (99 bytes, contains root+access keys, scope, timestamps). */
    val payload: ByteArray,

    /** WebAuthn authenticator data from the assertion ceremony. */
    val authenticatorData: ByteArray,

    /** WebAuthn client data JSON from the assertion ceremony. */
    val clientDataJson: ByteArray,

    /** ECDSA P-256 signature from the passkey over the assertion data. */
    val signature: ByteArray,

    /** HD derivation path of the access key (e.g., "m/44'/2400'/0'/5/0"). */
    val accessKeyPath: String,

    /** Label for identifying this authorization (e.g., dApp name, "default"). */
    val label: String,

    /** Whether this authorization has been revoked. */
    val revoked: Boolean = false,

    /** When the record was created (epoch millis). */
    val createdAtMs: Long,

    /** When the record was revoked (epoch millis), or null if active. */
    val revokedAtMs: Long? = null,
) {
    /** Parse the embedded authorization payload. */
    fun parsePayload(): AuthorizationPayload = KeyAuthorization.parsePayload(payload)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthorizationRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "AuthorizationRecord(id=$id, did=${did.take(20)}..., label=$label, revoked=$revoked)"
}
