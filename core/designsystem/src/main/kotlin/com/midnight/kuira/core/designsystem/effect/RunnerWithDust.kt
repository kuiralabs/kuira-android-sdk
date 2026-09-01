package com.midnight.kuira.core.designsystem.effect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
/**
 * Half the box, so the dust band's right edge lands on the runner's centre — its feet. Any wider
 * and the spawn point crosses in front of the runner.
 */
private const val TRAIL_WIDTH_FRACTION = 0.5f

@Composable
fun RunnerWithDust(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // The trail band's RIGHT edge must sit at the runner's feet, so dust spawns AT the feet
        // and drifts left (behind). Letting the band fill the whole box put its spawn point —
        // 78% of the band's width, see DustTrail — well past the runner's centre, so dust kicked
        // up in FRONT. Same geometry as RunnerDustProgress, which was fixed for this reason.
        DustTrail(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(TRAIL_WIDTH_FRACTION)
                .align(Alignment.CenterStart),
            color = color,
            particleCount = 18,
            maxAlpha = 0.6f,
        )
        // Runner on top
        LottieRunner(
            modifier = Modifier.matchParentSize(),
            color = color,
        )
    }
}
