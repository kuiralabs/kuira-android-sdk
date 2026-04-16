package com.midnight.kuira.feature.balance.redesign

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors

@Composable
fun BalanceWireframeWithDevControls() {
    var state by remember { mutableStateOf(WireframeState.DEFAULT) }
    var showBanner by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        BalanceWireframe(state = state, showBackupBanner = showBanner)

        // Dev controls FAB
        if (!showControls) {
            FloatingActionButton(
                onClick = { showControls = true },
                containerColor = MidnightColors.VoidElevated,
                contentColor = MidnightColors.Light,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Build, contentDescription = "Open dev controls")
            }
        }

        // Dev controls panel
        if (showControls) {
            DevControlsPanel(
                currentState = state,
                showBanner = showBanner,
                onStateChange = { state = it },
                onBannerToggle = { showBanner = it },
                onDismiss = { showControls = false },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun DevControlsPanel(
    currentState: WireframeState,
    showBanner: Boolean,
    onStateChange: (WireframeState) -> Unit,
    onBannerToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MidnightColors.VoidElevated)
            .padding(16.dp)
            .width(200.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DEV CONTROLS",
                color = MidnightColors.LightMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.W400,
                letterSpacing = 3.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MidnightColors.LightMuted,
                    modifier = Modifier.height(16.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "State:",
            color = MidnightColors.LightSoft,
            fontSize = 12.sp,
            fontWeight = FontWeight.W400,
        )
        Spacer(modifier = Modifier.height(8.dp))

        WireframeState.entries.forEach { wireframeState ->
            val isSelected = wireframeState == currentState
            Text(
                text = wireframeState.name.lowercase().replace('_', '-'),
                color = if (isSelected) MidnightColors.Light else MidnightColors.LightMuted,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.W400 else FontWeight.W300,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSelected) Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MidnightColors.LightBarely)
                        else Modifier
                    )
                    .clickable { onStateChange(wireframeState) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Checkbox(
                checked = showBanner,
                onCheckedChange = onBannerToggle,
                colors = CheckboxDefaults.colors(
                    checkedColor = MidnightColors.Light,
                    uncheckedColor = MidnightColors.LightMuted,
                    checkmarkColor = MidnightColors.Void,
                ),
            )
            Text(
                text = "Backup Banner",
                color = MidnightColors.LightSoft,
                fontSize = 12.sp,
            )
        }
    }
}
