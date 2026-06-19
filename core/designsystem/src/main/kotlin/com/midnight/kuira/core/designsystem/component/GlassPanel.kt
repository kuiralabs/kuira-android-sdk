package com.midnight.kuira.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted-glass container for content zones that sit above the ambient star
 * field. Three layers build the glass without a drop shadow (the brand forbids
 * shadows — lift comes from light, not depth):
 *
 *  1. **Fill** — the [tint]. Pass a LOW-ALPHA value (e.g. 6–9% white) so the
 *     star field shows faintly through; that translucency *is* the "frost".
 *  2. **Sheen** — a top-edge highlight gradient ([sheen]) fading to transparent,
 *     the specular cue that reads as glass. On by default; pass `false` for a
 *     plain tinted panel.
 *  3. **Edge** — a 1dp [border] hairline (LightFaint) defining the rim.
 *
 * Not a true backdrop blur (Compose has no built-in equivalent of CSS
 * `backdrop-filter`). On the near-black star field the translucent fill + sheen
 * reads as frost; a real Gaussian backdrop blur would need a capture pass
 * (`graphicsLayer` + render-effect) or a dedicated library.
 *
 * @param tint fill color — pass a low-alpha value so stars show through. An
 *   opaque value still works (no frost, just a flat lifted panel).
 * @param border hairline color — palette LightFaint, or `Color.Transparent` to drop it.
 * @param sheen draw the top specular highlight. Default true.
 * @param cornerRadius default radius-md (12dp).
 * @param contentPadding inner padding. Defaults to space-16.
 */
@Composable
fun GlassPanel(
    tint: Color,
    border: Color,
    modifier: Modifier = Modifier,
    sheen: Boolean = true,
    cornerRadius: Dp = 12.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            // Layer 1: translucent fill — stars show through.
            .background(tint)
            // Layer 2: top sheen highlight — the specular "glass" cue (drawn over the
            // fill, under the content). Subtle so it never competes with text.
            .then(if (sheen) Modifier.background(GLASS_SHEEN) else Modifier)
            // Layer 3: rim hairline.
            .border(BorderStroke(1.dp, border), shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Top-edge specular highlight that fades to transparent over the upper ~45% of
 * the panel — the cue the eye reads as a glass surface catching light. Kept low
 * (≤10% white) so it lifts the rim without washing the fill.
 */
private val GLASS_SHEEN = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.10f),
    0.45f to Color.Transparent,
    1f to Color.Transparent,
)
