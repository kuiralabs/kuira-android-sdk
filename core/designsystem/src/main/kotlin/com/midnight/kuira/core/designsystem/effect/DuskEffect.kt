package com.midnight.kuira.core.designsystem.effect

import android.provider.Settings
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
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

/**
 * Ambient depth-parallax star field. Each star has a depth z (0 = far, 1 = near); the whole field
 * drifts slowly with per-star speed ∝ z (nearer stars sweep faster — the parallax cue that reads as
 * gliding through 3-D space), wrapping toroidally for an endless field. Realism without hue (brand
 * monochrome): power-law size/brightness, soft glow halos, a diffraction cross on the brightest few,
 * organic two-octave scintillation, and a rare shooting star. Honors the system "remove animations"
 * setting (freezes to a static sky).
 *
 * @param color star color (its alpha is ignored — [alpha] caps brightness). White (dark) / Black (light).
 * @param alpha max brightness. ~1f dark; ~0.25–0.45f keeps it a subtle texture under content.
 * @param drift parallax speed multiplier; 0f = a still field.
 * @param meteors draw the occasional faint shooting star.
 */
@Composable
fun StarField(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    alpha: Float = 1f,
    starCount: Int = 25,
    drift: Float = 1f,
    meteors: Boolean = true,
) = StarFieldImpl(modifier, color, alpha, starCount, drift, meteors, STYLE_BLOOM) // C · soft bloom (chosen)

@Composable
private fun StarFieldImpl(
    modifier: Modifier,
    color: Color,
    alpha: Float,
    starCount: Int,
    drift: Float,
    meteors: Boolean,
    style: StarStyle,
) {
    val context = LocalContext.current
    val motion = remember {
        if (Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        ) 0f else 1f
    }

    val stars = remember(starCount, style) {
        val rng = Random(42)
        List(starCount) {
            // z² couplings give a power-law sky — many faint pinpricks, a few brilliant anchors.
            val z = rng.nextFloat()
            Star(
                x0 = rng.nextFloat(),
                y0 = rng.nextFloat(),
                z = z,
                radiusDp = style.minR + z * z * (style.maxR - style.minR),
                brightness = STAR_MIN_A + z * z * (STAR_MAX_A - STAR_MIN_A),
                phaseA = rng.nextFloat() * TWO_PI,
                phaseB = rng.nextFloat() * TWO_PI,
                twinkleHz = STAR_TW_MIN + rng.nextFloat() * STAR_TW_RANGE,
                spike = z > STAR_SPIKE_Z,
                spikeJitter = 0.6f + rng.nextFloat() * 0.8f,
                spikeAngle = (rng.nextFloat() - 0.5f) * 0.6f,
            )
        }
    }

    // Delta-time clock at the display refresh rate — smooth, frame-rate-independent motion.
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion) {
        if (motion == 0f) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) t += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val field = t * STAR_DRIFT * drift
        stars.forEach { star ->
            // Parallax: nearer stars sweep faster; wrap toroidally for an endless field.
            val cx = wrap01(star.x0 + field * star.z) * w
            val cy = wrap01(star.y0 + field * star.z * STAR_DRIFT_Y) * h
            // Scintillation: two incommensurate octaves → irregular, atmospheric flicker.
            val tw = 0.5f + 0.5f *
                (0.6f * sin(t * star.twinkleHz + star.phaseA) +
                    0.4f * sin(t * star.twinkleHz * 1.7f + star.phaseB))
            val a = (star.brightness * alpha * (0.35f + 0.65f * tw)).coerceIn(0f, 1f)
            val rPx = (star.radiusDp * (0.9f + 0.2f * tw)).dp.toPx()
            val center = Offset(cx, cy)
            // Soft glow halo on the brighter (nearer) stars.
            if (star.radiusDp > style.glowMinR) {
                val gr = rPx * style.glowScale
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to color.copy(alpha = a * style.glowA),
                        1f to color.copy(alpha = 0f),
                        center = center,
                        radius = gr,
                    ),
                    radius = gr,
                    center = center,
                )
            }
            // Core — a hard pinpoint, or a soft (radial-gradient) point with no hard edge.
            if (style.softCore) {
                val cr = rPx * SOFT_CORE_SCALE
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to color.copy(alpha = a),
                        1f to color.copy(alpha = 0f),
                        center = center,
                        radius = cr,
                    ),
                    radius = cr,
                    center = center,
                )
            } else {
                drawCircle(color.copy(alpha = a), radius = rPx, center = center)
            }
            // Diffraction spikes on the brightest: perfect cross, an imperfect 6-point, or none.
            when (style.cross) {
                CrossMode.None -> Unit
                CrossMode.Perfect -> if (star.spike) {
                    val len = rPx * STAR_SPIKE_LEN
                    val sw = STAR_SPIKE_W.dp.toPx()
                    val sc = color.copy(alpha = a * STAR_SPIKE_A)
                    drawLine(sc, Offset(cx - len, cy), Offset(cx + len, cy), sw, StrokeCap.Round)
                    drawLine(sc, Offset(cx, cy - len), Offset(cx, cy + len), sw, StrokeCap.Round)
                }
                CrossMode.Imperfect -> if (star.spike) {
                    val len = rPx * STAR_SPIKE_LEN * star.spikeJitter
                    val sw = (STAR_SPIKE_W * 0.7f).dp.toPx()
                    val sc = color.copy(alpha = a * STAR_SPIKE_A * 0.85f)
                    val ang = star.spikeAngle
                    spike(center, ang, len, len * 0.8f, sw, sc)
                    spike(center, ang + HALF_PI, len * 0.9f, len * 0.7f, sw, sc)
                    spike(center, ang + QUARTER_PI, len * 0.45f, len * 0.45f, sw * 0.7f, color.copy(alpha = a * STAR_SPIKE_A * 0.4f))
                }
            }
        }
        if (meteors && motion != 0f) drawMeteor(t, color, alpha, w, h)
    }
}

/** One diffraction spike through [c] at [ang], with independent +/- arm lengths (asymmetry = realism). */
private fun DrawScope.spike(c: Offset, ang: Float, lenPos: Float, lenNeg: Float, w: Float, color: Color) {
    val dx = cos(ang)
    val dy = sin(ang)
    drawLine(color, Offset(c.x - dx * lenNeg, c.y - dy * lenNeg), Offset(c.x + dx * lenPos, c.y + dy * lenPos), w, StrokeCap.Round)
}

/**
 * A rare shooting star, deterministic per time-window so no spawn state is needed: most windows draw
 * nothing; an active one streaks across with a bright head + fading tail, fading in then out.
 */
private fun DrawScope.drawMeteor(t: Float, color: Color, alpha: Float, w: Float, h: Float) {
    val window = floor(t / METEOR_PERIOD).toInt()
    val local = t - window * METEOR_PERIOD
    if (local > METEOR_DUR) return
    val rng = Random(window * 73856093 + 19349663)
    if (rng.nextFloat() > METEOR_CHANCE) return
    val prog = local / METEOR_DUR
    val sx = rng.nextFloat() * w
    val sy = rng.nextFloat() * h * 0.3f
    val dirX = if (rng.nextBoolean()) 1f else -1f
    val dist = (w + h) * 0.55f
    val vx = dirX * cos(METEOR_ANGLE) * dist
    val vy = sin(METEOR_ANGLE) * dist
    val hx = sx + vx * prog
    val hy = sy + vy * prog
    val tail = Offset(hx - vx * METEOR_TAIL, hy - vy * METEOR_TAIL)
    val head = Offset(hx, hy)
    val a = alpha * METEOR_A * sin(prog * PI).toFloat() // fade in → out
    drawLine(
        brush = Brush.linearGradient(0f to color.copy(alpha = 0f), 1f to color.copy(alpha = a), start = tail, end = head),
        start = tail,
        end = head,
        strokeWidth = METEOR_W.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(color.copy(alpha = a), radius = METEOR_W.dp.toPx(), center = head)
}

private fun wrap01(v: Float): Float = (v % 1f + 1f) % 1f

private class Star(
    val x0: Float, val y0: Float, val z: Float,
    val radiusDp: Float, val brightness: Float,
    val phaseA: Float, val phaseB: Float, val twinkleHz: Float,
    val spike: Boolean, val spikeJitter: Float, val spikeAngle: Float,
)

private enum class CrossMode { None, Perfect, Imperfect }

/** The look knobs that differ between variations; everything else (drift, twinkle, meteors) is shared. */
private class StarStyle(
    val minR: Float, val maxR: Float,                 // dp size range, depth-mapped
    val glowScale: Float, val glowA: Float, val glowMinR: Float,
    val cross: CrossMode, val softCore: Boolean,
)

// A — current: bigger, hard pinpoints, perfect crosses (the look you saw).
private val STYLE_CURRENT = StarStyle(0.4f, 2.4f, 4.0f, 0.45f, 1.0f, CrossMode.Perfect, softCore = false)
// B — pinpoint: tiny soft points, dense, no spikes (naked-eye night sky).
private val STYLE_PINPOINT = StarStyle(0.3f, 1.1f, 3.0f, 0.30f, 0.7f, CrossMode.None, softCore = true)
// C — soft bloom: every star a hazy glow, no hard edges, no spikes (dreamy).
private val STYLE_BLOOM = StarStyle(0.5f, 1.8f, 5.5f, 0.55f, 0.0f, CrossMode.None, softCore = true)
// D — astro: small hard cores, strong glow, asymmetric imperfect spikes (refined camera look).
private val STYLE_ASTRO = StarStyle(0.35f, 1.5f, 4.5f, 0.55f, 0.8f, CrossMode.Imperfect, softCore = false)

private const val TWO_PI = 6.2831855f
private const val HALF_PI = 1.5707964f
private const val QUARTER_PI = 0.7853982f
private const val SOFT_CORE_SCALE = 1.8f  // soft-core radius = base radius × this
private const val STAR_DRIFT = 0.015f     // field sweep — fraction of viewport / sec (subtle)
private const val STAR_DRIFT_Y = 0.35f    // gentle downward bias
private const val STAR_MIN_A = 0.28f
private const val STAR_MAX_A = 0.95f
private const val STAR_TW_MIN = 0.6f
private const val STAR_TW_RANGE = 1.6f
private const val STAR_SPIKE_Z = 0.86f    // nearest ~14% get spikes
private const val STAR_SPIKE_LEN = 3.2f
private const val STAR_SPIKE_W = 0.6f     // dp
private const val STAR_SPIKE_A = 0.5f
private const val METEOR_PERIOD = 20f     // sec between meteor windows
private const val METEOR_DUR = 1.0f       // sec a meteor is visible
private const val METEOR_CHANCE = 0.6f    // fraction of windows that spawn one
private const val METEOR_ANGLE = 0.95f    // radians below horizontal (~54°)
private const val METEOR_TAIL = 0.16f     // tail length, fraction of travel distance
private const val METEOR_A = 0.7f
private const val METEOR_W = 1.6f         // dp

// ── Demo previews (Run on device — motion, glow, and meteors only show live) ──────────────────────

@Composable
private fun DemoCell(name: String, style: StarStyle, count: Int, modifier: Modifier) {
    Box(modifier.background(MidnightColors.Void)) {
        StarFieldImpl(Modifier.fillMaxSize(), Color.White, 1f, count, 1f, meteors = false, style = style)
        BasicText(
            name,
            style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
    }
}

/** All four side by side — fastest way to compare size, shape, glow, and spikes. */
@Preview(name = "▶ StarField · COMPARE (run on device)", showBackground = true)
@Composable
private fun StarFieldCompare() {
    Column(Modifier.fillMaxSize().background(MidnightColors.Void)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            DemoCell("A · current", STYLE_CURRENT, 40, Modifier.fillMaxHeight().weight(1f))
            DemoCell("B · pinpoint", STYLE_PINPOINT, 70, Modifier.fillMaxHeight().weight(1f))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            DemoCell("C · soft bloom", STYLE_BLOOM, 52, Modifier.fillMaxHeight().weight(1f))
            DemoCell("D · astro", STYLE_ASTRO, 46, Modifier.fillMaxHeight().weight(1f))
        }
    }
}

@Composable
private fun DemoFull(style: StarStyle, count: Int) =
    Box(Modifier.fillMaxSize().background(MidnightColors.Void)) {
        StarFieldImpl(Modifier.fillMaxSize(), Color.White, 1f, count, 1f, meteors = true, style = style)
    }

@Preview(name = "▶ StarField · A current", showBackground = true)
@Composable
private fun DemoFullA() = DemoFull(STYLE_CURRENT, 48)

@Preview(name = "▶ StarField · B pinpoint", showBackground = true)
@Composable
private fun DemoFullB() = DemoFull(STYLE_PINPOINT, 90)

@Preview(name = "▶ StarField · C soft bloom", showBackground = true)
@Composable
private fun DemoFullC() = DemoFull(STYLE_BLOOM, 60)

@Preview(name = "▶ StarField · D astro", showBackground = true)
@Composable
private fun DemoFullD() = DemoFull(STYLE_ASTRO, 52)

internal fun lerp(a: Color, b: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f,
    )
}
