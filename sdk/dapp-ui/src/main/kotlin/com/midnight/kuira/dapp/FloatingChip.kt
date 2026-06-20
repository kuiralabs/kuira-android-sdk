package com.midnight.kuira.dapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where a floating chip first appears before the user has moved it. */
internal enum class FloatingCorner { TopStart, TopEnd }

/**
 * Wraps [content] (a Kuira chip) in a draggable floater the user can move anywhere over the host's
 * content and **dock** to the left/right edge as a peek tab (YouTube-PiP style) — tap the tab to
 * bring it back. Must be placed inside a full-screen, pass-through overlay so empty space lets
 * touches reach the host underneath.
 *
 * IN-APP only — floats over the host app's own content, never other apps. Position + dock side
 * survive recomposition / process death via [rememberSaveable] (namespaced by [persistKey]).
 *
 * @param containerWidthPx / containerHeightPx the overlay's pixel size (from the parent
 *   `BoxWithConstraints`), used to clamp dragging and compute the docked peek position.
 */
@Composable
internal fun FloatingChip(
    persistKey: String,
    containerWidthPx: Int,
    containerHeightPx: Int,
    initialCorner: FloatingCorner,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val peekPx = with(density) { FLOAT_PEEK_WIDTH.toPx() }
    val marginPx = with(density) { FLOAT_EDGE_MARGIN.toPx() }
    val scope = rememberCoroutineScope()

    var chipSize by remember { mutableStateOf(IntSize.Zero) }
    // Persisted: last FREE position (px) + docked side (0 free, -1 left, +1 right). NaN = unplaced.
    var savedX by rememberSaveable(key = "$persistKey-x") { mutableFloatStateOf(Float.NaN) }
    var savedY by rememberSaveable(key = "$persistKey-y") { mutableFloatStateOf(Float.NaN) }
    var dockedSide by rememberSaveable(key = "$persistKey-dock") { mutableIntStateOf(0) }

    val pos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val ready = chipSize != IntSize.Zero && containerWidthPx > 0 && containerHeightPx > 0
    val maxX = (containerWidthPx - chipSize.width).coerceAtLeast(0).toFloat()
    val maxY = (containerHeightPx - chipSize.height).coerceAtLeast(0).toFloat()

    fun clampedY(): Float = (if (savedY.isNaN()) marginPx else savedY).coerceIn(0f, maxY)
    fun freeTarget(): Offset {
        val x = if (savedX.isNaN()) {
            if (initialCorner == FloatingCorner.TopEnd) maxX - marginPx else marginPx
        } else savedX.coerceIn(0f, maxX)
        return Offset(x, clampedY())
    }
    // Docked → a thin peek tab sitting flush at the edge (fixed size, independent of the chip).
    fun peekTarget(side: Int): Offset =
        Offset(if (side < 0) 0f else (containerWidthPx - peekPx), clampedY())

    // Resolve position once sizes are known: SNAP on first placement / rotation, ANIMATE on dock &
    // undock so the tuck-to-edge and the spring-back read smoothly.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(ready, maxX, maxY, dockedSide) {
        if (!ready) return@LaunchedEffect
        val target = if (dockedSide != 0) peekTarget(dockedSide) else freeTarget()
        if (placed) pos.animateTo(target) else { pos.snapTo(target); placed = true }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
            .onSizeChanged { chipSize = it },
    ) {
        if (dockedSide != 0) {
            // Docked → only the peek tab is interactive; tap brings the chip back on-screen.
            FloatingPeekTab(
                side = dockedSide,
                onClick = { dockedSide = 0 }, // LaunchedEffect animates it back to freeTarget()
            )
        } else {
            // Free → drag to move; PULL a chip past the left/right edge to dock it as a peek tab
            // (YouTube-PiP style). The drag may overshoot the edge so the tuck is visible; a release
            // that didn't overshoot enough springs the chip back on-screen.
            val overshoot = chipSize.width * FLOAT_DOCK_OVERSHOOT
            Box(
                modifier = Modifier.pointerInput(ready, maxX, maxY) {
                    if (!ready) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            scope.launch {
                                pos.snapTo(
                                    Offset(
                                        (pos.value.x + delta.x).coerceIn(-overshoot, maxX + overshoot),
                                        (pos.value.y + delta.y).coerceIn(0f, maxY),
                                    ),
                                )
                            }
                        },
                        onDragEnd = {
                            savedY = pos.value.y
                            val trigger = chipSize.width * FLOAT_DOCK_TRIGGER
                            when {
                                pos.value.x < -trigger -> dockedSide = -1            // pulled off the left
                                pos.value.x > maxX + trigger -> dockedSide = 1       // pulled off the right
                                else -> {                                            // not far enough → snap back
                                    savedX = pos.value.x.coerceIn(0f, maxX)
                                    scope.launch { pos.animateTo(freeTarget()) }
                                }
                            }
                        },
                    )
                },
            ) {
                content()
            }
        }
    }
}

/**
 * The peek handle shown when a chip is docked — a pull-tab attached to the edge (flat on the docked
 * side, rounded on the inner side), solid elevated surface + frosted sheen + hairline so it reads
 * clearly against the void. Tap to restore the chip.
 */
@Composable
private fun FloatingPeekTab(side: Int, onClick: () -> Unit) {
    // Round only the inner corners so it looks anchored to the screen edge, not a floating pill.
    val shape = if (side < 0) {
        RoundedCornerShape(topEnd = FLOAT_PEEK_RADIUS, bottomEnd = FLOAT_PEEK_RADIUS)
    } else {
        RoundedCornerShape(topStart = FLOAT_PEEK_RADIUS, bottomStart = FLOAT_PEEK_RADIUS)
    }
    Box(
        modifier = Modifier
            .width(FLOAT_PEEK_WIDTH)
            .height(FLOAT_PEEK_HEIGHT)
            .clip(shape)
            .background(MidnightColors.VoidElevated)
            .background(FLOAT_PEEK_SHEEN)
            .border(1.dp, MidnightColors.LightFaint, shape)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .semantics {
                contentDescription = "Show Kuira chip"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // Chevron points back on-screen ("›" when docked left, "‹" when docked right).
        Text(
            text = if (side < 0) "›" else "‹",
            color = MidnightColors.LightSoft,
            fontSize = FLOAT_PEEK_CHEVRON,
        )
    }
}

private val FLOAT_PEEK_WIDTH = 28.dp
private val FLOAT_PEEK_HEIGHT = 56.dp
private val FLOAT_PEEK_RADIUS = 14.dp
private val FLOAT_EDGE_MARGIN = 12.dp
private val FLOAT_PEEK_CHEVRON = 18.sp
private val FLOAT_PEEK_SHEEN = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.10f),
    0.5f to Color.Transparent,
    1f to Color.Transparent,
)
private const val FLOAT_DOCK_OVERSHOOT = 0.55f // a drag may pull this fraction of the chip past the edge
private const val FLOAT_DOCK_TRIGGER = 0.30f   // released ≥ this fraction past the edge → dock to a peek tab
