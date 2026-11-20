package com.midnight.kuira.core.grant

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.math.BigInteger
import java.security.MessageDigest

/** A signature check's verdict: the key is refused, the signature is refused, or both hold. */
enum class GrantSignatureCheck { VALID, BAD_KEY, BAD_SIGNATURE }

/**
 * The curve checks of the grant spec (section 2.2 for Ed25519, 2.3 for secp256k1), made here explicitly so the
 * verdict never depends on a library's defaults: canonical encodings, prime-order keys, `S < L`, the cofactorless
 * equation, and unreduced ECDSA scalars with high `s` accepted.
 */
object GrantCurves {
    // ---- Ed25519 (section 2.2) ----

    /** A canonical Ed25519 key of prime order: `[L]A = O`, and not one of the small-order points. */
    fun ed25519KeyIsValid(key: ByteArray): Boolean = ed25519Key(key) != null

    /** RFC 8032 Ed25519 under the spec's rules: canonical `R`, `S < L`, cofactorless `[S]B = R + [k]A`. */
    fun ed25519Verify(key: ByteArray, message: ByteArray, signature: ByteArray): GrantSignatureCheck {
        val point = ed25519Key(key) ?: return GrantSignatureCheck.BAD_KEY
        if (signature.size != 64) return GrantSignatureCheck.BAD_SIGNATURE
        val rBytes = signature.copyOfRange(0, 32)
        if (Edwards.decode(rBytes) == null) return GrantSignatureCheck.BAD_SIGNATURE
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= Edwards.L) return GrantSignatureCheck.BAD_SIGNATURE
        val hash = MessageDigest.getInstance("SHA-512").run {
            update(rBytes)
            update(key)
            update(message)
            digest()
        }
        val k = littleEndian(hash).mod(Edwards.L)
        val check = Edwards.add(Edwards.multiply(Edwards.BASE, s), Edwards.multiply(Edwards.negate(point), k))
        return if (Edwards.encode(check).contentEquals(rBytes)) GrantSignatureCheck.VALID else GrantSignatureCheck.BAD_SIGNATURE
    }

    /** The Ed25519 public key of a 32-byte RFC 8032 secret. */
    internal fun ed25519PublicKey(secret: ByteArray): ByteArray {
        require(secret.size == 32) { "an Ed25519 secret is 32 bytes" }
        val key = ByteArray(32)
        Ed25519.generatePublicKey(secret, 0, key, 0)
        return key
    }

    /** RFC 8032 Ed25519, deterministic. */
    internal fun ed25519Sign(secret: ByteArray, message: ByteArray): ByteArray {
        require(secret.size == 32) { "an Ed25519 secret is 32 bytes" }
        val signature = ByteArray(64)
        Ed25519.sign(secret, 0, message, 0, message.size, signature, 0)
        return signature
    }

    private fun ed25519Key(bytes: ByteArray): Edwards.Point? {
        val point = Edwards.decode(bytes) ?: return null
        val torsionFree = Edwards.isIdentity(Edwards.multiply(point, Edwards.L))
        val smallOrder = Edwards.isIdentity(Edwards.multiply(point, BigInteger.valueOf(8)))
        return if (torsionFree && !smallOrder) point else null
    }

    // ---- secp256k1 (section 2.3) ----

    private val k1Curve = CustomNamedCurves.getByName("secp256k1")
    private val k1Domain = ECDomainParameters(k1Curve.curve, k1Curve.g, k1Curve.n, k1Curve.h)
    private val k1N: BigInteger = k1Curve.n
    private val k1P: BigInteger = k1Curve.curve.field.characteristic

    /** `x || y`, each 32 bytes little-endian, below the field prime and on the curve (which refuses the identity). */
    fun k1KeyIsValid(key: ByteArray): Boolean = k1Key(key) != null

    /**
     * ECDSA over secp256k1, envelope 0: the signature `r || s` (little-endian, each in `[1, n − 1]`, never
     * reduced, high `s` accepted) over `H(digest)`.
     */
    fun k1Verify(key: ByteArray, digest: ByteArray, signature: ByteArray): GrantSignatureCheck {
        val point = k1Key(key) ?: return GrantSignatureCheck.BAD_KEY
        if (digest.size != 32 || signature.size != 64) return GrantSignatureCheck.BAD_SIGNATURE
        val r = littleEndian(signature.copyOfRange(0, 32))
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (!inScalarRange(r) || !inScalarRange(s)) return GrantSignatureCheck.BAD_SIGNATURE
        val verifier = ECDSASigner()
        verifier.init(false, ECPublicKeyParameters(point, k1Domain))
        return if (verifier.verifySignature(GrantBytes.sha256(digest), r, s)) {
            GrantSignatureCheck.VALID
        } else {
            GrantSignatureCheck.BAD_SIGNATURE
        }
    }

    /** The grantee's public key for a 32-byte big-endian secret in `[1, n − 1]`, as `x || y` little-endian. */
    internal fun k1PublicKey(secret: ByteArray): ByteArray {
        val d = k1Secret(secret)
        val point = k1Domain.g.multiply(d).normalize()
        return toLittleEndian32(point.affineXCoord.toBigInteger()) + toLittleEndian32(point.affineYCoord.toBigInteger())
    }

    /** ECDSA over `H(digest)` with RFC 6979 nonces, low `s`, as `r || s` little-endian. */
    internal fun k1Sign(secret: ByteArray, digest: ByteArray): ByteArray {
        require(digest.size == 32) { "a digest is 32 bytes" }
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(k1Secret(secret), k1Domain))
        val (r, s) = signer.generateSignature(GrantBytes.sha256(digest)).let { it[0] to it[1] }
        val low = if (s > k1N.shiftRight(1)) k1N.subtract(s) else s
        return toLittleEndian32(r) + toLittleEndian32(low)
    }

    private fun k1Secret(secret: ByteArray): BigInteger {
        if (secret.size != 32) throw GrantRefusal.Malformed("a secp256k1 secret is 32 bytes")
        val d = BigInteger(1, secret)
        if (!inScalarRange(d)) throw GrantRefusal.Malformed("a secp256k1 secret lies in [1, n - 1]")
        return d
    }

    private fun k1Key(bytes: ByteArray): ECPoint? {
        if (bytes.size != 64) return null
        val x = littleEndian(bytes.copyOfRange(0, 32))
        val y = littleEndian(bytes.copyOfRange(32, 64))
        if (x >= k1P || y >= k1P) return null
        return try {
            k1Curve.curve.validatePoint(x, y)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun inScalarRange(value: BigInteger): Boolean = value.signum() > 0 && value < k1N

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun toLittleEndian32(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(32)
        val length = minOf(bigEndian.size, 32)
        System.arraycopy(bigEndian, bigEndian.size - length, out, 32 - length, length)
        return out.reversedArray()
    }

    /**
     * Edwards25519 point arithmetic in extended coordinates (RFC 8032 section 5.1.4), for the checks no library
     * call makes on our behalf. Variable time: it only ever touches public keys and signatures.
     */
    private object Edwards {
        private val TWO: BigInteger = BigInteger.valueOf(2)
        val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))
        val L: BigInteger = TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

        class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

        val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        val BASE: Point = checkNotNull(
            decode(checkNotNull(hexBytes("5866666666666666666666666666666666666666666666666666666666666666"))),
        )

        /**
         * RFC 8032 section 5.1.3, strictly: `y < p`, a square root exists, and `x = 0` with the sign bit set is
         * refused. An encoding that decodes is therefore the one its point re-encodes to.
         */
        fun decode(bytes: ByteArray): Point? {
            if (bytes.size != 32) return null
            val raw = bytes.copyOf()
            val sign = (raw[31].toInt() shr 7) and 1
            raw[31] = (raw[31].toInt() and 0x7f).toByte()
            val y = BigInteger(1, raw.reversedArray())
            if (y >= P) return null
            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val x2 = u.multiply(v.modInverse(P)).mod(P)
            var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (x.multiply(x).mod(P) != x2) {
                x = x.multiply(SQRT_M1).mod(P)
                if (x.multiply(x).mod(P) != x2) return null
            }
            if (x.signum() == 0 && sign == 1) return null
            if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
            return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }

        fun encode(point: Point): ByteArray {
            val zInverse = point.z.modInverse(P)
            val x = point.x.multiply(zInverse).mod(P)
            val y = point.y.multiply(zInverse).mod(P)
            val out = toLittleEndian32(y)
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        fun add(a: Point, b: Point): Point {
            val aa = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
            val bb = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
            val cc = a.t.multiply(TWO_D).multiply(b.t).mod(P)
            val dd = a.z.multiply(TWO).multiply(b.z).mod(P)
            val e = bb.subtract(aa)
            val f = dd.subtract(cc)
            val g = dd.add(cc)
            val h = bb.add(aa)
            return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
        }

        fun negate(point: Point): Point = Point(P.subtract(point.x).mod(P), point.y, point.z, P.subtract(point.t).mod(P))

        fun multiply(point: Point, scalar: BigInteger): Point {
            var result = IDENTITY
            for (bit in scalar.bitLength() - 1 downTo 0) {
                result = add(result, result)
                if (scalar.testBit(bit)) result = add(result, point)
            }
            return result
        }

        /** `x = 0` and `y = z`, projectively. */
        fun isIdentity(point: Point): Boolean = point.x.signum() == 0 && point.y == point.z

        private val TWO_D: BigInteger get() = D.multiply(TWO).mod(P)
    }
}
