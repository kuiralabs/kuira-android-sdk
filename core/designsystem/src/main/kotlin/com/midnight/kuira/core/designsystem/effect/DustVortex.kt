package com.midnight.kuira.core.designsystem.effect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Canyon dust vortex — particles orbit in a slow spiral, density
 * controlled by [progress] (0f = sparse, 1f = dense serene cloud).
 *
 * Represents Midnight's dust generation process: NIGHT balance
 * passively generates DUST tokens over time up to a cap.
 *
 * At 0%: a few faint particles drifting lazily.
 * At 50%: visible swirl forming, particles pulling inward.
 * At 100%: dense, stable, softly glowing cloud — tank is full.
 *          No shape-forming. Just calm equilibrium.
 *
 * Optionally shows a lifecycle graph below via [DustLifecycleGraph].
 */
@Composable
fun DustVortex(
    progress: Float, // 0f..1f — generation progress
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    particleCount: Int = 60,
) {
    val p = progress.coerceIn(0f, 1f)

    val particles = remember {
        List(particleCount) {
            VortexParticle(
                orbitRadius = Random.nextFloat(),
                angle = Random.nextFloat() * 2f * PI.toFloat(),
                speed = Random.nextFloat() * 0.5f + 0.3f,
                size = Random.nextFloat() * 2.5f + 0.8f,
                drift = Random.nextFloat() * 0.3f + 0.85f,
                alphaBase = Random.nextFloat() * 0.4f + 0.3f,
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "vortex")

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    val breathe by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    val glowPulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = size.minDimension / 2f * 0.85f

        val visibleCount = (particleCount * (0.15f + p * 0.85f)).toInt()

        // Center glow — grows with progress, pulses gently
        if (p > 0.2f) {
            val glowIntensity = (p - 0.2f) / 0.8f
            // Outer soft glow
            drawCircle(
                color = color.copy(alpha = glowIntensity * glowPulse * 0.1f),
                radius = maxRadius * 0.35f * (0.8f + glowIntensity * 0.2f),
                center = Offset(cx, cy),
            )
            // Inner bright core — visible at high progress
            if (p > 0.6f) {
                val coreIntensity = (p - 0.6f) / 0.4f
                drawCircle(
                    color = color.copy(alpha = coreIntensity * glowPulse * 0.2f),
                    radius = maxRadius * 0.12f * breathe,
                    center = Offset(cx, cy),
                )
            }
        }

        particles.take(visibleCount).forEach { particle ->
            // Orbit tightens as progress increases — particles pull inward
            val tightening = 1f - p * 0.6f // at 100%, orbits are 40% of original
            val radiusFactor = particle.orbitRadius * tightening *
                particle.drift * breathe
            val radius = radiusFactor * maxRadius

            // Slower orbits at high progress = calmer, more stable
            val speedFactor = 1f - p * 0.4f // slow down 40% at 100%
            val angle = particle.angle + rotation * particle.speed * speedFactor

            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius * 0.7f // elliptical

            val distFromCenter = sqrt(
                (x - cx) * (x - cx) + (y - cy) * (y - cy)
            ) / maxRadius

            // Brighter near center at high progress
            val proximityBoost = if (p > 0.4f) {
                (1f - distFromCenter) * (p - 0.4f) * 0.4f
            } else 0f

            val alpha = (particle.alphaBase + proximityBoost)
                .coerceAtMost(0.9f) * (0.5f + p * 0.5f)

            // Particles grow slightly as they pull inward
            val particleSize = particle.size *
                (1f + (1f - distFromCenter) * p * 0.6f)

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = particleSize,
                center = Offset(x, y),
            )
        }
    }
}

/**
 * Dust lifecycle graph — minimal line chart showing the generation
 * ramp, cap plateau, and current position. Matches the official
 * Midnight dust spec curve (linear growth → cap → optional decay).
 *
 * @param progress current generation progress (0..1)
 * @param isDecaying whether the backing NIGHT has been spent (decay phase)
 * @param decayProgress how far into decay (0..1, only used if isDecaying)
 */
@Composable
fun DustLifecycleGraph(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    isDecaying: Boolean = false,
    decayProgress: Float = 0f,
) {
    val p = progress.coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val pad = 8f
        val gw = w - pad * 2
        val gh = h - pad * 2
        val baseY = pad + gh   // bottom
        val topY = pad          // top (= cap)

        // Key X positions for the full lifecycle curve
        val rampEndX = pad + gw * 0.35f       // generation ramp ends
        val plateauEndX = pad + gw * 0.55f    // cap plateau ends
        val decayEndX = pad + gw * 0.90f      // decay ramp ends

        // ── Full lifecycle reference curve (always visible, faint) ──
        val refPath = Path().apply {
            moveTo(pad, baseY)                   // start at zero
            lineTo(rampEndX, topY)               // ramp up to cap
            lineTo(plateauEndX, topY)            // plateau at cap
            lineTo(decayEndX, baseY)             // decay back to zero
        }
        drawPath(
            path = refPath,
            color = color.copy(alpha = 0.12f),
            style = Stroke(width = 1.5f, cap = StrokeCap.Round),
        )

        // Cap label (faint, right side)
        drawLine(
            color = color.copy(alpha = 0.08f),
            start = Offset(pad, topY),
            end = Offset(pad + gw, topY),
            strokeWidth = 1f,
        )

        // Axis
        drawLine(
            color = color.copy(alpha = 0.1f),
            start = Offset(pad, baseY),
            end = Offset(pad + gw, baseY),
            strokeWidth = 1f,
        )

        // ── Active curve (bright, shows current position) ──
        val activePath = Path()
        activePath.moveTo(pad, baseY)

        val dotX: Float
        val dotY: Float

        if (!isDecaying) {
            // Generation phase: ramp from 0 to current progress
            val currentX = pad + gw * 0.35f * p
            val currentY = baseY - gh * p
            activePath.lineTo(currentX, currentY)
            dotX = currentX
            dotY = currentY

            // If at cap, extend the plateau
            if (p >= 1f) {
                val plateauX = pad + gw * 0.45f // show some plateau
                activePath.lineTo(plateauX, topY)
                dotX // stays at plateau edge visually
            }
        } else {
            // Full ramp + plateau + partial decay
            activePath.lineTo(rampEndX, topY)          // full ramp
            activePath.lineTo(plateauEndX, topY)        // full plateau
            val dp = decayProgress.coerceIn(0f, 1f)
            val decayX = plateauEndX + (decayEndX - plateauEndX) * dp
            val decayY = topY + gh * dp
            activePath.lineTo(decayX, decayY)
            dotX = decayX
            dotY = decayY
        }

        drawPath(
            path = activePath,
            color = color.copy(alpha = 0.7f),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )

        // ── Current position dot ──
        // Glow
        drawCircle(
            color = color.copy(alpha = 0.25f),
            radius = 10f,
            center = Offset(dotX, dotY),
        )
        // Dot
        drawCircle(
            color = color.copy(alpha = 0.95f),
            radius = 4.5f,
            center = Offset(dotX, dotY),
        )
    }
}

private data class VortexParticle(
    val orbitRadius: Float,
    val angle: Float,
    val speed: Float,
    val size: Float,
    val drift: Float,
    val alphaBase: Float,
)
