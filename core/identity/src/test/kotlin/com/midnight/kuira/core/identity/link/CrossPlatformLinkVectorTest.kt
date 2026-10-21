package com.midnight.kuira.core.identity.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Holds the link protocol byte-identical to iOS.
 *
 * This matters more here than almost anywhere else in the codebase. iOS gets HPKE from CryptoKit;
 * Android has no equivalent available, so [Hpke] implements RFC 9180 by hand. These vectors are what
 * makes that defensible: the response below was sealed by CryptoKit, and a single wrong byte
 * anywhere in the labelled key schedule, the DHKEM context, or the nonce derivation makes it fail to
 * open. Passing is not evidence that the code looks right — it is evidence that two independent
 * implementations of the spec agree.
 *
 * The seal direction is covered too, in `CrossPlatformLinkVectorTest` on the iOS side.
 */
class CrossPlatformLinkVectorTest {

    /** Canonical alice entropy, the same value the backup vectors use. */
    private val entropy = ByteArray(32) { 0xA1.toByte() }
    private val privateKey = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2".unhex()

    private val request = (
        "0104f6b9611005b21f0e5ca6e0d7f6565c5a781475558e5a57095dda10dda0eb7b" +
            "f2b61c96d3f5d3bc44459dea7c8a5f1a553aacdeca8c76da4423bfe38f9c81d18b"
        ).unhex()

    private val response = (
        "0104b2f4269fd2f01b197dcad6faf513a5cf684eb5bd66a67110bb24be41a4754d" +
            "9ac70701f81701b02c7a1e028fae125d46d8f32fa95b9833e851b3d2949373d54d1c9eda4528a4c866f8817" +
            "1b9fa958aec685b910f5efe558f1e98e256de29f46b91f84f98800961ea091e92063fee84c1"
        ).unhex()

    /**
     * The public key CryptoKit derived from the pinned scalar must be the one this implementation
     * derives. If these differ, the sealed vector below was sealed to a key no Android device would
     * ever hold, and opening it would prove nothing.
     */
    @Test
    fun `the pinned request matches the pinned private key`() {
        val derived = Hpke.serializePublicKey(
            Hpke.keyPair(privateKey).public as java.security.interfaces.ECPublicKey,
        )
        assertArrayEquals(request.copyOfRange(1, request.size), derived)
    }

    /** The one that proves the hand-written HPKE. Sealed by CryptoKit, opened here. */
    @Test
    fun `opens a payload CryptoKit sealed`() {
        val opened = DeviceLink.open(
            LinkResponse.decode(response),
            with = LinkSecret(Hpke.keyPair(privateKey)),
        )
        assertArrayEquals(entropy, opened)
    }

    @Test
    fun `the pinned transcript yields the pinned code`() {
        assertEquals(
            "299713",
            DeviceLink.verificationCode(LinkRequest.decode(request), LinkResponse.decode(response)),
        )
    }

    /**
     * A transcript whose code has a leading zero. Pinned separately because an implementation that
     * formats the number without padding produces "91338" — five digits that can never match the
     * other phone, in roughly one ceremony in ten.
     */
    @Test
    fun `a code with a leading zero keeps its padding`() {
        val padded = (
            "0104aa99a1c6d08c52f9bd3ce4cf68617a1ca2849eda5d10a7ec429c16f6757b7f32b262" +
                "54a700a0e351bb87e8b9e65301dc71f8f73d920d5ff8b1fa04823ec9eecb6603920fa014c3f548406b2" +
                "704fa6481fca241ea2a571a8fa115a2e4336e8d3c79da7c6e688b152d815b6029d810c5d3"
            ).unhex()
        assertEquals(
            "091338",
            DeviceLink.verificationCode(LinkRequest.decode(request), LinkResponse.decode(padded)),
        )
    }

    /** Domain separation, pinned as bytes: a typo in either would be invisible in a round trip. */
    @Test
    fun `the domain strings are frozen`() {
        assertEquals("kuira:link-device:v1", String(LinkWireFormat.INFO, Charsets.UTF_8))
        assertEquals("kuira:link-verify:v1", String(LinkWireFormat.VERIFICATION_INFO, Charsets.UTF_8))
        assertEquals(0x01.toByte(), LinkWireFormat.VERSION)
    }

    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
