package com.midnight.kuira.dev.wireframes.settings

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

/**
 * Settings wireframe with dev-portal modal. Tap the FAB → bottom-sheet
 * modal with state switcher (default / dev-mode-unlocked) + light/dark
 * toggle + link back to the full wireframe list.
 */
@Composable
fun SettingsWireframeWithDevControls(
    onBack: () -> Unit = {},
    onOpenWireframeList: () -> Unit = {},
) {
    var devModeUnlocked by remember { mutableStateOf(false) }
    var lightMode by remember { mutableStateOf(false) }
    var modalOpen by remember { mutableStateOf(false) }

    val palette = if (lightMode) DuskPalette.LightMode else DuskPalette.DarkMode

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsWireframe(
            devModeUnlocked = devModeUnlocked,
            palette = palette,
            onBack = onBack,
        )

        FloatingActionButton(
            onClick = { modalOpen = true },
            // FAB keeps dark-mode styling regardless of wireframe palette —
            // it's dev chrome, not part of the wireframe under review.
            containerColor = MidnightColors.VoidElevated,
            contentColor = MidnightColors.Light,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Build, contentDescription = "Open dev controls")
        }
    }

    if (modalOpen) {
        DevPortalModal(
            title = "Settings · Dev Controls",
            onDismiss = { modalOpen = false },
            onOpenWireframeList = {
                modalOpen = false
                onOpenWireframeList()
            },
            stateControls = {
                // Settings has only `default`, but dev-mode-unlocked is a
                // variant that inserts the DEVELOPER OPTIONS section — wire
                // it as a radio for consistency with how other wireframes
                // surface state variants.
                DevStateSection(label = "STATE") {
                    DevRadioRow(
                        label = "default",
                        selected = !devModeUnlocked,
                        onClick = { devModeUnlocked = false },
                    )
                    DevRadioRow(
                        label = "default / dev-mode-unlocked",
                        selected = devModeUnlocked,
                        onClick = { devModeUnlocked = true },
                    )
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
