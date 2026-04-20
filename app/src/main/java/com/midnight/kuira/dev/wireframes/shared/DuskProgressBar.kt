package com.midnight.kuira.dev.wireframes.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.midnight.kuira.feature.balance.redesign.DuskPalette

/**
 * Dusk-palette progress bar. No accent color — uses Light fill on
 * LightBarely track (monochrome, on-brand). In light mode: black fill
 * on near-transparent track. Same opposite-pole principle as buttons.
 *
 * Used for: dust generation progress, sync progress on Balance,
 * any future progress indication.
 */
@Composable
fun DuskProgressBar(
    progress: Float, // 0f..1f
    palette: DuskPalette,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(palette.LightBarely),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(palette.LightSoft),
        )
    }
}
