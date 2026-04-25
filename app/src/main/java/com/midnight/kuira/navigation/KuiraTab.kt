package com.midnight.kuira.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The 4 bottom navigation tabs in Kuira.
 *
 * My Sigil is the start destination (home tab). Assets contains the
 * Balance/Send/Dust wallet features. Activity shows transaction history.
 * Settings is the sigil-wide preferences tab.
 *
 * Graph routes reference [Screen] sealed class — single source of truth.
 */
enum class KuiraTab(
    val graphRoute: String,
    val icon: ImageVector,
    val label: String,
) {
    MY_SIGIL(Screen.MySigilGraph.route, Icons.Default.Shield, "My Sigil"),
    ASSETS(Screen.AssetsGraph.route, Icons.Outlined.AccountBalanceWallet, "Assets"),
    ACTIVITY(Screen.ActivityGraph.route, Icons.Default.History, "Activity"),
    SETTINGS(Screen.SettingsGraph.route, Icons.Default.Settings, "Settings"),
}
