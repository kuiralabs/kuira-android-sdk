package com.midnight.kuira.dev

import androidx.compose.foundation.layout.Box
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
import com.midnight.kuira.core.designsystem.devportal.DevPortalModal
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Wraps the real Balance screen with a floating action button that opens the
 * Dev Portal modal. Temporary affordance for the 8B.1 design sprint — remove
 * after the redesign lands in 8B.3.
 */
@Composable
fun BalanceWithDevPortalFab(
    onOpenDevPortal: () -> Unit,
    content: @Composable () -> Unit,
) {
    var modalOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        content()

        FloatingActionButton(
            onClick = { modalOpen = true },
            containerColor = MidnightColors.VoidElevated,
            contentColor = MidnightColors.Light,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Build, contentDescription = "Open dev portal")
        }
    }

    if (modalOpen) {
        DevPortalModal(
            title = "Dev Portal",
            onDismiss = { modalOpen = false },
            onOpenWireframeList = {
                modalOpen = false
                onOpenDevPortal()
            },
            stateControls = null, // real app has no swappable states
        )
    }
}
