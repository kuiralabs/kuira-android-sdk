package com.midnight.kuira.dapp.wallet

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dev look-and-feel aid (NOT a pass/fail assertion): renders the Send "select token" step full-screen
 * in a REAL activity and HOLDS it so a host-side `adb screencap` captures the true render — the Midnight
 * brand symbol on each token avatar + the eye/lock privacy badge + the generic "NIGHT" rows. The Android
 * analog of the iOS forced-overlay `ui-shots`. Uses a plain [ActivityScenario] + `setContent` (same
 * reasoning as [LoadingStateScreenshotTest]); the token step itself has no animation to stall on.
 */
@RunWith(AndroidJUnit4::class)
class TokenModeScreenshotTest {

    @Test
    fun captureTokenStep() {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            val palette = SendPalette.from(WalletPanelColors.Default)
            activity.setContent {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(palette.bg),
                ) {
                    TokenModeStep(
                        palette = palette,
                        availableNight = "1,234",
                        onBack = {},
                        onPickUnshielded = {},
                    )
                }
            }
        }
        Thread.sleep(HOLD_MS)
        scenario.close()
    }

    private companion object {
        const val HOLD_MS = 9000L
    }
}
