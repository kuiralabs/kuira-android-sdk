package com.midnight.kuira.core.designsystem.effect

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Combined Rarámuri runner + dust trail — the full brand progress
 * indicator. Dust particles kick up behind the runner's feet.
 *
 * Single composable for all pending/loading screens. Pass
 * `palette.Light` as [color] for palette-aware monochrome rendering.
 */
@Composable
fun RunnerWithDust(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Dust behind the runner
        DustTrail(
            modifier = Modifier.matchParentSize(),
            color = color,
            particleCount = 12,
            maxAlpha = 0.25f,
        )
        // Runner on top
        LottieRunner(
            modifier = Modifier.matchParentSize(),
            color = color,
        )
    }
}
