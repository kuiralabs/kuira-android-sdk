package com.midnight.kuira.dev.wireframes.send

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.dev.wireframes.settings.SettingsDivider
import com.midnight.kuira.dev.wireframes.settings.SettingsRow
import com.midnight.kuira.dev.wireframes.settings.SettingsSectionHeader
import com.midnight.kuira.feature.balance.redesign.DuskPalette

enum class ConfirmationState {
    DEFAULT,
    PENDING_BUILDING,
    PENDING_SIGNING,
    PENDING_PROVING,
    PENDING_SUBMITTING,
    PENDING_SYNCING,
    SUCCESS,
    ERROR,
    OFFLINE,
}

private data class StepCopy(val label: String, val detail: String)

private fun stepCopy(state: ConfirmationState): StepCopy? = when (state) {
    ConfirmationState.PENDING_BUILDING ->
        StepCopy("Building transaction", "Preparing inputs\u2026")
    ConfirmationState.PENDING_SIGNING ->
        StepCopy("Signing", "Signing with device key\u2026")
    ConfirmationState.PENDING_PROVING ->
        StepCopy("Generating proof", "This can take a few minutes on the first shielded send.")
    ConfirmationState.PENDING_SUBMITTING ->
        StepCopy("Submitting", "Broadcasting to the network\u2026")
    ConfirmationState.PENDING_SYNCING ->
        StepCopy("Re-syncing", "Refreshing wallet state and retrying\u2026 (attempt 2)")
    else -> null
}

private fun ConfirmationState.isPending(): Boolean = stepCopy(this) != null

@Composable
fun SendConfirmationWireframe(
    state: ConfirmationState = ConfirmationState.DEFAULT,
    palette: DuskPalette = DuskPalette.DarkMode,
    onBack: () -> Unit = {},
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            TopBar(state = state, palette = palette, onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
            ) {
                when {
                    state.isPending() -> PendingContent(state = state, palette = palette)
                    state == ConfirmationState.SUCCESS -> SuccessContent(palette = palette)
                    else -> ReviewContent(state = state, palette = palette)
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: ConfirmationState, palette: DuskPalette, onBack: () -> Unit) {
    val backInteractive = !state.isPending()
    val title = if (state == ConfirmationState.SUCCESS) "Sent" else "Review"
    val leading = if (state == ConfirmationState.SUCCESS) "Done" else null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        if (leading != null) {
            Text(
                text = leading,
                color = palette.Light,
                fontSize = 14.sp,
                fontWeight = FontWeight.W300,
                modifier = Modifier.clickable { onBack() },
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = if (backInteractive) "Back to amount" else null,
                tint = if (backInteractive) palette.Light else palette.LightMuted,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(enabled = backInteractive) { onBack() },
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            color = palette.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
        )
    }
    HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)
}

// ── Receipt Review (default, error, offline) ──

@Composable
private fun ReviewContent(state: ConfirmationState, palette: DuskPalette) {
    Spacer(modifier = Modifier.height(32.dp))

    // Receipt hero — amount centered, star-protected
    GlassPanel(
        tint = palette.contentPanel,
        border = palette.LightFaint,
        contentPadding = 24.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "5.5",
                color = palette.Light,
                fontSize = 44.sp,
                fontWeight = FontWeight.W200,
                letterSpacing = (-1).sp,
                lineHeight = 48.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "NIGHT",
                color = palette.LightMuted,
                fontSize = 18.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Mode badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(palette.LightBarely)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "UNSHIELDED",
                    color = palette.LightSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W400,
                    letterSpacing = 3.sp,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    // Details card
    GlassPanel(
        tint = palette.contentPanel,
        border = palette.LightFaint,
        contentPadding = 0.dp,
    ) {
        SettingsRow(
            label = "To",
            rightValue = "mn_addr\u2026f5a2",
            rightValueMono = true,
            readOnly = true,
            trailingIcon = Icons.Filled.ContentCopy,
            palette = palette,
        )
        SettingsDivider(palette = palette)
        SettingsRow(
            label = "Network fee",
            rightValue = "\u2248 0.001 NIGHT",
            readOnly = true,
            palette = palette,
        )
        SettingsDivider(palette = palette)
        SettingsRow(
            label = "Total",
            rightValue = "5.501 NIGHT",
            readOnly = true,
            palette = palette,
        )
    }

    // Error / offline additions
    when (state) {
        ConfirmationState.ERROR -> {
            Spacer(modifier = Modifier.height(32.dp))
            ErrorCard(
                headline = "Submit failed",
                body = "Something went wrong. Network unreachable after 3 retries.",
                palette = palette,
            )
        }
        ConfirmationState.OFFLINE -> {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "You're offline. Connect to a network to send.",
                color = palette.LightMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 16.sp,
            )
        }
        else -> Unit
    }

    Spacer(modifier = Modifier.height(48.dp))

    val primaryText = if (state == ConfirmationState.ERROR) "Try Again" else "Confirm"
    val primaryEnabled = state != ConfirmationState.OFFLINE

    DuskButtonRowPaletted(
        secondaryText = "Cancel",
        primaryText = primaryText,
        onSecondary = { },
        onPrimary = { },
        palette = palette,
        primaryEnabled = primaryEnabled,
    )
}

// ── Pending (state machine) ──

@Composable
private fun PendingContent(state: ConfirmationState, palette: DuskPalette) {
    Spacer(modifier = Modifier.height(48.dp))

    val copy = stepCopy(state) ?: return
    GlassPanel(
        tint = palette.contentPanel,
        border = palette.LightFaint,
        contentPadding = 24.dp,
    ) {
        StepIndicator(
            stepLabel = copy.label,
            detailHint = copy.detail,
            palette = palette,
        )
    }
}

// ── Success ──

@Composable
private fun SuccessContent(palette: DuskPalette) {
    Spacer(modifier = Modifier.height(48.dp))

    // Success hero
    GlassPanel(
        tint = palette.contentPanel,
        border = palette.LightFaint,
        contentPadding = 24.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = palette.Light,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Transaction submitted",
                color = palette.Light,
                fontSize = 18.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "It may take a few minutes to confirm.",
                color = palette.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 18.sp,
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    SettingsSectionHeader(label = "TRANSACTION", palette = palette)
    Spacer(modifier = Modifier.size(12.dp))

    GlassPanel(
        tint = palette.contentPanel,
        border = palette.LightFaint,
        contentPadding = 0.dp,
    ) {
        SettingsRow(
            label = "Hash",
            rightValue = "abc12345\u2026def678",
            rightValueMono = true,
            readOnly = true,
            trailingIcon = Icons.Filled.ContentCopy,
            palette = palette,
        )
        SettingsDivider(palette = palette)
        SettingsRow(
            label = "View in history",
            palette = palette,
        )
    }

    Spacer(modifier = Modifier.height(48.dp))

    DuskPrimaryButtonPaletted(
        text = "Send another",
        onClick = { },
        palette = palette,
    )
}
