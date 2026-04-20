package com.midnight.kuira.core.designsystem.effect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.midnight.kuira.core.designsystem.R

/**
 * Rarámuri runner progress indicator — Lottie-animated version.
 *
 * Loads the `run_man_run.lottie` asset and tints it to a single
 * color (white in dark mode, black in light mode) using SrcIn
 * blend mode. Loops infinitely as a progress/loading indicator.
 *
 * The Tarahumara (Rarámuri) are legendary ultra-distance runners.
 * "Kuira" derives from their language. This animation connects the
 * brand origin to every "please wait" moment in the app.
 */
@Composable
fun LottieRunner(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.run_man_run),
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
    )

    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier
            // Offscreen compositing required for SrcIn blend to work
            // correctly — without it, SrcIn blends with the background
            // instead of just the Lottie content.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    color = color,
                    blendMode = BlendMode.SrcIn,
                )
            },
    )
}
