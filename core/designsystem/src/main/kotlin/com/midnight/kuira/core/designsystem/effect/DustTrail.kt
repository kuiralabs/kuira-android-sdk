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
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dust particle trail — small dots that kick up and drift away,
 * like a Rarámuri runner kicking up canyon dust. Designed to overlay
 * or sit behind a [LottieRunner].
 *
 * Particles spawn near the bottom-center (feet area), drift outward
 * + upward, and fade to zero. Continuous loop — the trail never stops
 * as long as the runner is running.
 *
 * @param color particle color — pass `palette.Light` for palette-aware
 *   monochrome particles (white in dark mode, black in light mode).
 * @param particleCount number of concurrent particles.
 * @param maxAlpha peak opacity of each particle (keep low — dust is subtle).
 */
@Composable
fun DustTrail(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    particleCount: Int = 12,
    maxAlpha: Float = 0.3f,
) {
    // Each particle has a random phase offset so they don't all
    // move in lockstep. The seed is stable across recompositions.
    val particles = remember {
        List(particleCount) {
            DustParticle(
                phaseOffset = Random.nextFloat(),
                driftX = -(Random.nextFloat() * 0.6f + 0.1f), // drift LEFT (behind runner)
                driftY = -(Random.nextFloat() * 0.5f + 0.1f), // drift UP from ground
                size = Random.nextFloat() * 5f + 3f,           // 3..8 dp — cloud-like puffs
                lifetime = Random.nextFloat() * 0.3f + 0.7f,
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "dust")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dustCycle",
    )

    Canvas(modifier = modifier) {
        // Ground line = bottom of the composable (runner's floor).
        // Particles spawn at ground level and puff upward + behind. The trail band's RIGHT edge
        // sits at the runner's centre/feet (see RunnerDustProgress), so spawn near that right
        // edge — the plume kicks up right at the feet, then drifts left (driftX < 0) as it ages,
        // rather than originating detached behind the runner.
        val startX = size.width * 0.78f
        val groundY = size.height * 0.80f // near the very bottom — the floor

        particles.forEach { p ->
            val t = ((cycle + p.phaseOffset) % 1f) / p.lifetime
            if (t > 1f) return@forEach

            val alpha = maxAlpha * (1f - t * t) // quadratic fade
            // Dust kicks up from ground and rises
            val x = startX + p.driftX * size.width * 0.6f * t
            val y = groundY + p.driftY * size.height * 0.6f * t // rises from floor

            val wobble = sin(t * 6.28f * 2f) * 3f

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = p.size * (1f + t * 0.5f), // GROWS as it disperses
                center = Offset(x + wobble, y),
            )
        }
    }
}

private data class DustParticle(
    val phaseOffset: Float,
    val driftX: Float,
    val driftY: Float,
    val size: Float,
    val lifetime: Float,
)
