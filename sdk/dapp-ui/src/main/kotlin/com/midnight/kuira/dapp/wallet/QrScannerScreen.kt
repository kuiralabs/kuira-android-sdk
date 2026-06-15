package com.midnight.kuira.dapp.wallet

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QrScanner"

/**
 * Full-screen QR scanner for the Send recipient field. Live CameraX preview;
 * each frame's luminance plane is decoded with the bundled ZXing
 * [MultiFormatReader] (QR only). The first successful decode fires [onResult]
 * with the raw payload (the bech32m address the Receive screen encodes) and
 * the host closes the scanner.
 *
 * Camera-less devices and denied permission both degrade to a prompt with a
 * "enter manually" escape — paste/typing always remains available, so the
 * scanner is strictly additive.
 */
@Composable
internal fun QrScannerScreen(
    palette: SendPalette,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize().background(palette.bg)) {
        if (granted) {
            CameraDecoder(onDecoded = onResult)
            Viewfinder(palette = palette)
        } else {
            CameraPermissionPrompt(
                palette = palette,
                denied = denied,
                onGrant = { launcher.launch(Manifest.permission.CAMERA) },
                onCancel = onCancel,
            )
        }
        ScannerTopBar(palette = palette, onCancel = onCancel)
    }
}

// ── Camera + decode ──

@Composable
private fun CameraDecoder(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    // LifecycleCameraController wraps the provider future + binding, so we avoid
    // ProcessCameraProvider's Guava ListenableFuture (not on the compile path).
    val controller = remember { LifecycleCameraController(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }

    DisposableEffect(lifecycleOwner) {
        // Owned by this effect, not remember()-cached: a re-bind must get a fresh
        // executor, since onDispose shuts the previous one down (reusing a shut-down
        // executor on the next analyzer set throws RejectedExecutionException).
        val executor = Executors.newSingleThreadExecutor()
        controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        controller.setImageAnalysisAnalyzer(executor, QrAnalyzer { text -> mainExecutor.execute { onDecoded(text) } })
        runCatching { controller.bindToLifecycle(lifecycleOwner) }
            .onFailure { Log.e(TAG, "Camera bind failed", it) }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Decodes the Y (luminance) plane of each [ImageProxy] as a QR code. Uses the
 * plane's `rowStride` as the data width so row padding doesn't skew the image.
 * Fires [onDecoded] exactly once (subsequent frames are dropped) so a held QR
 * doesn't re-trigger after the host starts closing.
 */
private class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private val fired = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (fired.get()) {
            image.close()
            return
        }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            val text = result.text
            if (!text.isNullOrBlank() && fired.compareAndSet(false, true)) {
                onDecoded(text)
            }
        } catch (_: Exception) {
            // No QR in this frame (NotFoundException) or a transient decode
            // error — both are expected per-frame; just try the next one.
        } finally {
            reader.reset()
            image.close()
        }
    }
}

// ── Overlay chrome ──

@Composable
private fun ScannerTopBar(palette: SendPalette, onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .height(SendDimens.TopBarHeight)
            .padding(horizontal = SendDimens.Space16),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(SendDimens.RadiusLg))
                .clickable(onClick = onCancel)
                .padding(SendDimens.GlyphHit),
        ) {
            BackGlyph(color = palette.text)
        }
        Spacer(modifier = Modifier.size(SendDimens.Space16))
        Text(
            text = "Scan address",
            color = palette.text,
            fontSize = SendType.Title,
            fontWeight = FontWeight.W400,
        )
    }
}

/** Centered square viewfinder with corner brackets + a hint. */
@Composable
private fun Viewfinder(palette: SendPalette) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(ViewfinderSize)) {
                val w = size.width
                val sw = w * VIEWFINDER_STROKE_FRACTION
                val arm = w * VIEWFINDER_ARM_FRACTION
                fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
                    drawLine(palette.text, Offset(cx, cy), Offset(cx + dx, cy), sw, StrokeCap.Round)
                    drawLine(palette.text, Offset(cx, cy), Offset(cx, cy + dy), sw, StrokeCap.Round)
                }
                corner(0f, 0f, arm, arm)
                corner(w, 0f, -arm, arm)
                corner(0f, w, arm, -arm)
                corner(w, w, -arm, -arm)
            }
            Spacer(modifier = Modifier.height(SendDimens.Space24))
            Text(
                text = "Point at a Midnight address QR",
                color = palette.textSoft,
                fontSize = SendType.Caption,
                fontWeight = FontWeight.W300,
            )
        }
    }
}

@Composable
private fun CameraPermissionPrompt(
    palette: SendPalette,
    denied: Boolean,
    onGrant: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = SendDimens.Space32),
        ) {
            ScanGlyph(color = palette.textMuted, size = SendDimens.Icon32)
            Spacer(modifier = Modifier.height(SendDimens.Space24))
            Text(
                text = if (denied) {
                    "Camera access is off. Enable it in Settings to scan, or enter the address manually."
                } else {
                    "Allow camera access to scan a recipient's QR code."
                },
                color = palette.textSoft,
                fontSize = SendType.Caption,
                fontWeight = FontWeight.W300,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(SendDimens.Space24))
            if (!denied) {
                SendPrimaryButton(text = "Allow camera", onClick = onGrant, palette = palette)
                Spacer(modifier = Modifier.height(SendDimens.Space12))
            }
            SendSecondaryButton(text = "Enter manually", onClick = onCancel, palette = palette)
        }
    }
}

private val ViewfinderSize = 240.dp
private const val VIEWFINDER_STROKE_FRACTION = 0.02f
private const val VIEWFINDER_ARM_FRACTION = 0.18f
