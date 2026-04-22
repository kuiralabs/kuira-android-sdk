package com.midnight.kuira.feature.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors

// Sizing tokens — mirrors DuskTokens from the wireframes.
// TODO: when DuskPalette moves to core:designsystem, import
//       DuskTokens directly instead of these local aliases.
private val TopBarHeight = 56.dp
private val RowMinHeight = 56.dp
private val Space4 = 4.dp
private val Space8 = 8.dp
private val Space12 = 12.dp
private val Space16 = 16.dp
private val Space24 = 24.dp
private val Space32 = 32.dp
private val Icon16 = 16.dp
private val Icon24 = 24.dp

/**
 * Production Settings screen. Mirrors the wireframe in
 * `05-settings.md` / `SettingsWireframe.kt` but wired to real data
 * via [SettingsViewModel].
 *
 * Uses [MidnightColors] (dark-mode only for v1.0). When DuskPalette
 * moves to `core:designsystem`, this screen will adopt it for
 * light-mode support.
 *
 * TODO (8B.3 follow-ups):
 * - ConfirmationSheet for wipe + force-resync
 * - NetworkPicker bottom sheet
 * - Proof server URL edit field (DuskInputField)
 * - Wire last-sync timestamp from SyncStateManager
 * - Promote DuskPalette + wireframe SettingsRow to core:designsystem
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToRecoveryPhrase: () -> Unit = {},
    onWipeComplete: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TopBarHeight)
                    .padding(horizontal = Space16),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to balance",
                    tint = MidnightColors.Light,
                    modifier = Modifier
                        .size(Icon24)
                        .clickable { onBack() },
                )
                Spacer(modifier = Modifier.width(Space16))
                Text(
                    text = "Settings",
                    color = MidnightColors.Light,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                )
            }
            HorizontalDivider(color = MidnightColors.LightFaint, thickness = 1.dp)

            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space16)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + Space24),
            ) {
                Spacer(modifier = Modifier.height(Space16))

                // ── NETWORK ──
                SectionHeader("NETWORK")
                Spacer(modifier = Modifier.height(Space12))
                GlassPanel(
                    tint = MidnightColors.VoidElevated,
                    border = MidnightColors.LightFaint,
                    contentPadding = 0.dp,
                ) {
                    SettingsRowItem(
                        leadingIcon = Icons.Filled.Language,
                        label = "Network",
                        value = uiState.network.name,
                        readOnly = !uiState.devModeUnlocked,
                        onClick = { /* TODO: open NetworkPicker sheet */ },
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        leadingIcon = Icons.Filled.Sync,
                        label = "Last sync",
                        value = uiState.lastSyncAgo,
                        readOnly = true,
                    )
                }

                Spacer(modifier = Modifier.height(Space32))

                // ── DEVELOPER OPTIONS (conditional) ──
                if (uiState.devModeUnlocked) {
                    SectionHeader("DEVELOPER OPTIONS")
                    Spacer(modifier = Modifier.height(Space12))
                    GlassPanel(
                        tint = MidnightColors.VoidElevated,
                        border = MidnightColors.LightFaint,
                        contentPadding = 0.dp,
                    ) {
                        SettingsRowItem(
                            label = "Proof server",
                            value = uiState.proofServerUrl,
                            onClick = { /* TODO: open proof server URL editor */ },
                        )
                        SettingsDividerItem()
                        DangerRowItem(
                            label = "Force re-sync",
                            leadingIcon = Icons.Filled.Sync,
                            onClick = { viewModel.onForceResync() },
                        )
                        SettingsDividerItem()
                        SettingsRowItem(
                            label = "Build info",
                            value = "${uiState.buildType} · ${uiState.commitHash}",
                            readOnly = true,
                        )
                    }
                    Spacer(modifier = Modifier.height(Space32))
                }

                // ── SECURITY ──
                SectionHeader("SECURITY")
                Spacer(modifier = Modifier.height(Space12))
                GlassPanel(
                    tint = MidnightColors.VoidElevated,
                    border = MidnightColors.LightFaint,
                    contentPadding = 0.dp,
                ) {
                    SettingsRowItem(
                        leadingIcon = Icons.Filled.Key,
                        label = "View recovery phrase",
                        onClick = onNavigateToRecoveryPhrase,
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        leadingIcon = Icons.Filled.Fingerprint,
                        label = "Test biometric",
                        onClick = { /* TODO: trigger biometric test + toast */ },
                    )
                    SettingsDividerItem()
                    DangerRowItem(
                        label = "Wipe wallet",
                        leadingIcon = Icons.Filled.DeleteForever,
                        onClick = {
                            // TODO: open ConfirmationSheet with "type WIPE" challenge
                            // On confirm:
                            //   viewModel.onWipeWallet()
                            //   onWipeComplete()
                        },
                    )
                }

                Spacer(modifier = Modifier.height(Space32))

                // ── ABOUT ──
                SectionHeader("ABOUT")
                Spacer(modifier = Modifier.height(Space12))
                GlassPanel(
                    tint = MidnightColors.VoidElevated,
                    border = MidnightColors.LightFaint,
                    contentPadding = 0.dp,
                ) {
                    // Version row: readOnly-looking but tappable for 7-tap unlock
                    SettingsRowItem(
                        label = "Version",
                        value = uiState.versionName,
                        readOnly = true,
                        alwaysClickable = true, // 7-tap dev-mode unlock
                        onClick = { viewModel.onVersionTapped() },
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        label = "Commit",
                        value = uiState.commitHash,
                        valueMono = true,
                        readOnly = true,
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        label = "License",
                        onClick = { /* TODO: open browser */ },
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        label = "GitHub",
                        onClick = { /* TODO: open browser */ },
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        label = "Support",
                        onClick = { /* TODO: open browser */ },
                    )
                }
            }
        }
    }
}

// ── Private composables ──

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 3.sp,
    )
}

/**
 * Settings row following the label/value emphasis rule:
 * - readOnly: label LightSoft (80%), value Light (100%) — data pops
 * - navigational: label Light (100%), value LightSoft (80%) — label pops
 *
 * [alwaysClickable] overrides readOnly's non-interactive behavior
 * (used for the Version row which looks readOnly but needs 7-tap).
 */
@Composable
private fun SettingsRowItem(
    label: String,
    value: String? = null,
    valueMono: Boolean = false,
    readOnly: Boolean = false,
    alwaysClickable: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    contentDesc: String? = null,
    onClick: () -> Unit = {},
) {
    val isClickable = !readOnly || alwaysClickable || trailingIcon != null
    val showChevron = !readOnly && trailingIcon == null
    val resolvedTrailing = trailingIcon
        ?: if (showChevron) Icons.AutoMirrored.Filled.ArrowForward else null

    // Label/value emphasis flip per spec
    val labelColor = if (readOnly) MidnightColors.LightSoft else MidnightColors.Light
    val valueColor = if (readOnly) MidnightColors.Light else MidnightColors.LightSoft

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .then(if (isClickable) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (contentDesc != null) Modifier.semantics { this.contentDescription = contentDesc }
                else Modifier,
            )
            .padding(horizontal = Space16, vertical = Space16),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MidnightColors.Light,
                modifier = Modifier.size(Icon24),
            )
            Spacer(modifier = Modifier.width(Space12))
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 20.sp,
                fontFamily = if (valueMono) FontFamily.Monospace else FontFamily.Default,
            )
        }
        if (resolvedTrailing != null) {
            Spacer(modifier = Modifier.width(Space8))
            Icon(
                imageVector = resolvedTrailing,
                contentDescription = null,
                tint = MidnightColors.LightMuted,
                modifier = Modifier.size(Icon16),
            )
        }
    }
}

@Composable
private fun DangerRowItem(
    label: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
) {
    SettingsRowItem(
        label = label,
        leadingIcon = leadingIcon,
        contentDesc = "Destructive action, $label",
        onClick = onClick,
    )
}

@Composable
private fun SettingsDividerItem() {
    HorizontalDivider(
        color = MidnightColors.LightFaint,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = Space16),
    )
}
