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
 * Activity tab placeholder — ships in 8B with empty state.
 * Replaced by the full tx history + audit log when built.
 */
@Composable
fun ActivityPlaceholderScreen(
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
                text = "ACTIVITY",
                color = MidnightColors.LightMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.W400,
                letterSpacing = 3.sp,
            )
            Text(
                text = "No activity yet",
                color = MidnightColors.Light,
                fontSize = 18.sp,
                fontWeight = FontWeight.W300,
            )
            Text(
                text = "Transactions and delegations will appear here",
                color = MidnightColors.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
            )
        }
    }
}
