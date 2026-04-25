package com.midnight.kuira.navigation

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.midnight.kuira.dev.DevPortalScreen
import com.midnight.kuira.dev.wireframes.dust.DustWireframeWithDevControls
import com.midnight.kuira.dev.wireframes.onboarding.OnboardingWireframeWithDevControls
import com.midnight.kuira.dev.wireframes.history.TxHistoryWireframeWithDevControls
import com.midnight.kuira.dev.wireframes.receive.ReceiveWireframeWithDevControls
import com.midnight.kuira.dev.wireframes.recovery.RecoveryPhraseWireframeWithDevControls
import com.midnight.kuira.dev.wireframes.send.SendConfirmationWireframeWithDevControls
import com.midnight.kuira.dev.wireframes.send.SendWireframeWithDevControls
import com.midnight.kuira.dev.wireframes.settings.SettingsWireframeWithDevControls
import com.midnight.kuira.feature.balance.BalanceScreen
import com.midnight.kuira.feature.balance.redesign.BalanceWireframeWithDevControls
import com.midnight.kuira.feature.dust.DustScreen
import com.midnight.kuira.feature.send.SendMode
import com.midnight.kuira.feature.send.SendScreen
import com.midnight.kuira.dev.BalanceWithDevPortalFab
import com.midnight.kuira.feature.settings.SettingsScreen
import com.midnight.kuira.placeholder.ActivityPlaceholderScreen
import com.midnight.kuira.placeholder.MySigilPlaceholderScreen

/**
 * Route definitions for all screens in Kuira.
 *
 * Screens are organized into 4 tab graphs: My Sigil (home), Assets,
 * Activity, and Settings. Dev portal routes live outside the tab graphs.
 */
sealed class Screen(val route: String) {
    // Tab graph routes (containers for nested screens)
    data object MySigilGraph : Screen("my-sigil-graph")
    data object AssetsGraph : Screen("assets-graph")
    data object ActivityGraph : Screen("activity-graph")
    data object SettingsGraph : Screen("settings-graph")

    // Tab root screens
    data object MySigil : Screen("my-sigil")
    data object Balance : Screen("balance")
    data object Activity : Screen("activity")
    data object Settings : Screen("settings")

    // Assets tab sub-screens
    data object Send : Screen("send?mode={mode}") {
        fun createRoute(mode: SendMode = SendMode.UNSHIELDED) =
            "send?mode=${mode.name.lowercase()}"
    }

    data object Dust : Screen("dust/{address}") {
        fun createRoute(address: String) = "dust/$address"
    }

    // Dev portal (outside tabs — remove after 8B.1)
    data object DevPortal : Screen("dev-portal")
    data object BalanceWireframe : Screen("balance-wireframe")
    data object SettingsWireframe : Screen("settings-wireframe")
    data object SendWireframe : Screen("send-wireframe")
    data object SendConfirmationWireframe : Screen("send-confirmation-wireframe")
    data object DustWireframe : Screen("dust-wireframe")
    data object TxHistoryWireframe : Screen("tx-history-wireframe")
    data object ReceiveWireframe : Screen("receive-wireframe")
    data object RecoveryPhraseWireframe : Screen("recovery-phrase-wireframe")
    data object OnboardingWireframe : Screen("onboarding-wireframe")
}

/** Routes where the bottom nav bar is visible (tab roots only). */
private val TAB_ROOT_ROUTES = setOf(
    Screen.MySigil.route,
    Screen.Balance.route,
    Screen.Activity.route,
    Screen.Settings.route,
)

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    onWalletWiped: () -> Unit = {},
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentGraphRoute = navBackStackEntry?.destination?.parent?.route
    val showBottomBar = currentRoute in TAB_ROOT_ROUTES

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.MySigilGraph.route,
            // Reserve space for bottom bar on tab root screens so content
            // doesn't get clipped. Sub-screens use the full height.
            modifier = if (showBottomBar) {
                Modifier.padding(bottom = BottomNavBarContentHeight)
            } else {
                Modifier
            },
        ) {
            // ================================================================
            // Tab 1: My Sigil (home)
            // ================================================================
            navigation(
                route = Screen.MySigilGraph.route,
                startDestination = Screen.MySigil.route,
            ) {
                composable(Screen.MySigil.route) {
                    MySigilPlaceholderScreen()
                }
            }

            // ================================================================
            // Tab 2: Assets (Balance + Send + Dust)
            // ================================================================
            navigation(
                route = Screen.AssetsGraph.route,
                startDestination = Screen.Balance.route,
            ) {
                composable(Screen.Balance.route) {
                    BalanceWithDevPortalFab(
                        onOpenDevPortal = { navController.navigate(Screen.DevPortal.route) },
                    ) {
                        BalanceScreen(
                            onNavigateToSend = {
                                navController.navigate(Screen.Send.createRoute())
                            },
                            onNavigateToDust = { address ->
                                navController.navigate(Screen.Dust.createRoute(address))
                            },
                        )
                    }
                }

                // Send screen — mode-driven
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

                // Dust screen
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

            // ================================================================
            // Tab 3: Activity
            // ================================================================
            navigation(
                route = Screen.ActivityGraph.route,
                startDestination = Screen.Activity.route,
            ) {
                composable(Screen.Activity.route) {
                    ActivityPlaceholderScreen()
                }
            }

            // ================================================================
            // Tab 4: Settings
            // ================================================================
            navigation(
                route = Screen.SettingsGraph.route,
                startDestination = Screen.Settings.route,
            ) {
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onNavigateToRecoveryPhrase = {
                            // TODO: navigate to Screen.RecoveryPhrase when built
                        },
                        onWipeComplete = { onWalletWiped() },
                    )
                }
            }

            // ================================================================
            // Dev portal (outside tabs — remove after 8B.1)
            // ================================================================
            composable(route = Screen.DevPortal.route) {
                DevPortalScreen(
                    onOpenWireframe = { route -> navController.navigate(route) },
                )
            }

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

            composable(route = Screen.SettingsWireframe.route) {
                SettingsWireframeWithDevControls(
                    onBack = { navController.popBackStack() },
                    onOpenWireframeList = {
                        navController.navigate(Screen.DevPortal.route) {
                            popUpTo(Screen.DevPortal.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(route = Screen.SendWireframe.route) {
                SendWireframeWithDevControls(
                    onBack = { navController.popBackStack() },
                    onOpenWireframeList = {
                        navController.navigate(Screen.DevPortal.route) {
                            popUpTo(Screen.DevPortal.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(route = Screen.SendConfirmationWireframe.route) {
                SendConfirmationWireframeWithDevControls(
                    onBack = { navController.popBackStack() },
                    onOpenWireframeList = {
                        navController.navigate(Screen.DevPortal.route) {
                            popUpTo(Screen.DevPortal.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(route = Screen.DustWireframe.route) {
                DustWireframeWithDevControls(
                    onBack = { navController.popBackStack() },
                    onOpenWireframeList = {
                        navController.navigate(Screen.DevPortal.route) {
                            popUpTo(Screen.DevPortal.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(route = Screen.TxHistoryWireframe.route) {
                TxHistoryWireframeWithDevControls(
                    onBack = { navController.popBackStack() },
                    onOpenWireframeList = {
                        navController.navigate(Screen.DevPortal.route) {
                            popUpTo(Screen.DevPortal.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(route = Screen.ReceiveWireframe.route) {
                ReceiveWireframeWithDevControls(
                    onBack = { navController.popBackStack() },
                    onOpenWireframeList = {
                        navController.navigate(Screen.DevPortal.route) {
                            popUpTo(Screen.DevPortal.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(route = Screen.RecoveryPhraseWireframe.route) {
                RecoveryPhraseWireframeWithDevControls(
                    onBack = { navController.popBackStack() },
                    onOpenWireframeList = {
                        navController.navigate(Screen.DevPortal.route) {
                            popUpTo(Screen.DevPortal.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(route = Screen.OnboardingWireframe.route) {
                OnboardingWireframeWithDevControls(
                    onBack = { navController.popBackStack() },
                    onOpenWireframeList = {
                        navController.navigate(Screen.DevPortal.route) {
                            popUpTo(Screen.DevPortal.route) { inclusive = true }
                        }
                    },
                )
            }
        }

        // Bottom navigation bar — visible on tab root screens only
        if (showBottomBar) {
            KuiraBottomNavBar(
                currentGraphRoute = currentGraphRoute,
                onTabSelected = { tab ->
                    navController.navigate(tab.graphRoute) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
