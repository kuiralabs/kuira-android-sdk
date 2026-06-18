package com.midnight.kuira.core.designsystem.effect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
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

    // Tint with Lottie's native COLOR_FILTER on every layer ("**") instead of an
    // offscreen graphics layer + per-frame SrcIn blend. The offscreen path allocated
    // a compositing buffer and re-blended each frame — heavy on the cold first frame
    // (the send-screen mount hitch, #289) and wasteful while looping. A color filter
    // recolors the monochrome runner with no extra layer. SimpleColorFilter is
    // SRC_ATOP, so it tints the silhouette to [color] while preserving its alpha —
    // same visual result as the old SrcIn.
    val dynamicProperties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR_FILTER,
            value = SimpleColorFilter(color.toArgb()),
            keyPath = arrayOf("**"),
        ),
    )

    LottieAnimation(
        composition = composition,
        progress = { progress },
        dynamicProperties = dynamicProperties,
        modifier = modifier,
    )
}
