package com.midnight.kuira.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.network.MidnightNetwork

/**
 * Network selector bar with segmented buttons.
 *
 * **UI:**
 * - Horizontal segmented button row showing all network options
 * - Current network is highlighted
 * - Selecting a different network shows restart confirmation dialog
 *
 * **MVP Limitation:**
 * App restart is required after network change because Hilt singletons
 * are created at startup with network-specific URLs.
 *
 * @param selectedNetwork Currently selected network
 * @param onNetworkSelected Callback when user confirms network change
 * @param modifier Optional modifier for the component
 */
@Composable
fun NetworkSelectorBar(
    selectedNetwork: MidnightNetwork,
    onNetworkSelected: (MidnightNetwork) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    var pendingNetwork by remember { mutableStateOf<MidnightNetwork?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Network",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            MidnightNetwork.entries.forEachIndexed { index, network ->
                SegmentedButton(
                    selected = network == selectedNetwork,
                    onClick = {
                        if (network != selectedNetwork) {
                            pendingNetwork = network
                            showRestartDialog = true
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = MidnightNetwork.entries.size
                    )
                ) {
                    Text(text = network.displayName)
                }
            }
        }
    }

    if (showRestartDialog && pendingNetwork != null) {
        RestartRequiredDialog(
            networkName = pendingNetwork!!.displayName,
            onConfirm = {
                onNetworkSelected(pendingNetwork!!)
                showRestartDialog = false
                pendingNetwork = null
            },
            onDismiss = {
                showRestartDialog = false
                pendingNetwork = null
            }
        )
    }
}

/**
 * Dialog shown when user changes network, explaining restart requirement.
 */
@Composable
private fun RestartRequiredDialog(
    networkName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Restart Required")
        },
        text = {
            Text(
                "Switching to $networkName requires restarting the app. " +
                    "The app will close and you'll need to open it again."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Restart")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
