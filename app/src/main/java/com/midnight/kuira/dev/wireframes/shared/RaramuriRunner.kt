package com.midnight.kuira.dev.wireframes.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimalist animated Rarámuri runner — the "Running People" of the
 * Copper Canyons. Line-art figure in sandals, cycling through a
 * running gait. White lines on transparent background.
 *
 * The Tarahumara (Rarámuri) are legendary ultra-distance runners.
 * "Kuira" derives from their language. This animation connects the
 * brand origin to the "creating wallet" progress moment.
 *
 * Design: thin strokes only, no fills. Head = small circle, body =
 * single line, arms + legs = animated lines, sandals = small
 * horizontal strokes at the feet.
 */
@Composable
fun RaramuriRunner(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    strokeWidth: Float = 3f,
) {
    val transition = rememberInfiniteTransition(label = "runner")

    // Gait cycle: 0 → 1 → 0 (one full stride = two half-strides)
    val gait by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gait",
    )

    // Subtle vertical bounce — runner lifts slightly mid-stride
    val bounce by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bounce",
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = size.minDimension / 120f // normalize to ~120dp design space

        // Bounce offset (subtle — 2dp max)
        val bounceY = -bounce * 2f * scale

        drawRunner(
            cx = cx,
            cy = cy + bounceY,
            scale = scale,
            gait = gait,
            color = color,
            strokeWidth = strokeWidth,
        )
    }
}

private fun DrawScope.drawRunner(
    cx: Float,
    cy: Float,
    scale: Float,
    gait: Float, // 0..1 = half-stride interpolation
    color: Color,
    strokeWidth: Float,
) {
    // Body proportions (relative to scale)
    val headRadius = 6f * scale
    val neckY = cy - 28f * scale  // neck (below head)
    val headY = neckY - headRadius - 4f * scale
    val hipY = cy + 5f * scale    // hip joint
    val kneeLen = 18f * scale     // upper leg length
    val shinLen = 18f * scale     // lower leg length
    val armLen = 14f * scale      // upper arm
    val forearmLen = 12f * scale  // forearm
    val shoulderY = neckY + 4f * scale

    // Forward lean — runner leans slightly forward
    val leanX = 4f * scale

    // Head
    drawCircle(
        color = color,
        radius = headRadius,
        center = Offset(cx + leanX, headY),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
    )

    // Body (neck to hip) — slight forward lean
    drawLine(
        color = color,
        start = Offset(cx + leanX, neckY),
        end = Offset(cx, hipY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )

    // === LEGS ===
    // Gait drives leg angle: 0 = pose A (right forward), 1 = pose B (left forward)
    val legSwing = (gait - 0.5f) * 2f // -1..1

    // Front leg
    val frontLegAngle = PI.toFloat() / 2f + legSwing * 0.6f // swing forward
    val frontKneeX = cx + cos(frontLegAngle) * kneeLen
    val frontKneeY = hipY + sin(frontLegAngle) * kneeLen
    // Shin goes more vertical
    val frontFootX = frontKneeX + cos(PI.toFloat() / 2f + 0.2f) * shinLen
    val frontFootY = frontKneeY + sin(PI.toFloat() / 2f + 0.2f) * shinLen

    drawLine(color, Offset(cx, hipY), Offset(frontKneeX, frontKneeY), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(frontKneeX, frontKneeY), Offset(frontFootX, frontFootY), strokeWidth, cap = StrokeCap.Round)
    // Sandal (small horizontal line at foot)
    drawLine(color, Offset(frontFootX - 4f * scale, frontFootY), Offset(frontFootX + 4f * scale, frontFootY), strokeWidth * 0.8f, cap = StrokeCap.Round)

    // Back leg (opposite phase)
    val backLegAngle = PI.toFloat() / 2f - legSwing * 0.6f
    val backKneeX = cx + cos(backLegAngle) * kneeLen
    val backKneeY = hipY + sin(backLegAngle) * kneeLen
    val backShinAngle = PI.toFloat() / 2f - legSwing * 0.3f // back leg bends more
    val backFootX = backKneeX + cos(backShinAngle) * shinLen
    val backFootY = backKneeY + sin(backShinAngle) * shinLen

    drawLine(color, Offset(cx, hipY), Offset(backKneeX, backKneeY), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(backKneeX, backKneeY), Offset(backFootX, backFootY), strokeWidth, cap = StrokeCap.Round)
    // Sandal
    drawLine(color, Offset(backFootX - 4f * scale, backFootY), Offset(backFootX + 4f * scale, backFootY), strokeWidth * 0.8f, cap = StrokeCap.Round)

    // === ARMS === (opposite to legs for natural running form)
    val armSwing = -legSwing // arms swing opposite to legs

    // Front arm
    val frontArmAngle = PI.toFloat() / 2f + armSwing * 0.5f + 0.3f // slightly forward
    val frontElbowX = cx + leanX / 2f + cos(frontArmAngle) * armLen
    val frontElbowY = shoulderY + sin(frontArmAngle) * armLen
    val frontHandX = frontElbowX + cos(frontArmAngle + 0.8f) * forearmLen
    val frontHandY = frontElbowY + sin(frontArmAngle + 0.8f) * forearmLen

    drawLine(color, Offset(cx + leanX / 2f, shoulderY), Offset(frontElbowX, frontElbowY), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(frontElbowX, frontElbowY), Offset(frontHandX, frontHandY), strokeWidth * 0.8f, cap = StrokeCap.Round)

    // Back arm
    val backArmAngle = PI.toFloat() / 2f - armSwing * 0.5f + 0.3f
    val backElbowX = cx + leanX / 2f + cos(backArmAngle) * armLen
    val backElbowY = shoulderY + sin(backArmAngle) * armLen
    val backHandX = backElbowX + cos(backArmAngle + 0.8f) * forearmLen
    val backHandY = backElbowY + sin(backArmAngle + 0.8f) * forearmLen

    drawLine(color, Offset(cx + leanX / 2f, shoulderY), Offset(backElbowX, backElbowY), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(backElbowX, backElbowY), Offset(backHandX, backHandY), strokeWidth * 0.8f, cap = StrokeCap.Round)
}
