package com.midnight.kuira.dapp.backup

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
import com.midnight.kuira.dapp.R

/**
 * Rarámuri runner progress indicator (SDK-owned copy of the app-side
 * `core:designsystem` runner — the SDK can't depend on that unpublished module).
 *
 * Loads `run_man_run.lottie` and tints it to [color] via SrcIn so it matches the
 * pill theme; loops forever as a backup/loading indicator. The Tarahumara
 * (Rarámuri) are ultra-distance runners — "Kuira" comes from their language.
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
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(color = color, blendMode = BlendMode.SrcIn)
            },
    )
}
