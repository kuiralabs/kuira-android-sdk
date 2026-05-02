package com.midnight.kuira.core.identity.auth

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Self-verifiable key authorization — the core innovation over rvcas.
 *
 * The P-256 root key (passkey, in TEE) signs a payload that authorizes a secp256k1
 * access key to act on behalf of the sigil. Anyone can verify the authorization
 * cryptographically — no server trust required.
 *
 * The signing happens during a CredentialManager authentication ceremony:
 * the payload hash is embedded in the WebAuthn challenge. The passkey signs
 * `authenticatorData || SHA-256(clientDataJSON)` which transitively commits
 * to our challenge (the payload hash).
 *
 * Authorization record = root pubkey + access pubkey + scope + timestamp + WebAuthn signature
 */
object KeyAuthorization {

    /**
     * Builds the authorization payload that will be signed by the passkey.
     *
     * The payload is deterministic for the same inputs — important for verification.
     *
     * Format (binary):
     * - Magic bytes: "KUIRA-AUTH-V1" (13 bytes)
     * - Root public key: compressed P-256 (33 bytes)
     * - Access public key: compressed secp256k1 (33 bytes)
     * - Scope flags: 4 bytes (big-endian uint32)
     * - Timestamp: 8 bytes (big-endian int64, epoch millis)
     * - Expiry: 8 bytes (big-endian int64, epoch millis, 0 = no expiry)
     *
     * Total: 99 bytes fixed
     */
    fun buildPayload(
        rootPublicKey: ByteArray,
        accessPublicKey: ByteArray,
        scope: AuthorizationScope,
        timestampMs: Long,
        expiryMs: Long = NO_EXPIRY,
    ): ByteArray {
        require(rootPublicKey.size == COMPRESSED_P256_SIZE) {
            "Root public key must be $COMPRESSED_P256_SIZE bytes (compressed P-256)"
        }
        require(accessPublicKey.size == COMPRESSED_SECP256K1_SIZE) {
            "Access public key must be $COMPRESSED_SECP256K1_SIZE bytes (compressed secp256k1)"
        }
        require(timestampMs > 0) { "Timestamp must be positive" }
        require(expiryMs == NO_EXPIRY || expiryMs > timestampMs) {
            "Expiry must be after timestamp or NO_EXPIRY (0)"
        }

        val buffer = ByteBuffer.allocate(PAYLOAD_SIZE)
        buffer.put(MAGIC_BYTES)
        buffer.put(rootPublicKey)
        buffer.put(accessPublicKey)
        buffer.putInt(scope.flags)
        buffer.putLong(timestampMs)
        buffer.putLong(expiryMs)

        return buffer.array()
    }

    /**
     * Hashes the payload for use as the WebAuthn challenge.
     *
     * The challenge is SHA-256(payload). During authentication, the passkey signs
     * `authenticatorData || SHA-256(clientDataJSON)` where clientDataJSON contains
     * our challenge. This transitively binds the signature to the payload.
     */
    fun hashPayload(payload: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(payload)
    }

    /**
     * Parses a binary payload back into its components.
     *
     * @throws IllegalArgumentException if the payload is malformed
     */
    fun parsePayload(payload: ByteArray): AuthorizationPayload {
        require(payload.size == PAYLOAD_SIZE) {
            "Payload must be $PAYLOAD_SIZE bytes, got ${payload.size}"
        }

        val buffer = ByteBuffer.wrap(payload)

        // Verify magic bytes
        val magic = ByteArray(MAGIC_BYTES.size)
        buffer.get(magic)
        require(magic.contentEquals(MAGIC_BYTES)) { "Invalid magic bytes" }

        val rootPublicKey = ByteArray(COMPRESSED_P256_SIZE)
        buffer.get(rootPublicKey)

        val accessPublicKey = ByteArray(COMPRESSED_SECP256K1_SIZE)
        buffer.get(accessPublicKey)

        val scopeFlags = buffer.int
        val timestampMs = buffer.long
        val expiryMs = buffer.long

        return AuthorizationPayload(
            rootPublicKey = rootPublicKey,
            accessPublicKey = accessPublicKey,
            scope = AuthorizationScope.fromFlags(scopeFlags),
            timestampMs = timestampMs,
            expiryMs = expiryMs,
        )
    }

    private val MAGIC_BYTES = "KUIRA-AUTH-V1".toByteArray(Charsets.US_ASCII)

    private const val COMPRESSED_P256_SIZE = 33
    private const val COMPRESSED_SECP256K1_SIZE = 33

    /** Fixed payload size: magic(13) + rootPK(33) + accessPK(33) + scope(4) + timestamp(8) + expiry(8) */
    const val PAYLOAD_SIZE = 13 + 33 + 33 + 4 + 8 + 8 // = 99

    const val NO_EXPIRY = 0L
}

/**
 * Parsed authorization payload.
 */
data class AuthorizationPayload(
    val rootPublicKey: ByteArray,
    val accessPublicKey: ByteArray,
    val scope: AuthorizationScope,
    val timestampMs: Long,
    val expiryMs: Long,
) {
    val hasExpiry: Boolean get() = expiryMs != KeyAuthorization.NO_EXPIRY

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return hasExpiry && nowMs >= expiryMs
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthorizationPayload) return false
        return rootPublicKey.contentEquals(other.rootPublicKey) &&
            accessPublicKey.contentEquals(other.accessPublicKey) &&
            scope == other.scope &&
            timestampMs == other.timestampMs &&
            expiryMs == other.expiryMs
    }

    override fun hashCode(): Int {
        var result = rootPublicKey.contentHashCode()
        result = 31 * result + accessPublicKey.contentHashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + expiryMs.hashCode()
        return result
    }
}

/**
 * What the access key is authorized to do.
 *
 * Encoded as a 32-bit flags field for extensibility.
 * The three tiers from the sigil authority model:
 * - SILENT: read state, query balances
 * - NOTIFY: small spends, agent actions
 * - APPROVE: large spends, deploy contracts, key delegation
 *
 * FULL_ACCESS = all flags set.
 */
data class AuthorizationScope(val flags: Int) {

    fun hasPermission(permission: Int): Boolean = (flags and permission) != 0

    companion object {
        /** Read-only operations: query balances, read state. */
        const val SILENT = 0x01

        /** Transaction signing with notification: small spends, agent actions. */
        const val NOTIFY = 0x02

        /** High-value operations requiring explicit approval. */
        const val APPROVE = 0x04

        /** Full access — all permission tiers. */
        val FULL_ACCESS = AuthorizationScope(SILENT or NOTIFY or APPROVE)

        /** Read-only access. */
        val READ_ONLY = AuthorizationScope(SILENT)

        /** Transaction access (silent + notify, no approve). */
        val TRANSACT = AuthorizationScope(SILENT or NOTIFY)

        fun fromFlags(flags: Int) = AuthorizationScope(flags)
    }
}
