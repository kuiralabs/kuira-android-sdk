package com.midnight.kuira.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/** Content height of the tab row (excludes system nav inset). */
val BottomNavBarContentHeight: Dp = 56.dp

/**
 * Kuira bottom navigation bar — 4 tabs matching the Dusk design system.
 *
 * Spec: 56dp content height + system nav inset, VoidSoft bg,
 * 1dp LightFaint top border, active icon/label = Light (100%),
 * inactive = LightMuted (50%).
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
    val systemNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(modifier = modifier.fillMaxWidth()) {
        // 1dp LightFaint top border
        HorizontalDivider(
            thickness = 1.dp,
            color = MidnightColors.LightFaint,
        )

        // Tab row: 56dp content + system nav inset below
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MidnightColors.VoidSoft)
                .height(BottomNavBarContentHeight + systemNavInset)
                .padding(bottom = systemNavInset),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KuiraTab.entries.forEach { tab ->
                val selected = currentGraphRoute == tab.graphRoute
                val tint = if (selected) MidnightColors.Light else MidnightColors.LightMuted

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onTabSelected(tab) },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = tab.label,
                            color = tint,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.W400 else FontWeight.W300,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
