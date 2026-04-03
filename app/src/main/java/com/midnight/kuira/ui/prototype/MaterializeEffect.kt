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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.ui.theme.MidnightColors
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val KUIRA_LETTERS = listOf('K', 'U', 'I', 'R', 'A')

// Center-out stagger: I first, then U/R, then K/A
private val LETTER_THRESHOLDS = listOf(0.35f, 0.22f, 0.10f, 0.28f, 0.40f)

@Composable
fun KuiraMaterialize(
    modifier: Modifier = Modifier,
    durationMs: Int = 2500,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
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

    // Breathing after settled
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Sparkle layer — Canvas behind the text
        Canvas(modifier = Modifier.size(320.dp, 80.dp)) {
            val letterSpacing = size.width / 5f

            KUIRA_LETTERS.forEachIndexed { index, _ ->
                val threshold = LETTER_THRESHOLDS[index]
                val letterProgress = if (p <= threshold) 0f
                else ((p - threshold) / (0.5f)).coerceIn(0f, 1f)

                val cx = letterSpacing * index + letterSpacing / 2f
                val cy = size.height / 2f

                // Sparkle burst when letter appears (0.0 to 0.5 of letterProgress)
                if (letterProgress in 0.01f..0.99f) {
                    drawSparkle(
                        center = Offset(cx, cy),
                        progress = letterProgress,
                    )
                }

                // Ambient twinkle after settled
                if (p >= 1f) {
                    drawAmbientGlow(
                        center = Offset(cx, cy),
                        intensity = breathe,
                    )
                }
            }
        }

        // Text layer
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KUIRA_LETTERS.forEachIndexed { index, letter ->
                val threshold = LETTER_THRESHOLDS[index]
                val letterProgress = if (p <= threshold) 0f
                else ((p - threshold) / (0.5f)).coerceIn(0f, 1f)

                val alpha = letterProgress
                val glowAlpha = when {
                    p >= 1f -> breathe * 0.4f
                    letterProgress in 0.01f..0.7f -> {
                        val pulse = 1f - kotlin.math.abs(letterProgress / 0.7f - 0.5f) * 2f
                        pulse * 0.9f
                    }
                    letterProgress > 0.7f -> (1f - letterProgress) / 0.3f * 0.3f
                    else -> 0f
                }

                Text(
                    text = letter.toString(),
                    style = TextStyle(
                        fontSize = 48.sp,
                        fontWeight = FontWeight.W300,
                        color = Color.White.copy(alpha = alpha),
                        shadow = Shadow(
                            color = Color.White.copy(alpha = glowAlpha),
                            offset = Offset.Zero,
                            blurRadius = 24f + glowAlpha * 30f,
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * Sparkle burst — a 4-point star cross with radiating rays.
 * Flashes bright then fades, rays extend then retract.
 */
private fun DrawScope.drawSparkle(center: Offset, progress: Float) {
    // Sparkle lifecycle: 0-0.3 expand, 0.3-1.0 fade
    val expandPhase = (progress / 0.3f).coerceIn(0f, 1f)
    val fadeFactor = if (progress > 0.3f) 1f - ((progress - 0.3f) / 0.7f) else 1f
    val alpha = fadeFactor.coerceIn(0f, 1f)

    if (alpha < 0.01f) return

    // Central bright point
    drawCircle(
        color = Color.White.copy(alpha = alpha * 0.9f),
        radius = 3.dp.toPx() * expandPhase,
        center = center,
    )

    // Soft glow circle
    drawCircle(
        color = Color.White.copy(alpha = alpha * 0.2f),
        radius = 20.dp.toPx() * expandPhase,
        center = center,
    )

    // Cross rays (4 directions)
    val rayLength = 18.dp.toPx() * expandPhase
    val rayAlpha = alpha * 0.7f
    val strokeWidth = 1.2.dp.toPx()

    // Vertical
    drawLine(
        color = Color.White.copy(alpha = rayAlpha),
        start = Offset(center.x, center.y - rayLength),
        end = Offset(center.x, center.y + rayLength),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    // Horizontal
    drawLine(
        color = Color.White.copy(alpha = rayAlpha),
        start = Offset(center.x - rayLength, center.y),
        end = Offset(center.x + rayLength, center.y),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )

    // Diagonal rays (shorter, thinner)
    val diagLength = 10.dp.toPx() * expandPhase
    val diagAlpha = alpha * 0.4f
    val diagStroke = 0.8.dp.toPx()
    val diag = diagLength * 0.707f // cos(45°)

    drawLine(
        color = Color.White.copy(alpha = diagAlpha),
        start = Offset(center.x - diag, center.y - diag),
        end = Offset(center.x + diag, center.y + diag),
        strokeWidth = diagStroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color.White.copy(alpha = diagAlpha),
        start = Offset(center.x + diag, center.y - diag),
        end = Offset(center.x - diag, center.y + diag),
        strokeWidth = diagStroke,
        cap = StrokeCap.Round,
    )

    // Tiny scatter particles flying outward
    val particleCount = 6
    for (i in 0..particleCount) {
        val angle = (i.toFloat() / particleCount) * 6.28f + 0.5f
        val dist = 12.dp.toPx() * expandPhase + 5.dp.toPx() * expandPhase * (i % 3)
        val px = center.x + cos(angle) * dist
        val py = center.y + sin(angle) * dist
        val particleAlpha = alpha * 0.5f * (1f - expandPhase * 0.5f)
        drawCircle(
            color = Color.White.copy(alpha = particleAlpha),
            radius = 1.dp.toPx(),
            center = Offset(px, py),
        )
    }
}

/**
 * Ambient glow — soft breathing light around settled letters.
 */
private fun DrawScope.drawAmbientGlow(center: Offset, intensity: Float) {
    drawCircle(
        color = Color.White.copy(alpha = intensity * 0.08f),
        radius = 16.dp.toPx(),
        center = center,
    )
}

/**
 * Ambient particle drift — soft dots floating upward.
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
            delay(50)
            tick += 1f
        }
    }

    val particles = remember {
        val rng = Random(99)
        List(density) {
            Particle(
                x = rng.nextFloat(),
                y = rng.nextFloat(),
                size = rng.nextFloat() * 1.5f + 0.5f,
                driftSpeed = rng.nextFloat() * 0.003f + 0.001f,
                flickerRate = rng.nextFloat() * 0.2f + 0.05f,
            )
        }
    }

    Canvas(modifier = modifier) {
        particles.forEach { p ->
            val drift = (p.y + tick * p.driftSpeed) % 1f
            val flicker = kotlin.math.sin(tick * p.flickerRate * 6.28f).toFloat()
            val visible = flicker > 0f
            if (visible) {
                val dotAlpha = (0.08f + flicker * 0.12f) * alpha
                drawCircle(
                    color = Color.White.copy(alpha = dotAlpha),
                    radius = p.size.dp.toPx(),
                    center = Offset(p.x * size.width, drift * size.height),
                )
            }
        }
    }
}

private data class Particle(
    val x: Float,
    val y: Float,
    val size: Float,
    val driftSpeed: Float,
    val flickerRate: Float,
)

/**
 * Full entrance: void → particles → sparkle burst per letter → stars settle
 */
@Composable
fun MidnightEntrance(
    modifier: Modifier = Modifier,
) {
    val phase = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        phase.animateTo(1f, tween(3500, easing = FastOutSlowInEasing))
    }

    val p = phase.value

    Box(modifier = modifier.fillMaxSize().background(MidnightColors.Void)) {
        // Drifting particles
        val particleAlpha = when {
            p < 0.1f -> p / 0.1f
            p < 0.7f -> 1f
            else -> 1f - ((p - 0.7f) / 0.3f)
        }
        NoiseField(
            modifier = Modifier.fillMaxSize(),
            alpha = particleAlpha.coerceIn(0f, 1f),
        )

        // Stars emerge late
        if (p > 0.55f) {
            StarField(
                modifier = Modifier.fillMaxSize(),
                alpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f),
                starCount = 30,
            )
        }

        // KUIRA with sparkle bursts
        if (p > 0.08f) {
            val materializeProgress = ((p - 0.08f) / 0.6f).coerceIn(0f, 1f)
            KuiraMaterializeFrame(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 40.dp),
                progress = materializeProgress,
            )
        }

        // Horizon glow
        if (p > 0.65f) {
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
private fun SparkleAppearing() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        NoiseField(modifier = Modifier.fillMaxSize(), alpha = 0.8f)
        KuiraMaterializeFrame(
            modifier = Modifier.align(Alignment.Center),
            progress = 0.35f,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF000000)
@Composable
private fun SparklePeak() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        NoiseField(modifier = Modifier.fillMaxSize(), alpha = 0.5f)
        KuiraMaterializeFrame(
            modifier = Modifier.align(Alignment.Center),
            progress = 0.55f,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF000000)
@Composable
private fun Settled() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        StarField(modifier = Modifier.fillMaxSize(), alpha = 0.7f)
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
