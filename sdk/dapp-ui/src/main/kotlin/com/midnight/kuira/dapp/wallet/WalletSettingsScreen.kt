package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.midnight.kuira.core.designsystem.effect.StarField

/**
 * Settings panel (#252) — the SDK port of the design-sprint `SettingsWireframe`. Consolidates the
 * vital sections behind one entry so the pill sheet stays lean, and gives the recovery phrase a
 * deliberately hidden home under SECURITY. Themed via [SendPalette]; StarField + GlassPanel match
 * the rest of the wallet surface.
 *
 * Stateless: every action is a callback, and BACKUP is a content slot the host fills with its own
 * `BackupSection`, so this screen needn't re-thread that section's parameters. A dApp can present
 * its own settings instead — these are the SDK's recommended building blocks, not the only path.
 */
@Composable
internal fun WalletSettingsScreen(
    networkLabel: String,
    syncLabel: String,
    recoveryPhraseSaved: Boolean,
    onViewRecoveryPhrase: () -> Unit,
    onLockNow: () -> Unit,
    onSignOut: () -> Unit,
    // Host-supplied app version; null hides the ABOUT section rather than show a fabricated value.
    versionLabel: String? = null,
    colors: WalletPanelColors = WalletPanelColors.Default,
    // Appearance: the pickable themes + current selection. Selecting one re-themes the whole panel.
    themes: List<WalletTheme> = WalletThemes.all,
    selectedThemeId: String = WalletThemes.Default.id,
    onSelectTheme: (String) -> Unit = {},
    backup: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
) {
    val palette = remember(colors) { SendPalette.from(colors) }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.text,
            alpha = if (palette.isLight) STAR_ALPHA_LIGHT else STAR_ALPHA_DARK,
            starCount = STAR_COUNT,
        )

        Column(Modifier.fillMaxSize().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())) {
            RecoveryTopBar(title = "Settings", palette = palette, onBack = onBack)

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SendDimens.PanelPadding)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SendDimens.Space24),
            ) {
                Spacer(Modifier.height(SendDimens.Space16))

                SettingsSection("NETWORK", palette) {
                    SettingsRow(
                        label = "Network",
                        palette = palette,
                        leadingGlyph = { GlobeGlyph(it) },
                        rightValue = networkLabel,
                        readOnly = true,
                    )
                    SettingsDivider(palette)
                    SettingsRow(label = "Sync", palette = palette, rightValue = syncLabel, readOnly = true)
                }
                Spacer(Modifier.height(SendDimens.Space32))

                SettingsSection("APPEARANCE", palette) {
                    ThemeSwatchRow(
                        themes = themes,
                        selectedId = selectedThemeId,
                        palette = palette,
                        onSelect = onSelectTheme,
                    )
                }
                Spacer(Modifier.height(SendDimens.Space32))

                SettingsSection("SECURITY", palette) {
                    SettingsRow(
                        label = "View recovery phrase",
                        palette = palette,
                        leadingGlyph = { KeyGlyph(it) },
                        rightValue = if (recoveryPhraseSaved) "Saved" else null,
                        onClick = onViewRecoveryPhrase,
                    )
                    SettingsDivider(palette)
                    SettingsRow(
                        label = "Lock now",
                        palette = palette,
                        leadingGlyph = { LockGlyph(it) },
                        onClick = onLockNow,
                    )
                    SettingsDivider(palette)
                    SettingsRow(
                        label = "Sign out",
                        palette = palette,
                        leadingGlyph = { SignOutGlyph(it) },
                        danger = true,
                        onClick = onSignOut,
                    )
                }
                Spacer(Modifier.height(SendDimens.Space32))

                if (backup != null) {
                    backup()
                    Spacer(Modifier.height(SendDimens.Space32))
                }

                if (versionLabel != null) {
                    SettingsSection("ABOUT", palette) {
                        SettingsRow(label = "Version", palette = palette, rightValue = versionLabel, readOnly = true)
                    }
                }
            }
        }
    }
}
