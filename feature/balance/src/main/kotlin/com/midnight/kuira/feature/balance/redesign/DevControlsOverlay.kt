package com.midnight.kuira.feature.balance.redesign

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

/**
 * Balance wireframe with dev-portal modal. Tap the FAB → bottom-sheet
 * modal with state switcher + backup banner toggle + light/dark toggle +
 * a link back to the full wireframe list.
 */
@Composable
fun BalanceWireframeWithDevControls(
    onBack: () -> Unit = {},
    onOpenWireframeList: () -> Unit = {},
) {
    var state by remember { mutableStateOf(WireframeState.DEFAULT) }
    var showBanner by remember { mutableStateOf(true) }
    var lightMode by remember { mutableStateOf(false) }
    var modalOpen by remember { mutableStateOf(false) }

    val palette = if (lightMode) DuskPalette.Light else DuskPalette.Dark

    Box(modifier = Modifier.fillMaxSize()) {
        BalanceWireframe(
            state = state,
            showBackupBanner = showBanner,
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
            title = "Balance · Dev Controls",
            onDismiss = { modalOpen = false },
            onOpenWireframeList = {
                modalOpen = false
                onOpenWireframeList()
            },
            stateControls = {
                DevStateSection(label = "STATE") {
                    WireframeState.entries.forEach { wireframeState ->
                        DevRadioRow(
                            label = wireframeState.name.lowercase().replace('_', '-'),
                            selected = wireframeState == state,
                            onClick = { state = wireframeState },
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
                    DevCheckboxRow(
                        label = "Backup banner",
                        checked = showBanner,
                        onCheckedChange = { showBanner = it },
                    )
                }
            },
        )
    }
}
