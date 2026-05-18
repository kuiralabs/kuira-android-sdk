package com.midnight.kuira.dapp.wallet

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.core.graphics.set

/**
 * Renders a QR code for [text] as a square bitmap.
 *
 * **API contract:** part of the dapp-ui public surface. Consumers (e.g.
 * Kicks's `CreateMatchScreen`) call this directly to render arbitrary QR
 * payloads (match-share links, addresses, etc.) without needing the full
 * receive screen. Signature stable from 1.0.0-alpha onward.
 *
 * Implementation notes:
 *  - Uses ZXing core's pure-Java [QRCodeWriter] (no Android-specific deps),
 *    then we hand-roll the BitMatrix → ARGB_8888 conversion to keep the
 *    APK footprint small. Pulling in `zxing-android-core` would bring
 *    `android.hardware.camera` along for the ride.
 *  - Encoded at the bitmap's pixel size, so a 256.dp canvas on an xxhdpi
 *    device gets a ~768px QR — sharp at typical scan distances without
 *    being wasteful.
 *  - Error correction level M (~15%) — Lace's mainnet wallet uses the same
 *    level. H (~30%) would be more robust but doubles the module count and
 *    looks busier on screen.
 *  - White background, black foreground — most camera apps tune for this
 *    contrast. We do *not* invert in dark mode; some scanners fail when
 *    the dark side is the QR data.
 *
 * @param text Payload to encode. For receive flows this is a wallet address.
 *   The QR width grows with payload length; for the ~95-char Midnight
 *   shielded addresses, [size] should be at least 200.dp to stay scannable.
 */
@Composable
fun QrCode(
    text: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Encode once per text — generating the BitMatrix is a few ms, but
    // recomposing on every scroll tick is wasteful and the QR itself
    // is purely a function of `text`.
    val bitmap = remember(text) { encodeQr(text, QR_PIXEL_SIZE) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code for $text",
        modifier = modifier.size(size),
    )
}

/**
 * Hand-rolled BitMatrix → Bitmap conversion. ZXing's official Android helper
 * lives in `:android-core` which is overkill — this matches what it does
 * internally, minus the camera-related transitive deps.
 */
private fun encodeQr(text: String, pixelSize: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        // Quiet zone = white border around the QR. 1 module is the spec
        // minimum; 2 gives most scanners a comfortable margin.
        EncodeHintType.MARGIN to 2,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, pixelSize, pixelSize, hints)
    val bitmap = Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.ARGB_8888)
    for (y in 0 until pixelSize) {
        for (x in 0 until pixelSize) {
            bitmap[x, y] = if (matrix[x, y]) BLACK_ARGB else WHITE_ARGB
        }
    }
    return bitmap
}

/** Pixel resolution of the encoded bitmap. The Composable scales this to [Dp]; oversampling here keeps edges crisp on xxhdpi/xxxhdpi devices. */
private const val QR_PIXEL_SIZE = 768

private const val BLACK_ARGB = 0xFF000000.toInt()
private const val WHITE_ARGB = 0xFFFFFFFF.toInt()
