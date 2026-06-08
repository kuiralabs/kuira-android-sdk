package com.midnight.kuira.dapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Clickable modifier with Material-3 interaction feedback for the panel's
 * custom-drawn chips and pills (the ones built on `Box`/`Row` rather than a
 * Material `Button`, which already has its own state layers).
 *
 * Adds, on top of a plain `clickable`:
 *  - a **state layer** — a translucent white overlay whose opacity tracks
 *    pressed > focused > hovered, matching M3's interaction-state guidance;
 *  - a subtle **press scale** so a tap reads as a physical press; and
 *  - a **≥48 dp touch target** ([minimumInteractiveComponentSize]) so small
 *    chips stay reachable while their visual stays compact.
 *
 * This mirrors the host apps' `kicksPressable` so the SDK's drop-in panels
 * feel identical to a well-built consumer UI. Ripple is intentionally omitted
 * in favour of the overlay, which reads better on the dark glass pills.
 *
 * @param selected when true, holds a faint resting overlay so an active chip
 *   (e.g. the current network) reads as selected even at rest.
 */
@Composable
fun Modifier.dappPressable(
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    selected: Boolean = false,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val overlayTarget = when {
        !enabled -> 0f
        pressed -> 0.16f
        focused -> 0.11f
        hovered -> 0.08f
        selected -> 0.06f
        else -> 0f
    }
    val overlay by animateFloatAsState(targetValue = overlayTarget, label = "dappStateLayer")
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        label = "dappPressScale",
    )
    return this
        .minimumInteractiveComponentSize()
        .scale(scale)
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (overlay > 0.001f) {
                drawRect(color = Color.White.copy(alpha = overlay))
            }
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
