package com.midnight.kuira.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Texture exploration for the wallet panel surface — the same mock card rendered with a range of
 * fills, from today's near-opaque through progressively glassier/more transparent treatments. The
 * star field + soft glows behind make the see-through effect visible; scroll to compare. Run on
 * device (the star field only animates live). Monochrome throughout (brand).
 */
private class GlassVariant(
    val name: String,
    val fill: Brush,
    val sheenAlpha: Float,   // top specular highlight strength (0 = none)
    val borderAlpha: Float,  // rim hairline brightness
)

private val GLASS_VARIANTS = listOf(
    GlassVariant(
        "1 · Opaque (current)",
        SolidColor(MidnightColors.VoidElevated.copy(alpha = 0.92f)),
        sheenAlpha = 0.08f, borderAlpha = 0.10f,
    ),
    GlassVariant(
        "2 · Frosted · 65%",
        SolidColor(MidnightColors.VoidElevated.copy(alpha = 0.65f)),
        sheenAlpha = 0.12f, borderAlpha = 0.16f,
    ),
    GlassVariant(
        "3 · Deep glass · 42%",
        SolidColor(MidnightColors.VoidElevated.copy(alpha = 0.42f)),
        sheenAlpha = 0.18f, borderAlpha = 0.26f,
    ),
    GlassVariant(
        "4 · Gradient glass",
        Brush.verticalGradient(
            listOf(MidnightColors.Light.copy(alpha = 0.12f), MidnightColors.VoidElevated.copy(alpha = 0.55f)),
        ),
        sheenAlpha = 0f, borderAlpha = 0.20f,
    ),
    GlassVariant(
        "5 · Edge-lit · 50%",
        SolidColor(MidnightColors.VoidElevated.copy(alpha = 0.50f)),
        sheenAlpha = 0.32f, borderAlpha = 0.34f,
    ),
)

@Composable
private fun T(text: String, size: Int, color: Color, weight: Float = 1f) =
    BasicText(text, style = TextStyle(color = color.copy(alpha = color.alpha * weight), fontSize = size.sp))

@Composable
private fun DemoGlassPanel(v: GlassVariant) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(v.fill)
            .then(
                if (v.sheenAlpha > 0f) {
                    Modifier.background(
                        Brush.verticalGradient(
                            0f to MidnightColors.Light.copy(alpha = v.sheenAlpha),
                            0.5f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .border(1.dp, MidnightColors.Light.copy(alpha = v.borderAlpha), shape)
            .padding(20.dp),
    ) {
        Column {
            T("NIGHT", 11, MidnightColors.LightMuted)
            Spacer(Modifier.height(4.dp))
            T("1,234.5", 34, MidnightColors.Light)
            Spacer(Modifier.height(10.dp))
            Row {
                T("DUST 933  ·  PreProd  ", 12, MidnightColors.LightMuted)
                T("✓", 12, MidnightColors.SuccessText)
            }
            Spacer(Modifier.height(16.dp))
            T(v.name, 11, MidnightColors.LightFaint)
        }
    }
}

/** Soft monochrome glows so the transparent panels clearly have *something* to reveal behind them. */
@Composable
private fun SoftGlows() {
    Canvas(Modifier.fillMaxSize()) {
        fun glow(fx: Float, fy: Float, fr: Float, a: Float) {
            val c = Offset(fx * size.width, fy * size.height)
            val r = fr * size.minDimension
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = a),
                    1f to Color.White.copy(alpha = 0f),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
        }
        glow(0.28f, 0.22f, 0.55f, 0.16f)
        glow(0.78f, 0.5f, 0.6f, 0.13f)
        glow(0.4f, 0.85f, 0.55f, 0.12f)
    }
}

@Preview(name = "▶ Panel texture (run on device)", showBackground = true)
@Composable
private fun PanelTextureDemo() {
    Box(Modifier.fillMaxSize().background(MidnightColors.Void)) {
        StarField(Modifier.fillMaxSize(), alpha = 0.7f)
        SoftGlows()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            GLASS_VARIANTS.forEach { DemoGlassPanel(it) }
            Spacer(Modifier.height(28.dp))
        }
    }
}
