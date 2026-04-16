package com.midnight.kuira.navigation

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.midnight.kuira.dev.DevPortalScreen
import com.midnight.kuira.feature.balance.BalanceScreen
import com.midnight.kuira.feature.balance.redesign.BalanceWireframeWithDevControls
import com.midnight.kuira.feature.dust.DustScreen
import com.midnight.kuira.feature.send.SendMode
import com.midnight.kuira.feature.send.SendScreen

sealed class Screen(val route: String) {
    data object DevPortal : Screen("dev-portal")
    data object BalanceWireframe : Screen("balance-wireframe")
    data object Balance : Screen("balance")

    // Send screen takes an optional mode hint ("unshielded" or "shielded").
    // BalanceScreen can pass this to pre-select the mode when the user taps
    // the matching balance card. SendScreen still lets the user toggle the
    // mode once they're on the screen.
    data object Send : Screen("send?mode={mode}") {
        fun createRoute(mode: SendMode = SendMode.UNSHIELDED) =
            "send?mode=${mode.name.lowercase()}"
    }

    data object Dust : Screen("dust/{address}") {
        fun createRoute(address: String) = "dust/$address"
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Balance.route
    ) {
        // Dev Portal — index of wireframes (remove after 8B.1)
        composable(route = Screen.DevPortal.route) {
            DevPortalScreen(
                onOpenWireframe = { route -> navController.navigate(route) },
            )
        }

        // Balance Wireframe (design preview — remove after 8B.1)
        composable(route = Screen.BalanceWireframe.route) {
            BalanceWireframeWithDevControls(
                onBack = { navController.popBackStack() },
                onOpenWireframeList = {
                    navController.navigate(Screen.DevPortal.route) {
                        popUpTo(Screen.DevPortal.route) { inclusive = true }
                    }
                },
            )
        }

        // Balance Screen — with dev-portal FAB overlay (remove FAB after 8B.1)
        composable(route = Screen.Balance.route) {
            com.midnight.kuira.dev.BalanceWithDevPortalFab(
                onOpenDevPortal = { navController.navigate(Screen.DevPortal.route) },
            ) {
                BalanceScreen(
                    onNavigateToSend = {
                        // SendScreen reads the active address from WalletAddressCache
                        // based on its own mode toggle. Per-mode pre-selection from
                        // the balance cards is deferred — when wired, pass a SendMode
                        // through a separate per-mode callback.
                        navController.navigate(Screen.Send.createRoute())
                    },
                    onNavigateToDust = { address ->
                        navController.navigate(Screen.Dust.createRoute(address))
                    }
                )
            }
        }

        // Send Screen — mode-driven. The optional `mode` query arg is used
        // to pre-select the Send Mode toggle; defaults to unshielded.
        composable(
            route = Screen.Send.route,
            arguments = listOf(
                navArgument("mode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val modeArg = backStackEntry.arguments?.getString("mode")
            val initialMode = when (modeArg?.lowercase()) {
                "shielded" -> SendMode.SHIELDED
                else -> SendMode.UNSHIELDED
            }
            SendScreen(initialMode = initialMode)
        }

        // Dust Screen
        composable(
            route = Screen.Dust.route,
            arguments = listOf(
                navArgument("address") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address") ?: ""
            DustScreen(address = address)
        }
    }
}
