package com.midnight.kuira.dapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
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
    fun peekTarget(side: Int): Offset {
        // Slide the chip mostly off-screen, leaving a [peekPx] sliver visible past the edge.
        val x = if (side < 0) -(chipSize.width - peekPx) else (containerWidthPx - peekPx)
        return Offset(x, clampedY())
    }

    // Resolve the position once we know both the chip and overlay sizes (and re-clamp on rotation).
    LaunchedEffect(ready, maxX, maxY, dockedSide) {
        if (!ready) return@LaunchedEffect
        pos.snapTo(if (dockedSide != 0) peekTarget(dockedSide) else freeTarget())
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
            // Free → the chip is draggable; tap falls through to the chip (which expands itself).
            Box(
                modifier = Modifier.pointerInput(ready, maxX, maxY) {
                    if (!ready) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            scope.launch {
                                pos.snapTo(
                                    Offset(
                                        (pos.value.x + delta.x).coerceIn(0f, maxX),
                                        (pos.value.y + delta.y).coerceIn(0f, maxY),
                                    ),
                                )
                            }
                        },
                        onDragEnd = {
                            val centerX = pos.value.x + chipSize.width / 2f
                            val side = when {
                                centerX < containerWidthPx * FLOAT_DOCK_ZONE -> -1
                                centerX > containerWidthPx * (1f - FLOAT_DOCK_ZONE) -> 1
                                else -> 0
                            }
                            savedY = pos.value.y
                            if (side == 0) {
                                savedX = pos.value.x
                                scope.launch { pos.animateTo(freeTarget()) }
                            } else {
                                dockedSide = side // LaunchedEffect animates to the peek position
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

/** The thin peek handle shown when a chip is docked to an edge — tap to restore. */
@Composable
private fun FloatingPeekTab(side: Int, onClick: () -> Unit) {
    val innerEdge = if (side < 0) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier
            .width(FLOAT_PEEK_WIDTH)
            .height(FLOAT_PEEK_HEIGHT)
            .clip(RoundedCornerShape(FLOAT_PEEK_RADIUS))
            .background(MidnightColors.Light.copy(alpha = FLOAT_PEEK_FILL_ALPHA))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .semantics {
                contentDescription = "Show Kuira chip"
                role = Role.Button
            },
        contentAlignment = innerEdge,
    ) {
        // A small chevron pointing back on-screen ("›" when docked left, "‹" when docked right).
        Text(
            text = if (side < 0) "›" else "‹",
            color = MidnightColors.Light,
            fontSize = FLOAT_PEEK_CHEVRON,
        )
    }
}

private val FLOAT_PEEK_WIDTH = 26.dp
private val FLOAT_PEEK_HEIGHT = 52.dp
private val FLOAT_PEEK_RADIUS = 13.dp
private val FLOAT_EDGE_MARGIN = 12.dp
private val FLOAT_PEEK_CHEVRON = 18.sp
private const val FLOAT_PEEK_FILL_ALPHA = 0.14f
private const val FLOAT_DOCK_ZONE = 0.12f // outer 12% of either side → dock on release
