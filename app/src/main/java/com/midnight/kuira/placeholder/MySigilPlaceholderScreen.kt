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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                color = MidnightColors.LightMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.W400,
                letterSpacing = 3.sp,
            )
            Text(
                text = "Kuira",
                color = MidnightColors.Light,
                fontSize = 22.sp,
                fontWeight = FontWeight.W300,
            )
            Text(
                text = "Your private identity for Midnight",
                color = MidnightColors.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
            )
        }
    }
}
