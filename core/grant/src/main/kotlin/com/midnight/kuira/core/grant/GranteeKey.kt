package com.midnight.kuira.core.grant

/**
 * The key a grant is issued to, in the arms and wire form of Midnight Passport's scoped-grants MIP (sections 3.1
 * and 3.4): affine `x || y`, each coordinate a 32-byte little-endian integer below its field's modulus.
 *
 * Only the encoding is checked here; whether the point lies on its curve is checked where a signature is verified.
 */
class GranteeKey(val arm: Arm, x: ByteArray, y: ByteArray) {
    sealed class Arm {
        /** The MIP's wire `scheme` name. */
        abstract val scheme: String

        internal abstract val modulus: ByteArray

        /** ECDSA over secp256k1; `envelope` 0 signs the digest itself, 1 the dApp connector's prefixed digest. */
        data class K1(val envelope: Int) : Arm() {
            override val scheme: String get() = "ecdsa_secp256k1_sha256"
            override val modulus: ByteArray get() = SECP256K1_PRIME
        }

        /** ECDSA over secp256r1 through a WebAuthn assertion. */
        data object R1 : Arm() {
            override val scheme: String get() = "ecdsa_secp256r1_webauthn"
            override val modulus: ByteArray get() = P256_PRIME
        }

        /** Schnorr over JubJub. */
        data object V1 : Arm() {
            override val scheme: String get() = "schnorr_jubjub"
            override val modulus: ByteArray get() = BLS12_381_SCALAR_FIELD
        }
    }

    private val xBytes: ByteArray
    private val yBytes: ByteArray

    init {
        if (arm is Arm.K1 && arm.envelope !in 0..1) throw GrantError.UnknownEnvelope(arm.envelope)
        if (x.size != 32 || y.size != 32) throw GrantError.MalformedKey("each coordinate is 32 bytes")
        if (!isBelow(x, arm.modulus) || !isBelow(y, arm.modulus)) throw GrantError.NonCanonicalCoordinate()
        xBytes = x.copyOf()
        yBytes = y.copyOf()
    }

    /** Little-endian. */
    val x: ByteArray get() = xBytes.copyOf()

    /** Little-endian. */
    val y: ByteArray get() = yBytes.copyOf()

    /** The wire form: 128 lowercase hex characters, `x || y`. */
    val wire: String get() = hex(xBytes + yBytes)

    override fun equals(other: Any?): Boolean =
        other is GranteeKey && other.arm == arm && other.xBytes.contentEquals(xBytes) && other.yBytes.contentEquals(yBytes)

    override fun hashCode(): Int = 31 * (31 * arm.hashCode() + xBytes.contentHashCode()) + yBytes.contentHashCode()

    override fun toString(): String = "GranteeKey(${arm.scheme}, $wire)"

    companion object {
        /** A key from its wire form: 128 lowercase hex characters, `x || y`. */
        fun fromWire(arm: Arm, wire: String): GranteeKey {
            val bytes = if (wire.length == 128) hexBytes(wire) else null
            if (bytes == null) throw GrantError.MalformedKey("a key is 128 lowercase hex characters")
            return GranteeKey(arm, bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
        }

        private fun isBelow(littleEndian: ByteArray, modulus: ByteArray): Boolean =
            GrantBytes.compareUnsigned(littleEndian.reversedArray(), modulus) < 0

        private val SECP256K1_PRIME = constant("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f")
        private val P256_PRIME = constant("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
        private val BLS12_381_SCALAR_FIELD = constant("73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001")

        private fun constant(text: String): ByteArray = checkNotNull(hexBytes(text)) { "a constant that is not hex" }
    }
}
