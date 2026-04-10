// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Reusable button styles for Dusk-themed screens.
 *
 * Three variants matching the existing approval UI aesthetic:
 * - [DuskPrimaryButton]: white fill, dark text (primary action)
 * - [DuskSecondaryButton]: near-transparent fill, muted text (secondary / deny)
 * - [DuskButtonRow]: primary + secondary side-by-side with 2dp gap
 */

@Composable
fun DuskPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(BUTTON_HEIGHT)
            .clip(RoundedCornerShape(BUTTON_RADIUS))
            .background(if (enabled) MidnightColors.Light else MidnightColors.LightBarely)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) MidnightColors.Void else MidnightColors.LightMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun DuskSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(BUTTON_HEIGHT)
            .clip(RoundedCornerShape(BUTTON_RADIUS))
            .background(MidnightColors.LightBarely)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MidnightColors.LightMuted,
            fontSize = 13.sp,
        )
    }
}

/**
 * Two-button row: secondary on the left, primary on the right.
 * Standard layout for confirm/cancel choices in the Dusk theme.
 */
@Composable
fun DuskButtonRow(
    secondaryText: String,
    primaryText: String,
    onSecondary: () -> Unit,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DuskSecondaryButton(
            text = secondaryText,
            onClick = onSecondary,
            modifier = Modifier.weight(1f),
        )
        DuskPrimaryButton(
            text = primaryText,
            onClick = onPrimary,
            modifier = Modifier.weight(1f),
            enabled = primaryEnabled,
        )
    }
}

private val BUTTON_HEIGHT = 48.dp
private val BUTTON_RADIUS = 12.dp
