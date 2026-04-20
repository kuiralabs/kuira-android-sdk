package com.midnight.kuira.core.designsystem.effect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Canyon dust vortex — particles orbit in a slow spiral, density
 * controlled by [progress] (0f = sparse, 1f = dense pulsing cloud).
 *
 * Represents Midnight's dust generation process: NIGHT balance
 * passively decays into DUST tokens over ~7 days. The vortex
 * densifies as the tank fills, giving the user a visceral sense
 * of "something is being created."
 *
 * At 0%: a few faint particles drifting lazily.
 * At 50%: visible swirl forming, particles pulling inward.
 * At 100%: dense, pulsing cloud — the tank is full.
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
                orbitRadius = Random.nextFloat(),       // 0..1 normalized
                angle = Random.nextFloat() * 2f * PI.toFloat(),
                speed = Random.nextFloat() * 0.5f + 0.3f,  // orbit speed
                size = Random.nextFloat() * 2.5f + 0.8f,   // particle size
                drift = Random.nextFloat() * 0.3f + 0.85f, // inward pull factor
                alphaBase = Random.nextFloat() * 0.4f + 0.3f,
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "vortex")

    // Main rotation — the whole vortex slowly turns
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    // Breathing pulse — the vortex gently expands and contracts
    val breathe by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    // Inner glow pulse — center brightens at high progress
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

        // How many particles are visible depends on progress
        val visibleCount = (particleCount * (0.15f + p * 0.85f)).toInt()

        // Center glow at high progress
        if (p > 0.3f) {
            val glowAlpha = (p - 0.3f) / 0.7f * glowPulse * 0.15f
            drawCircle(
                color = color.copy(alpha = glowAlpha),
                radius = maxRadius * 0.25f * p,
                center = Offset(cx, cy),
            )
        }

        particles.take(visibleCount).forEach { particle ->
            // Orbit radius shrinks as progress increases (particles pull inward)
            val radiusFactor = particle.orbitRadius *
                (1f - p * (1f - particle.drift)) * breathe

            val radius = radiusFactor * maxRadius

            // Angle = base angle + rotation + individual speed offset
            val angle = particle.angle + rotation * particle.speed

            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius * 0.7f // slightly elliptical

            // Particles closer to center are brighter at high progress
            val distanceFromCenter = sqrt(
                ((x - cx) * (x - cx) + (y - cy) * (y - cy))
            ) / maxRadius
            val proximityBoost = if (p > 0.5f) {
                (1f - distanceFromCenter) * (p - 0.5f) * 0.6f
            } else 0f

            val alpha = (particle.alphaBase + proximityBoost)
                .coerceAtMost(0.9f) * (0.5f + p * 0.5f)

            // Size grows slightly as particles pull inward
            val particleSize = particle.size * (1f + (1f - distanceFromCenter) * p * 0.8f)

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = particleSize,
                center = Offset(x, y),
            )
        }
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
