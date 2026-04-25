package com.midnight.kuira.navigation

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.DuskTypography
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/** Content height of the bottom nav bar. */
private val BottomNavBarContentHeight: Dp = 80.dp

/**
 * Kuira bottom navigation bar — 4 tabs matching the Dusk design system.
 *
 * Uses Material3 [NavigationBar] + [NavigationBarItem] for proper touch
 * targets and accessibility, styled with Dusk palette colors.
 *
 * Visible on tab root screens only. Hidden when navigating into
 * sub-screens (Send, Tx Detail, etc.).
 */
@Composable
fun KuiraBottomNavBar(
    currentGraphRoute: String?,
    onTabSelected: (KuiraTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        containerColor = MidnightColors.VoidSoft,
        contentColor = MidnightColors.Light,
        modifier = modifier.height(BottomNavBarContentHeight),
    ) {
        KuiraTab.entries.forEach { tab ->
            val selected = currentGraphRoute == tab.graphRoute

            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = DuskTypography.labelTiny.copy(letterSpacing = 0.sp),
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MidnightColors.Light,
                    selectedTextColor = MidnightColors.Light,
                    unselectedIconColor = MidnightColors.LightMuted,
                    unselectedTextColor = MidnightColors.LightMuted,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
