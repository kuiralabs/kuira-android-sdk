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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val KUIRA_LETTERS = listOf('K', 'U', 'I', 'R', 'A')
private val LETTER_THRESHOLDS = listOf(0.35f, 0.22f, 0.10f, 0.28f, 0.40f)

@Composable
fun KuiraMaterialize(
    modifier: Modifier = Modifier,
    durationMs: Int = 2500,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }

    KuiraMaterializeFrame(modifier = modifier, progress = progress.value)
}

@Composable
fun KuiraMaterializeFrame(
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    val p = progress.coerceIn(0f, 1f)

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

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(320.dp, 80.dp)) {
            val letterSpacing = size.width / 5f

            KUIRA_LETTERS.forEachIndexed { index, _ ->
                val threshold = LETTER_THRESHOLDS[index]
                val letterProgress = if (p <= threshold) 0f
                else ((p - threshold) / 0.5f).coerceIn(0f, 1f)

                val cx = letterSpacing * index + letterSpacing / 2f
                val cy = size.height / 2f

                if (letterProgress in 0.01f..0.99f) {
                    drawSparkle(center = Offset(cx, cy), progress = letterProgress)
                }
                if (p >= 1f) {
                    drawAmbientGlow(center = Offset(cx, cy), intensity = breathe)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KUIRA_LETTERS.forEachIndexed { index, letter ->
                val threshold = LETTER_THRESHOLDS[index]
                val letterProgress = if (p <= threshold) 0f
                else ((p - threshold) / 0.5f).coerceIn(0f, 1f)

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

private fun DrawScope.drawSparkle(center: Offset, progress: Float) {
    val expandPhase = (progress / 0.3f).coerceIn(0f, 1f)
    val fadeFactor = if (progress > 0.3f) 1f - ((progress - 0.3f) / 0.7f) else 1f
    val alpha = fadeFactor.coerceIn(0f, 1f)
    if (alpha < 0.01f) return

    drawCircle(Color.White.copy(alpha = alpha * 0.9f), 3.dp.toPx() * expandPhase, center)
    drawCircle(Color.White.copy(alpha = alpha * 0.2f), 20.dp.toPx() * expandPhase, center)

    val rayLength = 18.dp.toPx() * expandPhase
    val rayAlpha = alpha * 0.7f
    val strokeWidth = 1.2.dp.toPx()

    drawLine(Color.White.copy(alpha = rayAlpha), Offset(center.x, center.y - rayLength), Offset(center.x, center.y + rayLength), strokeWidth, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = rayAlpha), Offset(center.x - rayLength, center.y), Offset(center.x + rayLength, center.y), strokeWidth, StrokeCap.Round)

    val diag = 10.dp.toPx() * expandPhase * 0.707f
    val diagAlpha = alpha * 0.4f
    val diagStroke = 0.8.dp.toPx()
    drawLine(Color.White.copy(alpha = diagAlpha), Offset(center.x - diag, center.y - diag), Offset(center.x + diag, center.y + diag), diagStroke, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = diagAlpha), Offset(center.x + diag, center.y - diag), Offset(center.x - diag, center.y + diag), diagStroke, StrokeCap.Round)

    for (i in 0..6) {
        val angle = (i.toFloat() / 6) * 6.28f + 0.5f
        val dist = 12.dp.toPx() * expandPhase + 5.dp.toPx() * expandPhase * (i % 3)
        drawCircle(Color.White.copy(alpha = alpha * 0.5f * (1f - expandPhase * 0.5f)), 1.dp.toPx(), Offset(center.x + cos(angle) * dist, center.y + sin(angle) * dist))
    }
}

private fun DrawScope.drawAmbientGlow(center: Offset, intensity: Float) {
    drawCircle(Color.White.copy(alpha = intensity * 0.08f), 16.dp.toPx(), center)
}

@Composable
fun NoiseField(
    modifier: Modifier = Modifier,
    density: Int = 40,
    alpha: Float = 1f,
) {
    var tick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { delay(50); tick += 1f } }

    val particles = remember {
        val rng = Random(99)
        List(density) {
            Particle(rng.nextFloat(), rng.nextFloat(), rng.nextFloat() * 1.5f + 0.5f, rng.nextFloat() * 0.003f + 0.001f, rng.nextFloat() * 0.2f + 0.05f)
        }
    }

    Canvas(modifier = modifier) {
        particles.forEach { p ->
            val drift = (p.y + tick * p.driftSpeed) % 1f
            val flicker = kotlin.math.sin(tick * p.flickerRate * 6.28f).toFloat()
            if (flicker > 0f) {
                drawCircle(Color.White.copy(alpha = (0.08f + flicker * 0.12f) * alpha), p.size.dp.toPx(), Offset(p.x * size.width, drift * size.height))
            }
        }
    }
}

private data class Particle(val x: Float, val y: Float, val size: Float, val driftSpeed: Float, val flickerRate: Float)

@Composable
fun MidnightEntrance(modifier: Modifier = Modifier) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(Unit) { phase.animateTo(1f, tween(3500, easing = FastOutSlowInEasing)) }
    val p = phase.value

    Box(modifier = modifier.fillMaxSize().background(MidnightColors.Void)) {
        val particleAlpha = when {
            p < 0.1f -> p / 0.1f; p < 0.7f -> 1f; else -> 1f - ((p - 0.7f) / 0.3f)
        }
        NoiseField(modifier = Modifier.fillMaxSize(), alpha = particleAlpha.coerceIn(0f, 1f))

        if (p > 0.55f) StarField(modifier = Modifier.fillMaxSize(), alpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f), starCount = 30)

        if (p > 0.08f) {
            KuiraMaterializeFrame(modifier = Modifier.align(Alignment.Center).padding(bottom = 40.dp), progress = ((p - 0.08f) / 0.6f).coerceIn(0f, 1f))
        }

        if (p > 0.65f) HorizonGlow(modifier = Modifier.fillMaxWidth().height(40.dp))
    }
}
