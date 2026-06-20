package com.midnight.kuira.dapp.wallet

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import com.midnight.kuira.dapp.FloatingChip
import com.midnight.kuira.dapp.FloatingCorner
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Interactive resize demo (Phase 2) — DEPLOYABLE: "Run preview on device" and you can actually
 * drag it. The corner handle is **armed by a long-press** (so a stray touch can't resize), then
 * dragging it grows/shrinks the chip; the layout switches tiers live ([tierForWidth]) and snaps to
 * the nearest tier on release. This is the real resize mechanism wrapped for hands-on review.
 */

private val MIN_WIDTH = 150.dp   // Pill floor
private val MAX_WIDTH = 300.dp   // a touch past Panel, so Panel has headroom

@Composable
internal fun ResizableChip(
    colors: WalletPanelColors,
    onTap: () -> Unit = {},
    chip: @Composable (tier: ChipTier, width: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val minPx = with(density) { MIN_WIDTH.toPx() }
    val maxPx = with(density) { MAX_WIDTH.toPx() }
    // Default to the small Pill (also the resize floor); the user grows it by long-pressing the grip.
    val widthPx = remember { Animatable(with(density) { ChipTier.Pill.naturalWidth().toPx() }) }
    var armed by remember { mutableStateOf(false) }

    val widthDp = with(density) { widthPx.value.toDp() }
    val tier = tierForWidth(widthDp)

    // Haptics are central to the feel: a firm thunk when resize ARMS (the long-press, below), and a
    // light TICK each time the drag crosses into a new tier — and again when it snaps home. The
    // crossing tick is what makes "it changed size/layout" register in the hand, not just the eye.
    val view = LocalView.current
    var lastTier by remember { mutableStateOf(tier) }
    LaunchedEffect(tier) {
        if (tier != lastTier) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastTier = tier
        }
    }

    Box(contentAlignment = Alignment.TopStart) {
        // The chip. Armed → a subtle lift (graphicsLayer scale, no layout shift) + accent ring.
        Box(
            Modifier
                .graphicsLayer { val s = if (armed) 1.03f else 1f; scaleX = s; scaleY = s }
                // Tap (not drag) opens the full sheet; a drag falls through to the floating
                // container's move gesture, and the corner long-press to resize (below).
                .pointerInput(Unit) { detectTapGestures { onTap() } }
                .then(
                    if (armed) Modifier.border(1.5.dp, colors.onSheet.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    else Modifier,
                ),
        ) {
            chip(tier, widthDp)
        }

        // Resize handle at the inner (bottom-end) corner — long-press to arm, then drag.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(32.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            armed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            scope.launch { widthPx.snapTo((widthPx.value + drag.x).coerceIn(minPx, maxPx)) }
                        },
                        onDragEnd = {
                            armed = false
                            scope.launch { widthPx.animateTo(nearestTierWidthPx(widthPx.value, density)) }
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { widthPx.animateTo(nearestTierWidthPx(widthPx.value, density)) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            ResizeGrip(if (armed) colors.onSheet else colors.onSheetDim, armed)
        }
    }
}

/** Nearest of the snap tiers (Pill / Card / Panel) to the live width, in px. */
private fun nearestTierWidthPx(px: Float, density: androidx.compose.ui.unit.Density): Float {
    val targets = listOf(ChipTier.Pill, ChipTier.Card, ChipTier.Panel)
        .map { with(density) { it.naturalWidth().toPx() } }
    return targets.minByOrNull { abs(it - px) } ?: px
}

/** Classic resize grip — three parallel corner ticks; bigger + brighter while armed. */
@Composable
private fun ResizeGrip(color: Color, armed: Boolean) {
    Canvas(Modifier.size(if (armed) 16.dp else 13.dp)) {
        val s = size.minDimension
        val sw = s * 0.10f
        for (i in 0..2) {
            val o = s * (0.30f + i * 0.24f)
            drawLine(color, Offset(s, s - o), Offset(s - o, s), sw, cap = StrokeCap.Round)
        }
    }
}

// ── Deployable interactive previews (Run on device) ──────────────────────────

@Composable
private fun DemoSurface(label: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MidnightColors.Void), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
            Spacer(Modifier.height(28.dp))
            Text(
                label,
                color = MidnightColors.Light.copy(alpha = 0.45f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Preview(name = "▶ Wallet · RESIZE (run on device)", showBackground = true)
@Composable
private fun PreviewWalletResize() = DemoSurface("Long-press the corner grip, then drag to resize.\nPill → Card → Panel · releases snap to the nearest tier.") {
    ResizableChip(WalletPanelColors.Default) { tier, w ->
        WalletChip(tier, WalletChipUi.Sample, WalletPanelColors.Default, width = w)
    }
}

@Preview(name = "▶ Sigil · RESIZE (run on device)", showBackground = true)
@Composable
private fun PreviewSigilResize() = DemoSurface("Long-press the corner grip, then drag to resize.\nPill → Card → Panel · releases snap to the nearest tier.") {
    ResizableChip(WalletPanelColors.Default) { tier, w ->
        SigilChip(tier, SigilChipUi.Sample, WalletPanelColors.Default, width = w)
    }
}

/**
 * The full Phase 1 + Phase 2 experience in one place: a floating chip you can drag, pull past an
 * edge to dock as a peek tab (tap to return), AND long-press the grip to resize across tiers.
 */
@Preview(name = "▶ FLOATING + RESIZE (run on device)", showBackground = true)
@Composable
private fun PreviewFloatingPlayground() {
    Box(Modifier.fillMaxSize().background(MidnightColors.Void)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            FloatingChip(
                persistKey = "demo_playground",
                containerWidthPx = constraints.maxWidth,
                containerHeightPx = constraints.maxHeight,
                initialCorner = FloatingCorner.TopStart,
            ) {
                ResizableChip(WalletPanelColors.Default) { tier, w ->
                    WalletChip(tier, WalletChipUi.Sample, WalletPanelColors.Default, width = w)
                }
            }
        }
        Text(
            "Drag to move · pull past an edge to dock (tap the tab to return) · long-press the grip to resize",
            color = MidnightColors.Light.copy(alpha = 0.4f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }
}
