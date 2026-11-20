package com.midnight.kuira.core.grant

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * A grantee key's wire form (scoped-grants MIP section 3.4): 128 lowercase hex characters, `x || y` little-endian,
 * each coordinate below its field's modulus; a coordinate at or above it is refused, never reduced.
 */
class GranteeKeyTest {
    private val k1Wire = "9817f8165b81f259d928ce2ddbfc9b02070b87ce9562a055acbbdcf97e66be79" +
        "b8d410fb8fd0479c195485a648b417fda808110efcfba45d65c4a32677da3a48"

    private inline fun <reified E : GrantError> refused(body: () -> Unit): E {
        try {
            body()
        } catch (error: GrantError) {
            if (error is E) return error
            throw AssertionError("expected ${E::class.simpleName}, got $error", error)
        }
        fail("expected ${E::class.simpleName}, and it was accepted")
        throw IllegalStateException()
    }

    @Test
    fun theWireFormRoundTrips() {
        val key = GranteeKey.fromWire(GranteeKey.Arm.K1(0), k1Wire)

        assertEquals(k1Wire, key.wire)
        assertEquals("ecdsa_secp256k1_sha256", key.arm.scheme)
        assertEquals("ecdsa_secp256r1_webauthn", GranteeKey.Arm.R1.scheme)
        assertEquals("schnorr_jubjub", GranteeKey.Arm.V1.scheme)
    }

    @Test
    fun malformedWireFormsAreRefused() {
        val malformed = listOf(k1Wire.dropLast(2), k1Wire + "00", k1Wire.uppercase(), k1Wire.dropLast(1) + "g")

        for (wire in malformed) {
            val error = refused<GrantError.MalformedKey> { GranteeKey.fromWire(GranteeKey.Arm.K1(0), wire) }
            assertEquals(wire, "a key is 128 lowercase hex characters", error.detail)
        }
    }

    /** Each field's modulus itself is refused and one below it accepted, for both coordinates and every arm. */
    @Test
    fun aCoordinateAtItsModulusIsRefusedAndOneBelowIsAccepted() {
        val moduli = listOf(
            GranteeKey.Arm.K1(0) to "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f",
            GranteeKey.Arm.R1 to "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            GranteeKey.Arm.V1 to "73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001",
        )
        val small = ByteArray(32).also { it[31] = 1 }

        for ((arm, bigEndian) in moduli) {
            val modulus = checkNotNull(hexBytes(bigEndian))
            val below = subtractOne(modulus).reversedArray()
            val at = modulus.reversedArray()

            GranteeKey(arm, below, small)
            GranteeKey(arm, small, below)
            refused<GrantError.NonCanonicalCoordinate> { GranteeKey(arm, at, small) }
            refused<GrantError.NonCanonicalCoordinate> { GranteeKey(arm, small, at) }
        }
    }

    @Test
    fun aK1EnvelopeOtherThanZeroOrOneIsRefused() {
        val error = refused<GrantError.UnknownEnvelope> { GranteeKey.fromWire(GranteeKey.Arm.K1(2), k1Wire) }
        assertEquals(2, error.envelope)
    }

    @Test
    fun aCoordinateThatIsNot32BytesIsRefused() {
        val error = refused<GrantError.MalformedKey> { GranteeKey(GranteeKey.Arm.V1, ByteArray(31) { 1 }, ByteArray(32) { 1 }) }
        assertEquals("each coordinate is 32 bytes", error.detail)
    }

    private fun subtractOne(bigEndian: ByteArray): ByteArray {
        val bytes = bigEndian.copyOf()
        var index = bytes.size - 1
        while (bytes[index] == 0.toByte()) {
            bytes[index] = 0xff.toByte()
            index--
        }
        bytes[index] = (bytes[index] - 1).toByte()
        return bytes
    }
}
