package com.midnight.kuira.core.identity.link

/**
 * Prints link vectors for pinning in the iOS suite.
 *
 * Deliberately NOT a `@Test`. It asserts nothing, and it cannot: HPKE encapsulation is randomised,
 * so the bytes differ every run and there is no stable output to pin here. Carrying it as an
 * ignored test instead kept the unit suite's skip count off zero, which costs the signal -- a
 * genuinely masked test added later hides inside a number that already looks normal.
 *
 * What holds this format in place is [CrossPlatformLinkVectorTest], which pins captured vectors
 * from the same canonical inputs and runs on every build; `LinkVectorTests` is its iOS counterpart,
 * and the two are regenerated together.
 *
 * To regenerate: annotate [emit] with `@Test`, run it, paste the output into both suites, then
 * remove the annotation again.
 */
class EmitLinkVectors {
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

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
