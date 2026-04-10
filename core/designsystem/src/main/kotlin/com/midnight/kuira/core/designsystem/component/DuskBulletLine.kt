// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Bulleted line for Dusk-themed lists (permissions, feature summaries, etc.).
 *
 * Faint bullet marker, soft-muted body text — matches the approval UI style.
 */
@Composable
fun DuskBulletLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.padding(vertical = 4.dp)) {
        Text("\u2022", color = MidnightColors.LightFaint, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = MidnightColors.LightSoft, fontSize = 13.sp)
    }
}
