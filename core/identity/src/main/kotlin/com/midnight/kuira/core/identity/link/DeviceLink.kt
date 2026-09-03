package com.midnight.kuira.core.identity.link

import java.security.MessageDigest
import java.security.interfaces.ECPublicKey

/**
 * Carries one wallet onto a second device, in person, with no server in the middle.
 *
 * Passkey-PRF cannot reproduce a seed across ecosystems — a Google Password Manager credential and
 * an iCloud Keychain credential yield different bytes — so the same sigil cannot simply *appear* on
 * the other platform. Instead one seed stays canonical and is transferred, sealed with HPKE, to
 * each device that joins. Both devices end up holding the same seed, and each remains fully
 * self-custodial.
 *
 * The flow is two scans:
 *
 * 1. The new device calls [makeRequest] and shows the request as a QR.
 * 2. The source device scans it, confirms with the user, and calls [seal].
 * 3. The source shows the response as a QR; the new device scans it.
 * 4. **Both** compute [verificationCode] and the user checks the two screens agree, *before* the new
 *    device commits anything.
 * 5. The new device calls [open] and stores the entropy under its own biometric ACL.
 *
 * Step 4 is not decoration. Everything else is defeated by the camera scan and by HPKE, but a
 * swapped ciphertext QR still decodes and still opens — into a *different* wallet the attacker
 * controls. The code is the only thing that catches it.
 *
 * This object performs no I/O and touches no camera: it is the byte layer, and it is byte-identical
 * to the iOS `DeviceLink`.
 */
object DeviceLink {

    /**
     * Generates the single-use key pair for one ceremony.
     *
     * @return the request to display, and the secret to keep until the response is scanned.
     */
    fun makeRequest(): Pair<LinkRequest, LinkSecret> {
        val keyPair = Hpke.generateKeyPair()
        val encoded = Hpke.serializePublicKey(keyPair.public as ECPublicKey)
        return LinkRequest.ofValidated(encoded) to LinkSecret(keyPair)
    }

    /**
     * Seals the wallet entropy to a scanned request.
     *
     * @param entropy the canonical 32-byte BIP-39 entropy. Only the entropy travels; the new device
     *   re-derives the mnemonic and the 64-byte seed from it.
     */
    fun seal(entropy: ByteArray, to: LinkRequest): LinkResponse {
        if (entropy.size != LinkWireFormat.ENTROPY_SIZE) {
            throw LinkException.UnsupportedEntropySize(entropy.size)
        }
        val sealed = Hpke.seal(to.recipientKey(), LinkWireFormat.INFO, entropy)
        return LinkResponse(sealed.encapsulatedKey, sealed.ciphertext)
    }

    /**
     * Opens a scanned response.
     *
     * Call this only after the user has confirmed the verification codes match; a response that
     * opens is not the same as a response that came from the intended phone.
     *
     * @return the 32-byte entropy. The caller owns it — hand it to custody and wipe it.
     */
    fun open(response: LinkResponse, with: LinkSecret): ByteArray {
        val plaintext = try {
            Hpke.open(
                encapsulatedKey = response.encapsulatedKey,
                recipient = with.keyPair,
                info = LinkWireFormat.INFO,
                ciphertext = response.ciphertext,
            )
        } catch (e: Exception) {
            // A malformed encapsulated key and a wrong one are the same event to the user.
            throw LinkException.SealFailed(e.message ?: "authentication failed")
        }
        if (plaintext.size != LinkWireFormat.ENTROPY_SIZE) {
            plaintext.fill(0)
            throw LinkException.UnsupportedEntropySize(plaintext.size)
        }
        return plaintext
    }

    /**
     * The short code both phones display, so the user can confirm they are talking to each other.
     *
     * Derived from the whole visible transcript — the key the source sealed *to*, and the response
     * it produced — so both sides compute it from what they actually hold, and any substitution
     * anywhere in either QR changes it. The new device can compute this before decrypting, which is
     * the point: the check has to happen before the seed is committed.
     *
     * Six digits, not words: the code has to match across a Kotlin and a Swift implementation and be
     * read aloud between two people, and digits need no shared wordlist to stay in sync. Zero-padded
     * — an unpadded five-digit render can never match the other phone, and it happens about one
     * ceremony in ten.
     */
    fun verificationCode(request: LinkRequest, response: LinkResponse): String {
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(LinkWireFormat.VERIFICATION_INFO)
            update(request.publicKey)
            update(response.encapsulatedKey)
            update(response.ciphertext)
            digest()
        }
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (digest[index].toLong() and 0xFF)
        }
        return "%06d".format(value % LinkWireFormat.VERIFICATION_MODULUS)
    }
}
