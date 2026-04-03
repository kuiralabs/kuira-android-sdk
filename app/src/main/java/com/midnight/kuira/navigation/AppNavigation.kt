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
import com.midnight.kuira.feature.send.SendScreen

sealed class Screen(val route: String) {
    data object Balance : Screen("balance")
    data object Send : Screen("send/{address}") {
        fun createRoute(address: String) = "send/$address"
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
                onNavigateToSend = { address ->
                    navController.navigate(Screen.Send.createRoute(address))
                },
                onNavigateToDust = { address ->
                    navController.navigate(Screen.Dust.createRoute(address))
                }
            )
        }

        // Send Screen
        composable(
            route = Screen.Send.route,
            arguments = listOf(
                navArgument("address") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address") ?: ""
            SendScreen(address = address)
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
