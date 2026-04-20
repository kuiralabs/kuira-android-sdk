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
                driftX = Random.nextFloat() * 2f - 1f,   // -1..1 horizontal drift
                driftY = -(Random.nextFloat() * 0.5f + 0.3f), // upward drift
                size = Random.nextFloat() * 2f + 1f,      // 1..3 dp radius
                lifetime = Random.nextFloat() * 0.4f + 0.6f, // 0.6..1.0 of cycle
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
        val cx = size.width * 0.5f
        val footY = size.height * 0.75f // particles originate from foot level

        particles.forEach { p ->
            // Each particle's local progress within its lifetime,
            // offset by its random phase so they stagger.
            val t = ((cycle + p.phaseOffset) % 1f) / p.lifetime
            if (t > 1f) return@forEach // particle is in its "dead" phase

            val alpha = maxAlpha * (1f - t) // fade out over lifetime
            val x = cx + p.driftX * size.width * 0.3f * t
            val y = footY + p.driftY * size.height * 0.4f * t

            // Slight wobble for organic feel
            val wobble = sin(t * 6.28f * 2f) * 2f

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = p.size * (1f - t * 0.5f), // shrink slightly as they fade
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
