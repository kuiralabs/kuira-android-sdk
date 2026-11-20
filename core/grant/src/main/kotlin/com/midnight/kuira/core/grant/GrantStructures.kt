package com.midnight.kuira.core.grant

/**
 * A grant (spec section 3): the root gives [grantee] the features of [scope] at [audience], from [issuedAt]
 * until [expiresAt]. Root kind 1, the Sigil's Ed25519 key, is the only kind version 1 defines.
 *
 * The constructor checks every rule of section 3.1; the signature is not checked here.
 */
class Grant(
    root: ByteArray,
    val audience: GrantClientID,
    val grantee: GranteeKey,
    val client: GrantClientID,
    val scope: List<String>,
    val issuedAt: Long,
    val expiresAt: Long,
    val generation: Long,
    issuance: ByteArray,
) {
    private val rootBytes: ByteArray
    private val issuanceBytes: ByteArray

    init {
        if (root.size != 32 || issuance.size != 32) throw GrantRefusal.Malformed("a root key and an issuance are 32 bytes")
        if (grantee.arm != GranteeKey.Arm.K1(0)) throw GrantRefusal.UnknownArm
        GrantBytes.audience(audience.value)
        GrantBytes.client(client.value)
        GrantBytes.checkScope(scope)
        GrantBytes.checkTime(issuedAt)
        GrantBytes.checkTime(expiresAt)
        if (expiresAt <= issuedAt) throw GrantRefusal.Malformed("a grant expires after it is issued")
        if (expiresAt - issuedAt > GrantBytes.MAX_LIFETIME) throw GrantRefusal.LifetimeTooLong
        if (generation < 0 || generation >= GrantBytes.U32_MAX) throw GrantRefusal.Malformed("the generation is below 0xFFFFFFFF")
        rootBytes = root.copyOf()
        issuanceBytes = issuance.copyOf()
    }

    val root: ByteArray get() = rootBytes.copyOf()
    val issuance: ByteArray get() = issuanceBytes.copyOf()

    val preimage: ByteArray
        get() = GrantBytes.tag(TAG) + byteArrayOf(1, 1) + rootBytes + GrantBytes.str16(audience.value) +
            GrantBytes.granteeBytes(grantee) + GrantBytes.str16(client.value) + GrantBytes.scope(scope) +
            GrantBytes.u64(issuedAt) + GrantBytes.u64(expiresAt) + GrantBytes.u32(generation) + issuanceBytes

    /** `H(preimage)`. */
    val id: ByteArray get() = GrantBytes.sha256(preimage)

    override fun equals(other: Any?): Boolean = other is Grant && other.preimage.contentEquals(preimage)

    override fun hashCode(): Int = preimage.contentHashCode()

    companion object {
        internal const val TAG = "kuira:grant:v1"

        /** Decodes the preimage exactly, refusing anything outside the spec. */
        fun decode(preimage: ByteArray): Grant {
            val reader = GrantReader(preimage)
            reader.tag(TAG)
            reader.version()
            reader.rootKind()
            val root = reader.bytes(32)
            val audience = GrantBytes.audience(reader.text(1..300))
            val grantee = GrantBytes.grantee(reader.u8(), reader.u8(), reader.bytes(64))
            val client = GrantBytes.client(reader.text(1..300))
            val scope = reader.scope()
            val issuedAt = reader.time()
            val expiresAt = reader.time()
            val generation = reader.u32()
            val issuance = reader.bytes(32)
            reader.end()
            return Grant(root, audience, grantee, client, scope, issuedAt, expiresAt, generation, issuance)
        }
    }
}

/** One action under a grant, signed by the grantee (spec section 4). */
class GrantUse(grantID: ByteArray, val feature: String, subject: ByteArray, nonce: ByteArray, val at: Long) {
    private val grantIDBytes: ByteArray
    private val subjectBytes: ByteArray
    private val nonceBytes: ByteArray

    init {
        if (grantID.size != 32 || nonce.size != 16) throw GrantRefusal.Malformed("a grant id is 32 bytes and a nonce 16")
        if (!GrantBytes.isFeature(feature)) throw GrantRefusal.Malformed("`$feature` is not a feature")
        if (subject.size > 1024) throw GrantRefusal.Malformed("a subject is at most 1024 bytes")
        GrantBytes.checkTime(at)
        grantIDBytes = grantID.copyOf()
        subjectBytes = subject.copyOf()
        nonceBytes = nonce.copyOf()
    }

    val grantID: ByteArray get() = grantIDBytes.copyOf()
    val subject: ByteArray get() = subjectBytes.copyOf()
    val nonce: ByteArray get() = nonceBytes.copyOf()

    val preimage: ByteArray
        get() = GrantBytes.tag(TAG) + grantIDBytes + GrantBytes.str16(feature) + GrantBytes.str16(subjectBytes) +
            nonceBytes + GrantBytes.u64(at)

    /** `d = H(preimage)`, the digest the grantee signs under section 2.3. */
    val digest: ByteArray get() = GrantBytes.sha256(preimage)

    override fun equals(other: Any?): Boolean = other is GrantUse && other.preimage.contentEquals(preimage)

    override fun hashCode(): Int = preimage.contentHashCode()

    companion object {
        internal const val TAG = "kuira:grant:use:v1"

        fun decode(preimage: ByteArray): GrantUse {
            val reader = GrantReader(preimage)
            reader.tag(TAG)
            val grantID = reader.bytes(32)
            val feature = reader.text(1..64)
            val subject = reader.str16(0..1024)
            val nonce = reader.bytes(16)
            val at = reader.time()
            reader.end()
            return GrantUse(grantID, feature, subject, nonce, at)
        }
    }
}

/** A revocation, signed by the root it names (spec section 5). */
sealed class GrantRevocation {
    /** The root that signs it. */
    abstract val root: ByteArray
    abstract val at: Long
    abstract val preimage: ByteArray

    class Revoke(root: ByteArray, grantID: ByteArray, override val at: Long) : GrantRevocation() {
        private val rootBytes = root.copyOf()
        private val grantIDBytes = grantID.copyOf()
        override val root: ByteArray get() = rootBytes.copyOf()
        val grantID: ByteArray get() = grantIDBytes.copyOf()
        override val preimage: ByteArray
            get() = GrantBytes.tag(REVOKE_TAG) + byteArrayOf(1, 1) + rootBytes + grantIDBytes + GrantBytes.u64(at)
    }

    class RevokeAll(root: ByteArray, val audience: GrantClientID, val generation: Long, override val at: Long) :
        GrantRevocation() {
        init {
            if (generation !in 0..GrantBytes.U32_MAX) throw GrantRefusal.Malformed("a generation is 4 bytes")
        }

        private val rootBytes = root.copyOf()
        override val root: ByteArray get() = rootBytes.copyOf()
        override val preimage: ByteArray
            get() = GrantBytes.tag(REVOKE_ALL_TAG) + byteArrayOf(1, 1) + rootBytes + GrantBytes.str16(audience.value) +
                GrantBytes.u32(generation) + GrantBytes.u64(at)
    }

    class Retire(root: ByteArray, val audience: GrantClientID, override val at: Long) : GrantRevocation() {
        private val rootBytes = root.copyOf()
        override val root: ByteArray get() = rootBytes.copyOf()
        override val preimage: ByteArray
            get() = GrantBytes.tag(RETIRE_TAG) + byteArrayOf(1, 1) + rootBytes + GrantBytes.str16(audience.value) +
                GrantBytes.u64(at)
    }

    override fun equals(other: Any?): Boolean =
        other is GrantRevocation && other::class == this::class && other.preimage.contentEquals(preimage)

    override fun hashCode(): Int = preimage.contentHashCode()

    companion object {
        private const val REVOKE_TAG = "kuira:grant:revoke:v1"
        private const val REVOKE_ALL_TAG = "kuira:grant:revoke-all:v1"
        private const val RETIRE_TAG = "kuira:grant:retire:v1"

        fun decode(preimage: ByteArray): GrantRevocation {
            val head = preimage.copyOfRange(0, minOf(preimage.size, GrantBytes.TAG_WIDTH))
            val reader = GrantReader(preimage)
            return when {
                head.contentEquals(GrantBytes.tag(REVOKE_TAG)) -> {
                    reader.tag(REVOKE_TAG)
                    reader.version()
                    reader.rootKind()
                    val root = reader.bytes(32)
                    val grantID = reader.bytes(32)
                    val at = reader.time()
                    reader.end()
                    Revoke(root, grantID, at)
                }
                head.contentEquals(GrantBytes.tag(REVOKE_ALL_TAG)) -> {
                    reader.tag(REVOKE_ALL_TAG)
                    reader.version()
                    reader.rootKind()
                    val root = reader.bytes(32)
                    val audience = GrantBytes.audience(reader.text(1..300))
                    val generation = reader.u32()
                    val at = reader.time()
                    reader.end()
                    RevokeAll(root, audience, generation, at)
                }
                head.contentEquals(GrantBytes.tag(RETIRE_TAG)) -> {
                    reader.tag(RETIRE_TAG)
                    reader.version()
                    reader.rootKind()
                    val root = reader.bytes(32)
                    val audience = GrantBytes.audience(reader.text(1..300))
                    val at = reader.time()
                    reader.end()
                    Retire(root, audience, at)
                }
                else -> throw GrantRefusal.UnknownTag
            }
        }
    }
}

/** A grantee's request for a grant, signed by the grantee as proof that it holds its key (spec section 6). */
class GrantRequest(
    val audience: GrantClientID,
    val grantee: GranteeKey,
    val client: GrantClientID,
    val scope: List<String>,
    val wantedExpiry: Long,
    nonce: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    private val nonceBytes: ByteArray

    init {
        if (grantee.arm != GranteeKey.Arm.K1(0)) throw GrantRefusal.UnknownArm
        GrantBytes.audience(audience.value)
        GrantBytes.client(client.value)
        GrantBytes.checkScope(scope)
        if (nonce.size != 16) throw GrantRefusal.Malformed("a nonce is 16 bytes")
        for (time in listOf(wantedExpiry, issuedAt, expiresAt)) GrantBytes.checkTime(time)
        if (expiresAt <= issuedAt || expiresAt - issuedAt > GrantBytes.MAX_REQUEST_WINDOW) {
            throw GrantRefusal.Malformed("a request lives at most 600 s")
        }
        nonceBytes = nonce.copyOf()
    }

    val nonce: ByteArray get() = nonceBytes.copyOf()

    val preimage: ByteArray
        get() = GrantBytes.tag(TAG) + byteArrayOf(1) + GrantBytes.str16(audience.value) + GrantBytes.granteeBytes(grantee) +
            GrantBytes.str16(client.value) + GrantBytes.scope(scope) + GrantBytes.u64(wantedExpiry) + nonceBytes +
            GrantBytes.u64(issuedAt) + GrantBytes.u64(expiresAt)

    val digest: ByteArray get() = GrantBytes.sha256(preimage)

    override fun equals(other: Any?): Boolean = other is GrantRequest && other.preimage.contentEquals(preimage)

    override fun hashCode(): Int = preimage.contentHashCode()

    companion object {
        internal const val TAG = "kuira:grant:request:v1"

        fun decode(preimage: ByteArray): GrantRequest {
            val reader = GrantReader(preimage)
            reader.tag(TAG)
            reader.version()
            val audience = GrantBytes.audience(reader.text(1..300))
            val grantee = GrantBytes.grantee(reader.u8(), reader.u8(), reader.bytes(64))
            val client = GrantBytes.client(reader.text(1..300))
            val scope = reader.scope()
            val wantedExpiry = reader.time()
            val nonce = reader.bytes(16)
            val issuedAt = reader.time()
            val expiresAt = reader.time()
            reader.end()
            return GrantRequest(audience, grantee, client, scope, wantedExpiry, nonce, issuedAt, expiresAt)
        }
    }
}

/** A structure's wire form: `base64url(preimage || signature)`, with a 64-byte signature. */
class GrantWire(preimage: ByteArray, signature: ByteArray) {
    private val preimageBytes: ByteArray
    private val signatureBytes: ByteArray

    init {
        if (signature.size != GrantBytes.SIGNATURE_LENGTH) throw GrantRefusal.Malformed("a signature is 64 bytes")
        if (preimage.size + signature.size > GrantBytes.MAX_WIRE) throw GrantRefusal.Malformed("wire size")
        preimageBytes = preimage.copyOf()
        signatureBytes = signature.copyOf()
    }

    val preimage: ByteArray get() = preimageBytes.copyOf()
    val signature: ByteArray get() = signatureBytes.copyOf()

    val text: String get() = Base64URL.encode(preimageBytes + signatureBytes)

    override fun equals(other: Any?): Boolean =
        other is GrantWire && other.preimageBytes.contentEquals(preimageBytes) &&
            other.signatureBytes.contentEquals(signatureBytes)

    override fun hashCode(): Int = 31 * preimageBytes.contentHashCode() + signatureBytes.contentHashCode()

    companion object {
        fun parse(text: String): GrantWire {
            val (preimage, signature) = GrantBytes.split(Base64URL.decode(text))
            return GrantWire(preimage, signature)
        }
    }
}
