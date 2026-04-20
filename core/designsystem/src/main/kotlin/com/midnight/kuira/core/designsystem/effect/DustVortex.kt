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
 * controlled by [progress] (0f = sparse, 1f = K monogram formed).
 *
 * Represents Midnight's dust generation process: NIGHT balance
 * passively decays into DUST tokens over ~7 days.
 *
 * At 0%: a few faint particles drifting lazily.
 * At 50%: visible swirl forming, particles pulling inward.
 * At 80-100%: particles converge into the Kuira "K" monogram.
 * At 100%: K fully formed with gentle breathing pulse.
 *
 * The payoff: chaos → vortex → brand. Dust crystallizes into identity.
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

    // Target positions for the K monogram (normalized 0..1).
    // Generated once per composable — 60 points along the K strokes.
    val kTargets = remember(particleCount) {
        buildKTargets(particleCount)
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = size.minDimension / 2f * 0.85f

        // How many particles are visible depends on progress
        val visibleCount = (particleCount * (0.15f + p * 0.85f)).toInt()

        // Convergence factor: 0 = pure vortex, 1 = fully on K shape.
        // Kicks in at 80% progress.
        val convergence = if (p > 0.8f) ((p - 0.8f) / 0.2f).coerceIn(0f, 1f) else 0f
        // Ease-in-out for smooth landing
        val easedConvergence = convergence * convergence * (3f - 2f * convergence)

        // Center glow (fades out as K forms)
        if (p > 0.3f && convergence < 1f) {
            val glowAlpha = (p - 0.3f) / 0.7f * glowPulse * 0.15f * (1f - easedConvergence)
            drawCircle(
                color = color.copy(alpha = glowAlpha),
                radius = maxRadius * 0.25f * p,
                center = Offset(cx, cy),
            )
        }

        particles.take(visibleCount).forEachIndexed { index, particle ->
            // === Vortex position (orbital) ===
            val radiusFactor = particle.orbitRadius *
                (1f - p * (1f - particle.drift)) * breathe
            val radius = radiusFactor * maxRadius
            val angle = particle.angle + rotation * particle.speed
            val vortexX = cx + cos(angle) * radius
            val vortexY = cy + sin(angle) * radius * 0.7f

            // === K target position ===
            val target = kTargets[index % kTargets.size]
            // K is drawn in a box centered on the canvas, sized to ~60% of the area
            val kScale = size.minDimension * 0.55f
            val kOffsetX = cx - kScale * 0.5f
            val kOffsetY = cy - kScale * 0.5f
            val targetX = kOffsetX + target.first * kScale
            val targetY = kOffsetY + target.second * kScale
            // Subtle breathing on the formed K
            val kBreathX = targetX + (targetX - cx) * (breathe - 1f) * 0.5f
            val kBreathY = targetY + (targetY - cy) * (breathe - 1f) * 0.5f

            // === Interpolate between vortex and K ===
            val x = vortexX + (kBreathX - vortexX) * easedConvergence
            val y = vortexY + (kBreathY - vortexY) * easedConvergence

            // Alpha: brighter when forming the K
            val baseAlpha = particle.alphaBase * (0.5f + p * 0.5f)
            val kAlpha = 0.85f + glowPulse * 0.15f // bright, pulsing on K
            val alpha = baseAlpha + (kAlpha - baseAlpha) * easedConvergence

            // Size: uniform when on K
            val vortexSize = particle.size
            val kSize = 2.2f // uniform dot size on K
            val particleSize = vortexSize + (kSize - vortexSize) * easedConvergence

            drawCircle(
                color = color.copy(alpha = alpha.coerceAtMost(0.95f)),
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

/**
 * Generate [count] points along a K monogram (normalized 0..1).
 * Three strokes: vertical stem, upper diagonal, lower diagonal.
 */
private fun buildKTargets(count: Int): List<Pair<Float, Float>> {
    val targets = mutableListOf<Pair<Float, Float>>()
    val stemCount = count / 3
    val upperCount = count / 3
    val lowerCount = count - stemCount - upperCount

    // Vertical stem: x = 0.28, y from 0.1 to 0.9
    for (i in 0 until stemCount) {
        val t = i.toFloat() / (stemCount - 1).coerceAtLeast(1)
        targets.add(0.28f to (0.1f + t * 0.8f))
    }

    // Upper diagonal: from (0.33, 0.48) to (0.72, 0.1)
    for (i in 0 until upperCount) {
        val t = i.toFloat() / (upperCount - 1).coerceAtLeast(1)
        targets.add(
            (0.33f + t * 0.39f) to (0.48f - t * 0.38f)
        )
    }

    // Lower diagonal: from (0.33, 0.52) to (0.72, 0.9)
    for (i in 0 until lowerCount) {
        val t = i.toFloat() / (lowerCount - 1).coerceAtLeast(1)
        targets.add(
            (0.33f + t * 0.39f) to (0.52f + t * 0.38f)
        )
    }

    return targets
}
