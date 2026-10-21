package com.midnight.kuira.core.identity.link

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HPKE (RFC 9180) base mode, ciphersuite `DHKEM(P-256, HKDF-SHA256)` / `HKDF-SHA256` /
 * `AES-256-GCM`, built on the JDK's own primitives.
 *
 * **Why this is hand-written rather than a library call.** iOS gets HPKE from CryptoKit. Android has
 * no equivalent at our minSdk, and the obvious answer — BouncyCastle, which ships HPKE from 1.72 —
 * is closed off: bitcoinj strictly pins `bcprov-jdk15to18:1.68`, and adding `bcprov-jdk18on`
 * alongside it puts two artifacts with identical package names on the classpath. This repo has no
 * app module, so that collision would not surface here; it would land in every consumer app's dex
 * merge, which is the opposite of an SDK absorbing complexity on its users' behalf. Upgrading
 * BouncyCastle underneath bitcoinj instead would put a 2021-era key-derivation library through a
 * ten-version jump to enable an unrelated feature.
 *
 * **Why writing it is defensible anyway.** This is a fully specified construction over primitives
 * the JDK already provides — ECDH, HMAC-SHA256, AES-GCM — and every byte of it is checkable. The
 * cross-platform tests seal with CryptoKit and open here, and seal here and open with CryptoKit. A
 * single wrong byte anywhere in the labelled key schedule fails both directions loudly. That is a
 * far stronger guarantee than "we called a library and it returned something".
 *
 * Only what the link ceremony needs is implemented: base mode, single-shot, one message per
 * context. There is no PSK mode, no sender authentication, and no sequence-number handling, because
 * a ceremony seals exactly one payload. Anything else should be added with the RFC in hand.
 */
internal object Hpke {

    // Ciphersuite identifiers, RFC 9180 section 7.
    private const val KEM_P256_HKDF_SHA256 = 0x0010
    private const val KDF_HKDF_SHA256 = 0x0001
    private const val AEAD_AES_256_GCM = 0x0002
    private const val MODE_BASE: Byte = 0x00

    // Ciphersuite lengths, in bytes.
    private const val N_SECRET = 32   // KEM shared secret
    private const val N_KEY = 32      // AES-256 key
    private const val N_NONCE = 12    // AES-GCM nonce
    private const val HASH_LEN = 32   // SHA-256 output
    private const val GCM_TAG_BITS = 128

    private const val CURVE = "secp256r1"
    private const val HMAC = "HmacSHA256"
    private const val UNCOMPRESSED_TAG: Byte = 0x04
    private const val COORDINATE_SIZE = 32

    /**
     * `TWO` is API 33 and this module's minSdk is 30, so referencing it would throw
     * `NoSuchFieldError` on Android 11 and 12 — inside the key exchange, where it would look like a
     * corrupt QR rather than a missing field.
     */
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val THREE: BigInteger = BigInteger.valueOf(3)

    private val VERSION_LABEL = "HPKE-v1".toByteArray(Charsets.US_ASCII)

    /** `suite_id` for the KEM's own labelled derivations: `"KEM" || I2OSP(kem_id, 2)`. */
    private val kemSuiteId = "KEM".toByteArray(Charsets.US_ASCII) + i2osp(KEM_P256_HKDF_SHA256, 2)

    /** `suite_id` for the key schedule: `"HPKE" || kem_id || kdf_id || aead_id`. */
    private val hpkeSuiteId = "HPKE".toByteArray(Charsets.US_ASCII) +
        i2osp(KEM_P256_HKDF_SHA256, 2) +
        i2osp(KDF_HKDF_SHA256, 2) +
        i2osp(AEAD_AES_256_GCM, 2)

    /** What a sender produces: the encapsulated key to publish, and the sealed payload. */
    data class Sealed(val encapsulatedKey: ByteArray, val ciphertext: ByteArray)

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE))
        }.generateKeyPair()

    /**
     * Rebuilds a key pair from a raw 32-byte private scalar.
     *
     * Needed to open a payload sealed to a known key — the cross-platform vectors pin a fixed
     * recipient so a blob CryptoKit sealed can be opened here.
     */
    fun keyPair(rawPrivateKey: ByteArray): KeyPair {
        require(rawPrivateKey.size == COORDINATE_SIZE) { "P-256 private key must be 32 bytes" }
        val spec = curveParameters()
        val scalar = BigInteger(1, rawPrivateKey)
        val private = KeyFactory.getInstance("EC")
            .generatePrivate(java.security.spec.ECPrivateKeySpec(scalar, spec))
        val publicPoint = multiplyGenerator(scalar, spec)
        val public = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(publicPoint, spec))
        return KeyPair(public, private)
    }

    /**
     * Scalar multiplication of the curve generator, in affine Jacobian-free form.
     *
     * The JDK exposes no public point arithmetic, and deriving the public key from a private scalar
     * is the one operation `KeyFactory` will not do. Double-and-add over `BigInteger` is enough:
     * this runs once per ceremony on a fixed generator, never in a loop, and never on secret data
     * whose timing an attacker can observe across a network.
     */
    private fun multiplyGenerator(scalar: BigInteger, spec: ECParameterSpec): ECPoint {
        val p = (spec.curve.field as java.security.spec.ECFieldFp).p
        val a = spec.curve.a
        var result: ECPoint? = null
        var addend = spec.generator
        for (bit in 0 until scalar.bitLength()) {
            if (scalar.testBit(bit)) result = add(result, addend, p, a)
            addend = add(addend, addend, p, a)!!
        }
        return requireNonNull(result)
    }

    private fun requireNonNull(point: ECPoint?): ECPoint =
        point ?: throw IllegalArgumentException("private key must not be zero")

    private fun add(first: ECPoint?, second: ECPoint, p: BigInteger, a: BigInteger): ECPoint? {
        if (first == null) return second
        if (first == ECPoint.POINT_INFINITY) return second
        val slope = if (first == second) {
            if (first.affineY.signum() == 0) return ECPoint.POINT_INFINITY
            first.affineX.modPow(TWO, p)
                .multiply(THREE)
                .add(a)
                .multiply(first.affineY.shiftLeft(1).modInverse(p))
                .mod(p)
        } else {
            if (first.affineX == second.affineX) return ECPoint.POINT_INFINITY
            second.affineY.subtract(first.affineY)
                .multiply(second.affineX.subtract(first.affineX).modInverse(p))
                .mod(p)
        }
        val x = slope.modPow(TWO, p).subtract(first.affineX).subtract(second.affineX).mod(p)
        val y = slope.multiply(first.affineX.subtract(x)).subtract(first.affineY).mod(p)
        return ECPoint(x, y)
    }

    /** Uncompressed X9.62: `0x04 || X(32) || Y(32)` — the same bytes CryptoKit calls `x963`. */
    fun serializePublicKey(key: ECPublicKey): ByteArray {
        val point = key.w
        return byteArrayOf(UNCOMPRESSED_TAG) +
            fixedWidth(point.affineX) +
            fixedWidth(point.affineY)
    }

    /**
     * Parses an uncompressed point and **verifies it satisfies the curve equation**.
     *
     * Without this check a caller could hand over a point on a different, weaker curve and learn
     * information about the private key from the resulting exchange. `KeyFactory` does not reliably
     * reject such a point, so the check is explicit here rather than assumed.
     */
    fun deserializePublicKey(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 1 + 2 * COORDINATE_SIZE) { "expected an uncompressed P-256 point" }
        require(bytes[0] == UNCOMPRESSED_TAG) { "expected an uncompressed P-256 point" }
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_SIZE, bytes.size))

        val spec = curveParameters()
        val field = spec.curve.field as java.security.spec.ECFieldFp
        val p = field.p
        require(x.signum() >= 0 && x < p && y.signum() >= 0 && y < p) { "coordinate out of range" }
        // y^2 == x^3 + ax + b (mod p)
        val left = y.modPow(TWO, p)
        val right = x.modPow(THREE, p)
            .add(spec.curve.a.multiply(x))
            .add(spec.curve.b)
            .mod(p)
        require(left == right) { "point is not on P-256" }

        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    /** Seals `plaintext` to `recipient`, RFC 9180 `SealBase`. */
    fun seal(recipient: ECPublicKey, info: ByteArray, plaintext: ByteArray): Sealed {
        val ephemeral = generateKeyPair()
        val encapsulated = serializePublicKey(ephemeral.public as ECPublicKey)
        val shared = sharedSecret(
            privateKey = ephemeral.private,
            peer = recipient,
            kemContext = encapsulated + serializePublicKey(recipient),
        )
        val (key, nonce) = keySchedule(shared, info)
        return Sealed(encapsulated, aead(Cipher.ENCRYPT_MODE, key, nonce, plaintext))
    }

    /** Opens a payload sealed to `recipient`, RFC 9180 `OpenBase`. */
    fun open(
        encapsulatedKey: ByteArray,
        recipient: KeyPair,
        info: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val ephemeralPublic = deserializePublicKey(encapsulatedKey)
        val shared = sharedSecret(
            privateKey = recipient.private,
            peer = ephemeralPublic,
            kemContext = encapsulatedKey + serializePublicKey(recipient.public as ECPublicKey),
        )
        val (key, nonce) = keySchedule(shared, info)
        return aead(Cipher.DECRYPT_MODE, key, nonce, ciphertext)
    }

    // MARK: - DHKEM

    /**
     * `ExtractAndExpand` over the raw ECDH output — RFC 9180 section 4.1.
     *
     * `KeyAgreement("ECDH")` returns the x-coordinate of the shared point, which is exactly the
     * `dh` value DHKEM specifies.
     */
    private fun sharedSecret(privateKey: PrivateKey, peer: ECPublicKey, kemContext: ByteArray): ByteArray {
        val dh = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }
        val eaePrk = labeledExtract(kemSuiteId, ByteArray(0), "eae_prk", dh)
        dh.fill(0)
        return labeledExpand(kemSuiteId, eaePrk, "shared_secret", kemContext, N_SECRET)
    }

    // MARK: - Key schedule

    /** RFC 9180 section 5.1, base mode: no PSK, so both PSK inputs are empty. */
    private fun keySchedule(sharedSecret: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val pskIdHash = labeledExtract(hpkeSuiteId, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(hpkeSuiteId, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(MODE_BASE) + pskIdHash + infoHash

        val secret = labeledExtract(hpkeSuiteId, sharedSecret, "secret", ByteArray(0))
        val key = labeledExpand(hpkeSuiteId, secret, "key", context, N_KEY)
        // Single message per context, so the sequence number is zero and the nonce is the base
        // nonce unmodified. A second message would have to XOR in the sequence number.
        val nonce = labeledExpand(hpkeSuiteId, secret, "base_nonce", context, N_NONCE)
        return key to nonce
    }

    // MARK: - Labelled HKDF

    private fun labeledExtract(
        suiteId: ByteArray,
        salt: ByteArray,
        label: String,
        ikm: ByteArray,
    ): ByteArray = extract(salt, VERSION_LABEL + suiteId + label.toByteArray(Charsets.US_ASCII) + ikm)

    private fun labeledExpand(
        suiteId: ByteArray,
        prk: ByteArray,
        label: String,
        info: ByteArray,
        length: Int,
    ): ByteArray = expand(
        prk,
        i2osp(length, 2) + VERSION_LABEL + suiteId + label.toByteArray(Charsets.US_ASCII) + info,
        length,
    )

    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        // An all-zero key of the hash length is HKDF's defined behaviour for an empty salt, and Mac
        // rejects a zero-length key outright.
        val key = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(ikm)
        }
    }

    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "HKDF cannot expand to $length bytes" }
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, output, written, take)
            written += take
            counter++
        }
        return output
    }

    // MARK: - AEAD

    private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            doFinal(input)
        }

    // MARK: - Encoding helpers

    /** RFC 8017 `I2OSP`: a big-endian integer in a fixed number of bytes. */
    private fun i2osp(value: Int, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var remaining = value
        for (index in size - 1 downTo 0) {
            bytes[index] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        return bytes
    }

    /**
     * A coordinate as exactly 32 bytes.
     *
     * `BigInteger.toByteArray` adds a leading zero for values with the high bit set and drops
     * leading zeros for small ones, so neither end can be trusted to be the right width.
     */
    private fun fixedWidth(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == COORDINATE_SIZE -> raw
            raw.size > COORDINATE_SIZE -> raw.copyOfRange(raw.size - COORDINATE_SIZE, raw.size)
            else -> ByteArray(COORDINATE_SIZE - raw.size) + raw
        }
    }

    private fun curveParameters(): ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }
}
