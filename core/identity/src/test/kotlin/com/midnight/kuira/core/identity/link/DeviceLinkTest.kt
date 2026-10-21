package com.midnight.kuira.core.identity.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLinkTest {

    private val entropy = ByteArray(32) { 0xA1.toByte() }

    @Test
    fun `the new device recovers exactly what the source sealed`() {
        val (request, secret) = DeviceLink.makeRequest()
        val response = DeviceLink.seal(entropy, to = request)
        assertArrayEquals(entropy, DeviceLink.open(response, with = secret))
    }

    @Test
    fun `the sealed payload fits a static QR`() {
        val (request, _) = DeviceLink.makeRequest()
        assertEquals(114, DeviceLink.seal(entropy, to = request).encode().size)
        assertEquals(66, request.encode().size)
    }

    @Test
    fun `another device's secret cannot open the response`() {
        val (request, _) = DeviceLink.makeRequest()
        val (_, eavesdropper) = DeviceLink.makeRequest()
        val response = DeviceLink.seal(entropy, to = request)
        assertThrows(LinkException.SealFailed::class.java) {
            DeviceLink.open(response, with = eavesdropper)
        }
    }

    @Test
    fun `a tampered ciphertext does not open`() {
        val (request, secret) = DeviceLink.makeRequest()
        val bytes = DeviceLink.seal(entropy, to = request).encode()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertThrows(LinkException.SealFailed::class.java) {
            DeviceLink.open(LinkResponse.decode(bytes), with = secret)
        }
    }

    @Test
    fun `a tampered encapsulated key does not open`() {
        val (request, secret) = DeviceLink.makeRequest()
        val bytes = DeviceLink.seal(entropy, to = request).encode()
        bytes[10] = (bytes[10].toInt() xor 1).toByte()
        assertThrows(LinkException::class.java) {
            DeviceLink.open(LinkResponse.decode(bytes), with = secret)
        }
    }

    /**
     * Forward secrecy rests on the encapsulation being fresh every time. Identical bytes across two
     * ceremonies would mean a captured QR replays.
     */
    @Test
    fun `every seal is fresh even for the same inputs`() {
        val (request, _) = DeviceLink.makeRequest()
        val first = DeviceLink.seal(entropy, to = request)
        val second = DeviceLink.seal(entropy, to = request)
        assertNotEquals(first.encapsulatedKey.toHex(), second.encapsulatedKey.toHex())
        assertNotEquals(first.ciphertext.toHex(), second.ciphertext.toHex())
    }

    /**
     * The attack the verification code exists for. An attacker who films the ceremony cannot read
     * the seed, but can show the new device a response of their own, sealed to the same public key,
     * which opens perfectly into a wallet they control and can later drain.
     *
     * Every other defence passes here. Only the code differs.
     */
    @Test
    fun `a substituted response opens cleanly and is caught only by the code`() {
        val (request, secret) = DeviceLink.makeRequest()
        val genuine = DeviceLink.seal(entropy, to = request)
        val attackerEntropy = ByteArray(32) { 0xEE.toByte() }
        val substituted = DeviceLink.seal(attackerEntropy, to = request)

        assertArrayEquals(attackerEntropy, DeviceLink.open(substituted, with = secret))
        assertNotEquals(
            DeviceLink.verificationCode(request, genuine),
            DeviceLink.verificationCode(request, substituted),
        )
    }

    @Test
    fun `the code is always six digits`() {
        repeat(200) {
            val (request, _) = DeviceLink.makeRequest()
            val code = DeviceLink.verificationCode(request, DeviceLink.seal(entropy, to = request))
            assertEquals(6, code.length)
            assertTrue("not numeric: $code", code.all { it.isDigit() })
        }
    }

    /** An invalid-curve point must be refused at the scan, not fed into a key exchange. */
    @Test
    fun `a request that is not a curve point is rejected`() {
        val notAPoint = byteArrayOf(LinkWireFormat.VERSION) + ByteArray(65) { 0x04 }
        assertThrows(LinkException.Malformed::class.java) { LinkRequest.decode(notAPoint) }
    }

    @Test
    fun `a future version is rejected by version not by length`() {
        val (request, _) = DeviceLink.makeRequest()
        val bytes = request.encode()
        bytes[0] = 0x02
        assertThrows(LinkException.UnsupportedVersion::class.java) { LinkRequest.decode(bytes) }
    }

    @Test
    fun `truncated and overlong payloads are rejected`() {
        val (request, _) = DeviceLink.makeRequest()
        val response = DeviceLink.seal(entropy, to = request)
        assertThrows(LinkException.Malformed::class.java) {
            LinkRequest.decode(request.encode().copyOfRange(0, 65))
        }
        assertThrows(LinkException.Malformed::class.java) {
            LinkResponse.decode(response.encode() + byteArrayOf(0))
        }
    }

    @Test
    fun `only thirty two byte entropy travels`() {
        val (request, _) = DeviceLink.makeRequest()
        for (size in listOf(0, 16, 31, 33, 64)) {
            assertThrows(LinkException.UnsupportedEntropySize::class.java) {
                DeviceLink.seal(ByteArray(size), to = request)
            }
        }
    }

    @Test
    fun `qr text is unpadded base64url and round trips`() {
        val (request, _) = DeviceLink.makeRequest()
        val text = request.qrText()
        assertTrue(text.none { it == '=' || it == '+' || it == '/' })
        assertArrayEquals(request.encode(), LinkQrText.decode(text))
        assertArrayEquals(request.encode(), LinkQrText.decode("  \n$text\n "))
    }

    @Test
    fun `empty and non base64 text is rejected`() {
        assertThrows(LinkException.Malformed::class.java) { LinkQrText.decode("") }
        assertThrows(LinkException.Malformed::class.java) { LinkQrText.decode("!!!!") }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
