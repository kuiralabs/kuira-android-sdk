package com.midnight.kuira.feature.balance.redesign

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors

enum class WireframeState { DEFAULT, LOADING_FIRST, SYNCING, ERROR, OFFLINE }

@Composable
fun BalanceWireframe(
    state: WireframeState = WireframeState.DEFAULT,
    showBackupBanner: Boolean = true,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightColors.Void),
    ) {
        // Ambient star background
        StarField(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            // Top bar
            TopBar()

            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 48.dp),
            ) {
                // Backup banner
                if (showBackupBanner) {
                    Spacer(modifier = Modifier.height(16.dp))
                    BackupBanner()
                    Spacer(modifier = Modifier.height(24.dp))
                } else {
                    Spacer(modifier = Modifier.height(32.dp))
                }

                // Hero
                BalanceHero(state = state)

                Spacer(modifier = Modifier.height(32.dp))

                // Secondary tokens
                val isLoading = state == WireframeState.LOADING_FIRST
                TokenRow(
                    label = "DUST",
                    value = dustValue(state),
                    denomination = "DUST",
                    isLoading = isLoading,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TokenRow(
                    label = "SHIELDED",
                    value = null,
                    denomination = null,
                    lockedText = if (isLoading) null else "locked — tap to unlock",
                    isLoading = isLoading,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Quick actions
                QuickActionsRow()

                Spacer(modifier = Modifier.height(32.dp))

                // Address chip
                AddressChipSection()
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "KUIRA",
            color = MidnightColors.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
            letterSpacing = 3.sp,
        )
        Spacer(modifier = Modifier.weight(1f))

        // Network badge
        NetworkBadge(network = "PREPROD")
        Spacer(modifier = Modifier.width(12.dp))

        // Settings icon
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "Open settings",
            tint = MidnightColors.Light,
            modifier = Modifier.size(24.dp),
        )
    }
    HorizontalDivider(color = MidnightColors.LightFaint, thickness = 1.dp)
}

@Composable
private fun NetworkBadge(network: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9999.dp))
            .background(MidnightColors.LightBarely)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = network,
            color = MidnightColors.LightSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.W400,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun BackupBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MidnightColors.LightBarely)
            .clickable { }
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Key,
            contentDescription = null,
            tint = MidnightColors.Light,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Back up your recovery phrase",
            color = MidnightColors.Light,
            fontSize = 13.sp,
            fontWeight = FontWeight.W400,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MidnightColors.LightMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun BalanceHero(state: WireframeState) {
    // Label
    Text(
        text = "BALANCE",
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    // Headline (numeric)
    if (state == WireframeState.LOADING_FIRST) {
        ShimmerBlock(height = 48.dp, widthFraction = 0.6f)
    } else {
        Text(
            text = "1,234.567890",
            color = MidnightColors.Light,
            fontSize = 44.sp,
            fontWeight = FontWeight.W200,
            letterSpacing = (-1).sp,
            lineHeight = 48.sp,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))

    // Detail
    Text(
        text = detailText(state),
        color = if (state == WireframeState.DEFAULT || state == WireframeState.LOADING_FIRST)
            MidnightColors.LightMuted else MidnightColors.LightSoft,
        fontSize = 13.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 18.sp,
        modifier = Modifier.animateContentSize(),
    )

    // Error retry row
    if (state == WireframeState.ERROR) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Could not update balance",
                color = MidnightColors.LightMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Retry",
                color = MidnightColors.Light,
                fontSize = 13.sp,
                modifier = Modifier.clickable { },
            )
        }
    }
}

@Composable
private fun TokenRow(
    label: String,
    value: String?,
    denomination: String?,
    lockedText: String? = null,
    isLoading: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable { },
    ) {
        Text(
            text = "\u2022",
            color = MidnightColors.LightFaint,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = MidnightColors.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                color = MidnightColors.Light,
                fontSize = 14.sp,
                fontWeight = FontWeight.W300,
            )
            if (denomination != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = denomination,
                    color = MidnightColors.LightMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W400,
                )
            }
        } else if (isLoading) {
            ShimmerBlock(height = 16.dp, widthFraction = 0.35f)
        } else if (lockedText != null) {
            Text(
                text = lockedText,
                color = MidnightColors.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
            )
        }
    }
}

@Composable
private fun QuickActionsRow() {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth(),
    ) {
        QuickActionCircle(
            icon = Icons.Filled.ArrowUpward,
            label = "Send",
            contentDesc = "Send a transaction",
            onClick = { },
        )
        QuickActionCircle(
            icon = Icons.Filled.QrCode2,
            label = "Receive",
            contentDesc = "Open receive screen",
            onClick = { },
        )
    }
}

@Composable
private fun AddressChipSection() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Unshielded", "Shielded")
    val addresses = listOf("mn_add…f5a2", "shield…3f8e")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MidnightColors.LightBarely)
            .padding(4.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (index == selectedTab)
                            Modifier.background(MidnightColors.VoidElevated)
                        else Modifier
                    )
                    .clickable { selectedTab = index }
                    .padding(vertical = 12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = tab,
                        color = if (index == selectedTab) MidnightColors.Light
                        else MidnightColors.LightMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W400,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = addresses[index],
                        color = MidnightColors.Light,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W400,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun detailText(state: WireframeState): String = when (state) {
    WireframeState.DEFAULT -> "NIGHT \u00B7 Synced 12s ago"
    WireframeState.LOADING_FIRST -> "Loading\u2026"
    WireframeState.SYNCING -> "NIGHT \u00B7 Syncing\u2026"
    WireframeState.ERROR -> "NIGHT \u00B7 Sync failed"
    WireframeState.OFFLINE -> "NIGHT \u00B7 Offline \u00B7 showing cached"
}

private fun dustValue(state: WireframeState): String? = when (state) {
    WireframeState.LOADING_FIRST -> null
    else -> "98,765.432109876543"
}
