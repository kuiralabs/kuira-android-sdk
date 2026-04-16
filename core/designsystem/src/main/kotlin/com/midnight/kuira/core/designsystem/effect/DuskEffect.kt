package com.midnight.kuira.core.designsystem.effect

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun DuskTransitionBackground(
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    val p = progress.coerceIn(0f, 1f)
    val topColor = lerp(Color(0xFF1A1A1A), MidnightColors.Void, p)

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(topColor, MidnightColors.Void))
        )
    )
}

@Composable
fun AnimatedDuskBackground(
    modifier: Modifier = Modifier,
    durationMs: Int = 900,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }

    Box(modifier = modifier) {
        DuskTransitionBackground(modifier = Modifier.fillMaxSize(), progress = progress.value)
        if (progress.value > 0.5f) {
            StarField(
                modifier = Modifier.fillMaxSize(),
                alpha = ((progress.value - 0.5f) / 0.5f).coerceIn(0f, 1f),
            )
        }
    }
}

@Composable
fun HorizonGlow(modifier: Modifier = Modifier) {
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

@Composable
fun StarField(
    modifier: Modifier = Modifier,
    // Star color. Alpha on this color is ignored — `alpha` param controls
    // max brightness. Dark mode: Color.White (default). Light mode: Color.Black.
    color: Color = Color.White,
    // Max brightness cap. Dark mode: 1f (default, yields stars up to ~0.8 α).
    // Light mode: ~0.25f keeps stars as subtle texture, not competing with text.
    alpha: Float = 1f,
    starCount: Int = 25,
) {
    var tick by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60) // ~16fps — enough for gentle twinkle
            tick += 0.06f
        }
    }

    val stars = remember {
        val rng = Random(42)
        List(starCount) {
            StarData(
                x = rng.nextFloat(),
                y = rng.nextFloat(),
                size = rng.nextFloat() * 1.8f + 0.5f,
                baseAlpha = rng.nextFloat() * 0.5f + 0.3f,
                twinkleSpeed = rng.nextFloat() * 1.5f + 0.8f,
            )
        }
    }

    Canvas(modifier = modifier) {
        stars.forEach { star ->
            val twinkle = (sin(tick * star.twinkleSpeed).toFloat() + 1f) / 2f
            val starAlpha = star.baseAlpha * alpha * (0.3f + twinkle * 0.7f)
            val pulseSize = star.size + twinkle * 0.4f
            drawCircle(
                color = color.copy(alpha = starAlpha),
                radius = pulseSize.dp.toPx(),
                center = Offset(star.x * size.width, star.y * size.height),
            )
        }
    }
}

private data class StarData(
    val x: Float, val y: Float, val size: Float,
    val baseAlpha: Float, val twinkleSpeed: Float,
)

internal fun lerp(a: Color, b: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f,
    )
}
