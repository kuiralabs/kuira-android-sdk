package com.midnight.kuira.ui.prototype

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.ui.theme.MidnightColors
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Materialize effect — ported from CLI's art.ts
 *
 * Characters start as noise (░▒▓█·) and resolve into the final text.
 * Each character has a threshold — it resolves when progress passes it.
 * The effect is deterministic (same progress = same frame).
 */

private val NOISE_CHARS = charArrayOf('░', '▒', '▓', '█', '·', '∗', '✦', ' ')

private fun noiseChar(row: Int, col: Int, seed: Int): Char {
    val hash = ((row * 131 + col * 997 + seed * 7919) % 65537).toFloat() / 65537f
    return NOISE_CHARS[(hash * NOISE_CHARS.size).toInt().coerceIn(0, NOISE_CHARS.size - 1)]
}

private fun materializeText(text: String, progress: Float, row: Int = 0): String {
    if (progress >= 1f) return text
    if (progress <= 0f) return text.map { ch ->
        if (ch == ' ') ' ' else noiseChar(row, text.indexOf(ch), 0)
    }.joinToString("")

    val seed = (progress * 100).toInt()
    return text.mapIndexed { col, ch ->
        if (ch == ' ') ' '
        else {
            val threshold = ((row * 131 + col * 997) % 100) / 100f
            if (progress >= threshold) ch else noiseChar(row, col, seed)
        }
    }.joinToString("")
}

/**
 * "KUIRA" materializing from noise — the brand entrance.
 *
 * Phases:
 * 1. Pure noise field (0.0 - 0.1)
 * 2. Characters begin resolving from noise (0.1 - 0.7)
 * 3. Fully resolved + glow pulse (0.7 - 1.0)
 */
@Composable
fun KuiraMaterialize(
    modifier: Modifier = Modifier,
    durationMs: Int = 2000,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMs, easing = LinearEasing),
        )
    }

    KuiraMaterializeFrame(
        modifier = modifier,
        progress = progress.value,
    )
}

@Composable
fun KuiraMaterializeFrame(
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    val p = progress.coerceIn(0f, 1f)

    // The resolved text glow (appears after 0.7)
    val glowAlpha = if (p > 0.7f) ((p - 0.7f) / 0.3f).coerceIn(0f, 1f) else 0f

    // Ambient twinkle after fully resolved
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    val effectiveGlow = if (p >= 1f) breathe else glowAlpha

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Noise/resolved text
        val displayText = materializeText("K U I R A", p, row = 0)

        // Glow layer (behind text)
        if (effectiveGlow > 0f) {
            Text(
                text = "K U I R A",
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.W200,
                    letterSpacing = 8.sp,
                    color = Color.White.copy(alpha = effectiveGlow * 0.15f),
                    shadow = Shadow(
                        color = Color.White.copy(alpha = effectiveGlow * 0.4f),
                        offset = Offset.Zero,
                        blurRadius = 40f,
                    ),
                ),
                textAlign = TextAlign.Center,
            )
        }

        // Main text
        Text(
            text = displayText,
            style = TextStyle(
                fontSize = 32.sp,
                fontWeight = FontWeight.W200,
                letterSpacing = 8.sp,
                color = Color.White.copy(alpha = 0.4f + (p * 0.6f)),
                shadow = if (effectiveGlow > 0.2f) Shadow(
                    color = Color.White.copy(alpha = effectiveGlow * 0.3f),
                    offset = Offset.Zero,
                    blurRadius = 20f,
                ) else null,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Noise particle field — ambient static behind the text.
 * Sparse, subtle, always moving.
 */
@Composable
fun NoiseField(
    modifier: Modifier = Modifier,
    density: Int = 40,
    alpha: Float = 1f,
) {
    var tick by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            tick += 1f
        }
    }

    val particles = remember {
        val rng = Random(99)
        List(density) {
            NoiseParticle(
                x = rng.nextFloat(),
                y = rng.nextFloat(),
                char = NOISE_CHARS[rng.nextInt(NOISE_CHARS.size)],
                driftSpeed = rng.nextFloat() * 0.002f + 0.001f,
                flickerRate = rng.nextFloat() * 0.3f + 0.1f,
            )
        }
    }

    Canvas(modifier = modifier) {
        particles.forEach { p ->
            val drift = (p.y + tick * p.driftSpeed) % 1f
            val flicker = kotlin.math.sin(tick * p.flickerRate * 6.28f).toFloat()
            val visible = flicker > 0.2f
            if (visible) {
                val dotAlpha = (0.15f + flicker * 0.15f) * alpha
                drawCircle(
                    color = Color.White.copy(alpha = dotAlpha),
                    radius = 1.5.dp.toPx(),
                    center = Offset(p.x * size.width, drift * size.height),
                )
            }
        }
    }
}

private data class NoiseParticle(
    val x: Float,
    val y: Float,
    val char: Char,
    val driftSpeed: Float,
    val flickerRate: Float,
)

/**
 * Full entrance: void → noise field → KUIRA materializes → stars appear
 */
@Composable
fun MidnightEntrance(
    modifier: Modifier = Modifier,
) {
    val phase = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 0.0-0.3: noise field intensifies
        // 0.3-0.8: KUIRA materializes
        // 0.8-1.0: stars fade in, noise fades out
        phase.animateTo(1f, tween(3000, easing = LinearEasing))
    }

    val p = phase.value

    Box(modifier = modifier.fillMaxSize().background(MidnightColors.Void)) {
        // Noise field — present throughout, fades after materialize
        val noiseAlpha = when {
            p < 0.3f -> p / 0.3f            // fade in
            p < 0.8f -> 1f                   // full
            else -> 1f - ((p - 0.8f) / 0.2f) // fade out
        }
        NoiseField(
            modifier = Modifier.fillMaxSize(),
            alpha = noiseAlpha.coerceIn(0f, 1f),
        )

        // Stars — emerge as noise fades
        if (p > 0.7f) {
            StarField(
                modifier = Modifier.fillMaxSize(),
                alpha = ((p - 0.7f) / 0.3f).coerceIn(0f, 1f),
                starCount = 30,
            )
        }

        // KUIRA materializes
        if (p > 0.15f) {
            val materializeProgress = ((p - 0.15f) / 0.65f).coerceIn(0f, 1f)
            KuiraMaterializeFrame(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 40.dp),
                progress = materializeProgress,
            )
        }

        // Subtle horizon glow at top after settling
        if (p > 0.8f) {
            HorizonGlow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
            )
        }
    }
}

// ── Previews ──

@Preview(showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF000000)
@Composable
private fun MaterializeNoise() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        NoiseField(modifier = Modifier.fillMaxSize())
        KuiraMaterializeFrame(
            modifier = Modifier.align(Alignment.Center),
            progress = 0.0f,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF000000)
@Composable
private fun MaterializeHalf() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        NoiseField(modifier = Modifier.fillMaxSize())
        KuiraMaterializeFrame(
            modifier = Modifier.align(Alignment.Center),
            progress = 0.5f,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF000000)
@Composable
private fun MaterializeComplete() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        StarField(modifier = Modifier.fillMaxSize(), alpha = 0.8f)
        KuiraMaterializeFrame(
            modifier = Modifier.align(Alignment.Center),
            progress = 1f,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF000000)
@Composable
private fun FullEntrancePreview() {
    MidnightEntrance()
}
