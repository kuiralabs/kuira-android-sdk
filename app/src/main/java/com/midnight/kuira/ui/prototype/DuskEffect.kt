package com.midnight.kuira.ui.prototype

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.midnight.kuira.ui.theme.MidnightColors
import kotlin.math.sin
import kotlin.random.Random

/**
 * The midnight entrance — light fading into void.
 *
 * Not a colorful gradient. A grayscale fade from dim gray to black.
 * The only visual feature: stars emerging from the darkness.
 */
@Composable
fun DuskTransitionBackground(
    modifier: Modifier = Modifier,
    /** 0f = dim light, 1f = void black */
    progress: Float = 1f,
) {
    val p = progress.coerceIn(0f, 1f)

    // Start: faint gray at top, dark at bottom
    // End: pure black everywhere
    val topColor = lerp(Color(0xFF1A1A1A), MidnightColors.Void, p)
    val bottomColor = MidnightColors.Void

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(topColor, bottomColor))
        )
    )
}

/**
 * Animated entrance — light drains away, stars appear.
 */
@Composable
fun AnimatedDuskBackground(
    modifier: Modifier = Modifier,
    durationMs: Int = 900,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        )
    }

    Box(modifier = modifier) {
        DuskTransitionBackground(
            modifier = Modifier.fillMaxSize(),
            progress = progress.value,
        )

        if (progress.value > 0.5f) {
            val starAlpha = ((progress.value - 0.5f) / 0.5f).coerceIn(0f, 1f)
            StarField(
                modifier = Modifier.fillMaxSize(),
                alpha = starAlpha,
            )
        }
    }
}

/**
 * Faint breathing glow at the top edge — last trace of light.
 */
@Composable
fun HorizonGlow(
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "horizon")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = glowAlpha),
                0.5f to Color.White.copy(alpha = glowAlpha * 0.3f),
                1.0f to Color.Transparent,
            )
        )
    )
}

/**
 * White points of light against the void.
 */
@Composable
fun StarField(
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    starCount: Int = 25,
) {
    val stars = remember {
        val rng = Random(42)
        List(starCount) {
            StarData(
                x = rng.nextFloat(),
                y = rng.nextFloat(),
                size = rng.nextFloat() * 1.2f + 0.3f,
                baseAlpha = rng.nextFloat() * 0.4f + 0.2f,
                twinkleSpeed = rng.nextFloat() * 2500f + 2000f,
            )
        }
    }

    Canvas(modifier = modifier) {
        val time = System.nanoTime() / 1_000_000_000f
        stars.forEach { star ->
            val twinkle = (sin(time * (6.28f / (star.twinkleSpeed / 1000f))).toFloat() + 1f) / 2f
            val starAlpha = star.baseAlpha * alpha * (0.4f + twinkle * 0.6f)

            drawCircle(
                color = Color.White.copy(alpha = starAlpha),
                radius = star.size.dp.toPx(),
                center = Offset(
                    x = star.x * size.width,
                    y = star.y * size.height,
                ),
            )
        }
    }
}

private data class StarData(
    val x: Float,
    val y: Float,
    val size: Float,
    val baseAlpha: Float,
    val twinkleSpeed: Float,
)

private fun lerp(a: Color, b: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f,
    )
}

// ── Previews ──

@Preview(showBackground = true, widthDp = 360, heightDp = 400, backgroundColor = 0xFF000000)
@Composable
private fun VoidPreview() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        StarField(modifier = Modifier.fillMaxSize(), alpha = 1f)
        HorizonGlow(modifier = Modifier.fillMaxWidth().height(60.dp))
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 400, backgroundColor = 0xFF000000)
@Composable
private fun DuskFadingPreview() {
    DuskTransitionBackground(modifier = Modifier.fillMaxSize(), progress = 0.3f)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 400, backgroundColor = 0xFF000000)
@Composable
private fun MidnightSettledPreview() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        StarField(modifier = Modifier.fillMaxSize(), alpha = 0.8f)
    }
}
