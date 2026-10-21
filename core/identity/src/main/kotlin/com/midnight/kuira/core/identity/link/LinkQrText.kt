package com.midnight.kuira.core.identity.link

import java.util.Base64

/**
 * How the link bytes become the text in a QR code, and back.
 *
 * Scanners on both platforms hand back a string, not bytes, so the binary payload needs a text
 * encoding — and that encoding is as much a part of the cross-platform contract as the bytes
 * underneath it. Base64url without padding: URL-safe, present in both standard libraries, and
 * unpadded so the two implementations cannot disagree about trailing `=`.
 *
 * There is deliberately no `kuira://` URL scheme here. A URL QR is offered as a deep link by the
 * system camera, which would invite someone to start a link ceremony from a QR they found rather
 * than one shown by a phone in their hand — the proximity assumption the whole design rests on.
 *
 * `java.util.Base64` rather than `android.util.Base64` so this stays a JVM unit-test concern; the
 * module's minSdk is 30, well past the API 26 this needs.
 */
object LinkQrText {

    /** Renders payload bytes as the string to encode in a QR. */
    fun encode(payload: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload)

    /**
     * Parses text handed back by a scanner.
     *
     * Camera scans pick up whatever is in frame, so failing here is an ordinary occurrence rather
     * than an exceptional one — callers are expected to ignore it and keep scanning.
     */
    fun decode(text: String): ByteArray {
        val trimmed = text.trim()
        val bytes = try {
            // The URL decoder accepts unpadded input, so nothing has to be re-padded by hand.
            Base64.getUrlDecoder().decode(trimmed)
        } catch (e: IllegalArgumentException) {
            throw LinkException.Malformed("not base64url")
        }
        if (bytes.isEmpty()) throw LinkException.Malformed("empty payload")
        return bytes
    }
}

/** The text to render as this device's QR. */
fun LinkRequest.qrText(): String = LinkQrText.encode(encode())

/** The text to render as this device's QR. */
fun LinkResponse.qrText(): String = LinkQrText.encode(encode())
