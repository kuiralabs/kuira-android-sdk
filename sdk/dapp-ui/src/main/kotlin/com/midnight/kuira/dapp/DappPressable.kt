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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
        // Pull the label onto the clickable node, and say this is a button.
        //
        // A Material `Button` merges its descendants, so the text inside it becomes the
        // accessible name of the thing you can press. A hand-rolled `Box` + `clickable` does not:
        // the click lands on one node and the text sits on a sibling. Measured on a device — every
        // primary action in the BBoard sample (CONNECT, CREATE A NEW BOARD, POST, TAKE DOWN …)
        // came back as a clickable node with EMPTY text, with its label on a separate node that
        // nothing can press. Every chip and pill in the SDK's own panels is built this way too.
        //
        // The cost is twofold. TalkBack announces an unlabelled button, which is the whole
        // interaction for a screen-reader user. And any tooling that drives the UI by label — the
        // Android CLI's `input tap text=…`, UI Automator, Espresso's `hasText` — cannot find the
        // control at all, forcing tests onto pixel coordinates that break on the next layout
        // change.
        //
        // `mergeDescendants` merges the child Text up; `Role.Button` tells assistive tech what it
        // is rather than leaving it an anonymous tappable region. Purely additive: no visual
        // change, no behaviour change, no new state.
        // Order matters: the merge has to come AFTER clickable, not before. Applied first, the
        // merging node ends up OUTSIDE the one clickable creates, and the accessibility tree
        // exposes clickable's own unlabelled node — verified on a device, where the label stayed
        // a separate child even with mergeDescendants set. Applied after, the merge lands on the
        // node that carries the click action, which is the node assistive tech and UI tooling
        // actually target.
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
        .semantics(mergeDescendants = true) {}
}
