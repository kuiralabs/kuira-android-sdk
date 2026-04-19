package com.midnight.kuira.dev.wireframes.dust

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.designsystem.devportal.DevCheckboxRow
import com.midnight.kuira.core.designsystem.devportal.DevPortalModal
import com.midnight.kuira.core.designsystem.devportal.DevRadioRow
import com.midnight.kuira.core.designsystem.devportal.DevStateSection
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import com.midnight.kuira.feature.balance.redesign.DuskPalette

@Composable
fun DustWireframeWithDevControls(
    onBack: () -> Unit = {},
    onOpenWireframeList: () -> Unit = {},
) {
    var state by remember { mutableStateOf(DustWireframeState.DEFAULT) }
    var lightMode by remember { mutableStateOf(false) }
    var modalOpen by remember { mutableStateOf(false) }

    val palette = if (lightMode) DuskPalette.LightMode else DuskPalette.DarkMode

    Box(modifier = Modifier.fillMaxSize()) {
        DustWireframe(state = state, palette = palette, onBack = onBack)

        FloatingActionButton(
            onClick = { modalOpen = true },
            containerColor = MidnightColors.VoidElevated,
            contentColor = MidnightColors.Light,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 72.dp, end = 16.dp),
        ) {
            Icon(Icons.Filled.Build, contentDescription = "Open dev controls")
        }
    }

    if (modalOpen) {
        DevPortalModal(
            title = "Dust \u00B7 Dev Controls",
            onDismiss = { modalOpen = false },
            onOpenWireframeList = {
                modalOpen = false
                onOpenWireframeList()
            },
            stateControls = {
                DevStateSection(label = "STATE") {
                    DustWireframeState.entries.forEach { s ->
                        DevRadioRow(
                            label = s.name.lowercase().replace('_', '-'),
                            selected = s == state,
                            onClick = { state = s },
                        )
                    }
                }

                Spacer(modifier = Modifier.padding(top = 16.dp))

                DevStateSection(label = "OPTIONS") {
                    DevCheckboxRow(
                        label = "Light mode",
                        checked = lightMode,
                        onCheckedChange = { lightMode = it },
                    )
                }
            },
        )
    }
}
