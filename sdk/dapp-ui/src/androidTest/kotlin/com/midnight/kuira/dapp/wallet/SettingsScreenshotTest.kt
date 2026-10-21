package com.midnight.kuira.dapp.wallet

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dev look-and-feel aid (NOT a pass/fail assertion): the settings screen, held
 * on a real activity so `adb screencap` catches the true render.
 *
 * It exists because the only way to reach this screen in a host app is through
 * the session-lock credential prompt, which cannot be driven from a script and
 * should not be. A slot's spacing was wrong for as long as it took someone to
 * unlock a phone and notice — the NETWORK & PROVING card sat 4dp from its edge
 * while every row around it sat at 20dp. Rendering it directly makes that
 * measurable instead of anecdotal.
 *
 * Both palettes, because the config slot is themed by `WalletPanelColors` while
 * the card around it is themed by `SendPalette`, and a light background is where
 * two palettes disagreeing stops being invisible.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenshotTest {
    @Test
    fun captureSettingsDark() = capture(WalletThemes.Default)

    @Test
    fun captureSettingsLight() = capture(WalletThemes.KuiraLight)

    /**
     * Takes the theme, not its id.
     *
     * The first version of this passed `selectedThemeId` alone and captured a
     * dark screen labelled "light": the screen derives its palette from
     * [WalletPanelColors], and the id only rings a swatch. A harness that can
     * report the wrong answer confidently is worse than none.
     */
    private fun capture(theme: WalletTheme) {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                WalletSettingsScreen(
                    networkLabel = "Localnet",
                    syncLabel = "Synced",
                    recoveryPhraseSaved = true,
                    onViewRecoveryPhrase = {},
                    onResyncBalance = {},
                    onLockNow = {},
                    onSignOut = {},
                    versionLabel = "0.1.0-alpha07",
                    colors = theme.colors,
                    selectedThemeId = theme.id,
                    config = {
                        ConfigRow(
                            network = MidnightNetwork.UNDEPLOYED,
                            provingMode = ProvingMode.LOCAL,
                            proofServerUrl = null,
                            colors = theme.colors,
                            onNetworkChange = {},
                            onProvingModeChange = {},
                            onProofServerUrlChange = {},
                        )
                    },
                    onBack = {},
                )
            }
        }
        Thread.sleep(HOLD_MS)
        scenario.close()
    }

    private companion object {
        const val HOLD_MS = 25_000L
    }
}
