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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Subtle glass-style container for content zones that need to sit above
 * the ambient star field without visual clutter behind the text.
 *
 * Fill is a low-alpha tint (typically LightBarely) so stars are still
 * partially visible — the card "lifts" the content area without hiding
 * the brand texture. A 1dp hairline border in LightFaint defines the
 * edge.
 *
 * Not a true Gaussian blur. Real backdrop blur (Modifier.blur / API 31+)
 * is a future upgrade if the tint + border treatment isn't enough.
 *
 * @param tint the fill color — pass a low-alpha value (e.g., palette
 *   LightBarely token). Opaque values will fully hide stars behind.
 * @param border hairline color — pass palette LightFaint or pass
 *   Color.Transparent to suppress the border.
 * @param cornerRadius default radius-md (12dp) per the Dusk shapes rule
 *   "most components use radius-md". Matches BackupBanner / AddressChip /
 *   NetworkBadge radii so stacked cards feel consistent.
 * @param contentPadding inner padding. Defaults to space-16.
 */
@Composable
fun GlassPanel(
    tint: Color,
    border: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(tint)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(cornerRadius))
            .padding(contentPadding),
        content = content,
    )
}
