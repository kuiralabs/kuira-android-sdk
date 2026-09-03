package com.midnight.kuira.core.identity.link

import org.junit.Ignore
import org.junit.Test

/**
 * Prints link vectors for pinning in the iOS suite. Asserts nothing by design — un-ignore it only
 * when regenerating the vectors on both sides, and update `CrossPlatformLinkVectorTest` here and
 * `LinkVectorTests` on iOS together.
 *
 * HPKE encapsulation is randomised, so these are one-time captures; what stays reproducible is that
 * a given response always opens to the same entropy and yields the same six digits.
 */
class EmitLinkVectors {
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    @Ignore("Regeneration helper — un-ignore to emit fresh vectors for the iOS suite")
    fun emit() {
        val privateKey = "b2".repeat(32).unhex()
        val entropy = ByteArray(32) { 0xA1.toByte() }
        val request = LinkRequest.ofValidated(
            Hpke.serializePublicKey(Hpke.keyPair(privateKey).public as java.security.interfaces.ECPublicKey),
        )
        val response = DeviceLink.seal(entropy, to = request)
        println("ANDROID-LINK request  = ${request.encode().hex()}")
        println("ANDROID-LINK response = ${response.encode().hex()}")
        println("ANDROID-LINK code     = ${DeviceLink.verificationCode(request, response)}")
        println("ANDROID-LINK qrText   = ${response.qrText()}")
    }
}
