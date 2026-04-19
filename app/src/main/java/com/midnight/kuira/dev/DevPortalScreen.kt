package com.midnight.kuira.dev

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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Dev portal — index of all wireframes under active design in 8B.1.
 *
 * Add a new entry to [wireframes] each time a screen prompt gets a
 * Compose wireframe. Route name must match a composable in AppNavigation.
 */
enum class WireframeStatus(val label: String, val icon: String) {
    PENDING("Pending", "\uD83D\uDCDD"),
    DRAFTING("Drafting", "\uD83C\uDFA8"),
    APPROVED("Approved", "\u2705"),
}

data class WireframeEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: WireframeStatus,
    val route: String?, // null until wireframe exists
)

private val wireframes = listOf(
    WireframeEntry(
        id = "01",
        title = "Balance",
        subtitle = "Home · NIGHT + DUST + shielded balances · Phantom-style actions",
        status = WireframeStatus.DRAFTING,
        route = "balance-wireframe",
    ),
    WireframeEntry(
        id = "02",
        title = "Send",
        subtitle = "Compose a transaction · address + amount + review",
        status = WireframeStatus.DRAFTING,
        route = "send-wireframe",
    ),
    WireframeEntry(
        id = "03",
        title = "Send Confirmation",
        subtitle = "Last gate before biometric sign",
        status = WireframeStatus.DRAFTING,
        route = "send-confirmation-wireframe",
    ),
    WireframeEntry(
        id = "04",
        title = "Dust",
        subtitle = "Balance + generation rate · registration CTA",
        status = WireframeStatus.DRAFTING,
        route = "dust-wireframe",
    ),
    WireframeEntry(
        id = "05",
        title = "Settings",
        subtitle = "Network · Security · About · Developer options (hidden)",
        status = WireframeStatus.DRAFTING,
        route = "settings-wireframe",
    ),
    WireframeEntry(
        id = "06",
        title = "Tx History + Detail",
        subtitle = "Day-grouped activity list · full detail drill-in",
        status = WireframeStatus.DRAFTING,
        route = "tx-history-wireframe",
    ),
    WireframeEntry(
        id = "07",
        title = "Receive",
        subtitle = "midnight: URI QR · tabs · full-screen mode",
        status = WireframeStatus.DRAFTING,
        route = "receive-wireframe",
    ),
    WireframeEntry(
        id = "08",
        title = "Recovery phrase view",
        subtitle = "24-word grid · FLAG_SECURE · biometric-gated",
        status = WireframeStatus.DRAFTING,
        route = "recovery-phrase-wireframe",
    ),
    WireframeEntry(
        id = "09",
        title = "Onboarding visual pass",
        subtitle = "Token audit of existing onboarding screens",
        status = WireframeStatus.PENDING,
        route = null,
    ),
    WireframeEntry(
        id = "10",
        title = "App icon",
        subtitle = "Pure-symbol mark concepts",
        status = WireframeStatus.PENDING,
        route = null,
    ),
    WireframeEntry(
        id = "11",
        title = "Splash animation",
        subtitle = "≤ 800ms intro · MaterializeEffect → Balance",
        status = WireframeStatus.PENDING,
        route = null,
    ),
)

@Composable
fun DevPortalScreen(onOpenWireframe: (route: String) -> Unit) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightColors.Void),
    ) {
        StarField(modifier = Modifier.fillMaxSize(), starCount = 60)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "DEV PORTAL",
                color = MidnightColors.LightMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.W400,
                letterSpacing = 3.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Wireframes",
                color = MidnightColors.Light,
                fontSize = 22.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 28.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Design preview screens for 8B.1. Tap a Drafting or Approved " +
                    "row to open the live wireframe.",
                color = MidnightColors.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(32.dp))

            wireframes.forEach { entry ->
                WireframeRow(
                    entry = entry,
                    onClick = entry.route?.let { route -> { onOpenWireframe(route) } },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun WireframeRow(
    entry: WireframeEntry,
    onClick: (() -> Unit)?,
) {
    val enabled = onClick != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MidnightColors.LightBarely)
            .then(if (enabled) Modifier.clickable(onClick = onClick!!) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = entry.id,
            color = MidnightColors.LightFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.W400,
            letterSpacing = 2.sp,
            modifier = Modifier.width(24.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = if (enabled) MidnightColors.Light else MidnightColors.LightMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.W400,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.subtitle,
                color = MidnightColors.LightMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 16.sp,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        StatusPill(status = entry.status)

        Spacer(modifier = Modifier.width(12.dp))

        if (enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MidnightColors.LightMuted,
                modifier = Modifier.width(16.dp),
            )
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}

@Composable
private fun StatusPill(status: WireframeStatus) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(9999.dp))
            .background(MidnightColors.VoidElevated)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = status.icon, fontSize = 10.sp)
        Text(
            text = status.label,
            color = MidnightColors.LightSoft,
            fontSize = 10.sp,
            fontWeight = FontWeight.W400,
            letterSpacing = 2.sp,
        )
    }
}
