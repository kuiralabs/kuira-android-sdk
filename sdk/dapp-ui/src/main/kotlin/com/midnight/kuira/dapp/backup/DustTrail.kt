package com.midnight.kuira.dapp.backup

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
 * Dust particle trail — small dots that kick up and drift away, like a Rarámuri
 * runner kicking up canyon dust. Sits behind [LottieRunner] as the SDK's branded
 * progress indicator.
 *
 * SDK-owned copy of the app-side `core:designsystem` effect (the SDK can't depend
 * on that app module — it isn't published), kept palette-agnostic: pass the pill
 * theme's color via [color].
 *
 * @param color particle color (pass the pill's onSheet/accent color).
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
    val particles = remember {
        List(particleCount) {
            DustParticle(
                phaseOffset = Random.nextFloat(),
                driftX = -(Random.nextFloat() * 0.6f + 0.1f),
                driftY = -(Random.nextFloat() * 0.5f + 0.1f),
                size = Random.nextFloat() * 5f + 3f,
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
        val startX = size.width * 0.35f
        val groundY = size.height * 0.80f

        particles.forEach { p ->
            val t = ((cycle + p.phaseOffset) % 1f) / p.lifetime
            if (t > 1f) return@forEach

            val alpha = maxAlpha * (1f - t * t)
            val x = startX + p.driftX * size.width * 0.6f * t
            val y = groundY + p.driftY * size.height * 0.6f * t
            val wobble = sin(t * 6.28f * 2f) * 3f

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = p.size * (1f + t * 0.5f),
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
