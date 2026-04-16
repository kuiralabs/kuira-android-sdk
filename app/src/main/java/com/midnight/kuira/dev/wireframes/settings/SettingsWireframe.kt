package com.midnight.kuira.dev.wireframes.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.feature.balance.redesign.DuskPalette

@Composable
fun SettingsWireframe(
    devModeUnlocked: Boolean = false,
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
        // Ambient star background — same palette-aware rule as Balance.
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
            TopBar(palette = palette, onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                NetworkSection(palette = palette, devMode = devModeUnlocked)
                Spacer(modifier = Modifier.height(32.dp))

                if (devModeUnlocked) {
                    DeveloperOptionsSection(palette = palette)
                    Spacer(modifier = Modifier.height(32.dp))
                }

                SecuritySection(palette = palette)
                Spacer(modifier = Modifier.height(32.dp))

                AboutSection(palette = palette)
            }
        }
    }
}

@Composable
private fun TopBar(palette: DuskPalette, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to balance",
            tint = palette.Light,
            modifier = Modifier
                .size(24.dp)
                .clickable { onBack() },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "Settings",
            color = palette.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
        )
    }
    HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)
}

@Composable
private fun NetworkSection(palette: DuskPalette, devMode: Boolean) {
    SettingsSection(label = "NETWORK", palette = palette) {
        GlassPanel(
            tint = palette.contentPanel,
            border = palette.LightFaint,
            contentPadding = 0.dp,
        ) {
            SettingsRow(
                leadingIcon = Icons.Filled.Language,
                label = "Network",
                rightValue = "Preprod",
                readOnly = !devMode,
                palette = palette,
            )
            SettingsDivider(palette = palette)
            SettingsRow(
                leadingIcon = Icons.Filled.Sync,
                label = "Last sync",
                rightValue = "12s ago",
                readOnly = true,
                palette = palette,
            )
        }
    }
}

@Composable
private fun DeveloperOptionsSection(palette: DuskPalette) {
    SettingsSection(label = "DEVELOPER OPTIONS", palette = palette) {
        GlassPanel(
            tint = palette.contentPanel,
            border = palette.LightFaint,
            contentPadding = 0.dp,
        ) {
            SettingsRow(
                label = "Proof server",
                rightValue = "Default (local)",
                readOnly = true,
                palette = palette,
            )
            SettingsDivider(palette = palette)
            DangerRow(
                label = "Force re-sync",
                leadingIcon = Icons.Filled.Sync,
                palette = palette,
                onClick = { /* opens ConfirmationSheet */ },
            )
            SettingsDivider(palette = palette)
            SettingsRow(
                label = "Build info",
                rightValue = "debug · abc123",
                readOnly = true,
                palette = palette,
            )
        }
    }
}

@Composable
private fun SecuritySection(palette: DuskPalette) {
    SettingsSection(label = "SECURITY", palette = palette) {
        GlassPanel(
            tint = palette.contentPanel,
            border = palette.LightFaint,
            contentPadding = 0.dp,
        ) {
            SettingsRow(
                leadingIcon = Icons.Filled.Key,
                label = "View recovery phrase",
                palette = palette,
            )
            SettingsDivider(palette = palette)
            SettingsRow(
                leadingIcon = Icons.Filled.Fingerprint,
                label = "Test biometric",
                palette = palette,
            )
            SettingsDivider(palette = palette)
            DangerRow(
                label = "Wipe wallet",
                leadingIcon = Icons.Filled.DeleteForever,
                palette = palette,
                onClick = { /* opens ConfirmationSheet with WIPE challenge */ },
            )
        }
    }
}

@Composable
private fun AboutSection(palette: DuskPalette) {
    SettingsSection(label = "ABOUT", palette = palette) {
        GlassPanel(
            tint = palette.contentPanel,
            border = palette.LightFaint,
            contentPadding = 0.dp,
        ) {
            SettingsRow(
                label = "Version",
                rightValue = "1.0.0",
                readOnly = true,
                palette = palette,
            )
            SettingsDivider(palette = palette)
            SettingsRow(
                label = "Commit",
                rightValue = "abc12345",
                rightValueMono = true,
                readOnly = true,
                palette = palette,
            )
            SettingsDivider(palette = palette)
            SettingsRow(label = "License", palette = palette)
            SettingsDivider(palette = palette)
            SettingsRow(label = "GitHub", palette = palette)
            SettingsDivider(palette = palette)
            SettingsRow(label = "Support", palette = palette)
        }
    }
}
