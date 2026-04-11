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
import com.midnight.kuira.feature.balance.BalanceScreen
import com.midnight.kuira.feature.dust.DustScreen
import com.midnight.kuira.feature.send.SendMode
import com.midnight.kuira.feature.send.SendScreen

sealed class Screen(val route: String) {
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
        // Balance Screen
        composable(route = Screen.Balance.route) {
            BalanceScreen(
                onNavigateToSend = { _ ->
                    // Address is no longer passed — SendScreen reads it from
                    // WalletAddressCache based on the active mode.
                    // TODO(8A.8d): pass mode from BalanceScreen when we add
                    // per-mode send buttons.
                    navController.navigate(Screen.Send.createRoute())
                },
                onNavigateToDust = { address ->
                    navController.navigate(Screen.Dust.createRoute(address))
                }
            )
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
