// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.designsystem.effect.KuiraMaterializeFrame
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import kotlinx.coroutines.delay

/**
 * Animation timing for the Dusk entrance sequence.
 *
 * Each phase kicks in with a small delay and fades toward its target value
 * using the same curves as the existing approval UI — keeping a unified
 * visual language across all Dusk-themed screens.
 */
private object DuskEntrance {
    const val BG_DURATION = 300
    const val STAR_DELAY = 150L
    const val STAR_DURATION = 600
    const val STAR_TARGET_ALPHA = 0.7f
    const val BRAND_DELAY = 200L
    const val BRAND_DURATION = 500
    const val SHEET_DELAY = 100L
    const val SHEET_DURATION = 400
    const val SHEET_OFFSET_INITIAL = 400f
    const val CONTENT_DELAY = 350L
    const val CONTENT_DURATION = 300
}

/**
 * Layout constants for the Dusk theme. Pulled into one place so all
 * Dusk-styled screens stay visually consistent without magic numbers.
 */
private object DuskLayout {
    val BrandTopPadding = 80.dp
    val SheetCornerRadius = 24.dp
    val SheetHorizontalPadding = 32.dp
    val SheetTopPadding = 20.dp
    val SheetBottomPadding = 40.dp
    val DragHandleWidth = 28.dp
    val DragHandleHeight = 1.dp
    val DragHandleSpacing = 32.dp
}

/**
 * Full-screen Dusk-themed scaffold with animated void + stars + KUIRA brand
 * and an optional bottom sheet for content.
 *
 * This is the shared visual container for every approval/onboarding/auth
 * screen in the wallet. It handles the entrance animation (background fade,
 * star twinkle, brand materialize, sheet slide-up) and gives callers a
 * [BoxScope] for any additional content.
 *
 * @param modifier Optional root modifier
 * @param onDismissBackground Called if the user taps outside the sheet.
 *   Pass `null` to disable dismissal (e.g., during a critical flow).
 * @param showBrand Whether to show the animated KUIRA wordmark at the top.
 * @param sheet Bottom sheet content — receives a [ColumnScope] and a content
 *   fade-in alpha that gates inner content until the entrance settles.
 * @param background Optional extra background content (behind stars).
 */
@Composable
fun DuskScaffold(
    modifier: Modifier = Modifier,
    onDismissBackground: (() -> Unit)? = null,
    showBrand: Boolean = true,
    sheet: (@Composable ColumnScope.(contentAlpha: Float) -> Unit)? = null,
    background: (@Composable BoxScope.() -> Unit)? = null,
) {
    val bgAlpha = remember { Animatable(0f) }
    val starAlpha = remember { Animatable(0f) }
    val brandAlpha = remember { Animatable(0f) }
    val sheetOffset = remember { Animatable(DuskEntrance.SHEET_OFFSET_INITIAL) }
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        bgAlpha.animateTo(1f, tween(DuskEntrance.BG_DURATION, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(DuskEntrance.STAR_DELAY)
        starAlpha.animateTo(DuskEntrance.STAR_TARGET_ALPHA, tween(DuskEntrance.STAR_DURATION))
    }
    LaunchedEffect(Unit) {
        delay(DuskEntrance.BRAND_DELAY)
        brandAlpha.animateTo(1f, tween(DuskEntrance.BRAND_DURATION))
    }
    LaunchedEffect(Unit) {
        delay(DuskEntrance.SHEET_DELAY)
        sheetOffset.animateTo(0f, tween(DuskEntrance.SHEET_DURATION, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(DuskEntrance.CONTENT_DELAY)
        contentAlpha.animateTo(1f, tween(DuskEntrance.CONTENT_DURATION))
    }

    // Root box: void background + optional tap-to-dismiss.
    // We use a no-indication clickable so the ripple doesn't bleed through.
    val rootClickable = if (onDismissBackground != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismissBackground,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MidnightColors.Void.copy(alpha = bgAlpha.value))
            .then(rootClickable),
    ) {
        background?.invoke(this)

        StarField(modifier = Modifier.fillMaxSize(), alpha = starAlpha.value)

        if (showBrand) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = DuskLayout.BrandTopPadding)
                    .alpha(brandAlpha.value),
            ) {
                KuiraMaterializeFrame(progress = 1f)
            }
        }

        if (sheet != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = sheetOffset.value.dp)
                    .fillMaxWidth()
                    .clip(
                        RoundedCornerShape(
                            topStart = DuskLayout.SheetCornerRadius,
                            topEnd = DuskLayout.SheetCornerRadius,
                        )
                    )
                    .background(MidnightColors.VoidSoft)
                    // Block tap-through so sheet content doesn't trigger dismiss
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = false,
                        onClick = {},
                    )
                    .padding(
                        start = DuskLayout.SheetHorizontalPadding,
                        end = DuskLayout.SheetHorizontalPadding,
                        top = DuskLayout.SheetTopPadding,
                        bottom = DuskLayout.SheetBottomPadding,
                    ),
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(DuskLayout.DragHandleWidth)
                        .height(DuskLayout.DragHandleHeight)
                        .background(MidnightColors.LightFaint),
                )
                Spacer(modifier = Modifier.height(DuskLayout.DragHandleSpacing))

                sheet(contentAlpha.value)
            }
        }
    }
}
