package com.midnight.kuira.core.designsystem.effect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * A shimmering placeholder block for first-load skeletons — a luminance sweep
 * across a rounded bar. Brand-neutral via [base]/[highlight] (default to the
 * Midnight palette's faintest light tokens), so it reads as "loading" without
 * introducing hue. Use one per line of content being awaited.
 */
@Composable
fun ShimmerBlock(
    height: Dp,
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    base: Color = MidnightColors.LightBarely,
    highlight: Color = MidnightColors.LightFaint,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offsetX by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )

    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(offsetX * 300f, 0f),
        end = Offset(offsetX * 300f + 300f, 0f),
    )

    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(brush),
    )
}
