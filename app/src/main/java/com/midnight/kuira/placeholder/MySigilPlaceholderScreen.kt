package com.midnight.kuira.placeholder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.designsystem.theme.DuskTypography
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * My Sigil tab placeholder — ships in 8B with minimal content.
 * Replaced by the full sigil dashboard in Phase 7+/9+.
 */
@Composable
fun MySigilPlaceholderScreen(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MidnightColors.Void),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "MY SIGIL",
                style = DuskTypography.labelTiny,
                color = MidnightColors.LightMuted,
            )
            Text(
                text = "Kuira",
                style = DuskTypography.headlineMd,
                color = MidnightColors.Light,
            )
            Text(
                text = "Your private identity for Midnight",
                style = DuskTypography.detail,
                color = MidnightColors.LightMuted,
            )
        }
    }
}
