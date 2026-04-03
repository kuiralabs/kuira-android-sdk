package com.midnight.kuira.navigation

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.midnight.kuira.feature.balance.BalanceScreen
import com.midnight.kuira.feature.dust.DustScreen
import com.midnight.kuira.feature.send.SendScreen
import com.midnight.kuira.core.designsystem.effect.MidnightEntrance

/**
 * App navigation routes.
 *
 * **Pattern:** Sealed class for type-safe navigation with arguments
 * **Routes:**
 * - Balance: Main screen showing wallet balance
 * - Send: Send transaction screen (requires sender address)
 * - Dust: Dust tank management screen (requires wallet address)
 */
sealed class Screen(val route: String) {
    data object Balance : Screen("balance")
    data object Send : Screen("send/{address}") {
        fun createRoute(address: String) = "send/$address"
    }
    data object Dust : Screen("dust/{address}") {
        fun createRoute(address: String) = "dust/$address"
    }
    data object Prototype : Screen("prototype")
}

/**
 * Main app navigation setup.
 *
 * **Navigation Flow:**
 * - Start: Balance screen (default)
 * - Balance → Send: Click "Send" button
 * - Send → Balance: Navigate back after transaction
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun MainActivity() {
 *     KuiraTheme {
 *         AppNavigation()
 *     }
 * }
 * ```
 */
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
            Box(modifier = Modifier.fillMaxSize()) {
                BalanceScreen(
                    onNavigateToSend = { address ->
                        navController.navigate(Screen.Send.createRoute(address))
                    },
                    onNavigateToDust = { address ->
                        navController.navigate(Screen.Dust.createRoute(address))
                    }
                )

                // Temporary: prototype launcher
                FloatingActionButton(
                    onClick = { navController.navigate(Screen.Prototype.route) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(48.dp),
                    shape = CircleShape,
                    containerColor = Color.Black,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp),
                ) {
                    Text("\u2726", fontSize = 18.sp) // ✦ star symbol
                }
            }
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
            android.util.Log.d("AppNavigation", "SendScreen composable - extracted address: '$address'")
            SendScreen(address = address)
        }

        // Prototype — temporary, remove after design review
        composable(route = Screen.Prototype.route) {
            MidnightEntrance()
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
