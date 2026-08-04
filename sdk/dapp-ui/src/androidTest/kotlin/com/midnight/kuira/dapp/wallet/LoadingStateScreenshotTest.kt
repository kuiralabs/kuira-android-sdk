package com.midnight.kuira.dapp.wallet

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dev look-and-feel aid (NOT a pass/fail assertion): renders the first-sync LOADING panel — now
 * carrying the shared [WalletSyncIndicator] runner + phase label + % — full-screen in a REAL
 * activity and HOLDS it visible with animations running, so a host-side `adb screencap` captures
 * the true render (the Lottie Rarámuri runner included). The Android analog of the iOS
 * `KUIRA_DEMO_FORCE_LOADING` capture. Driven by `scripts/ui-screenshots.sh`.
 *
 * Why a plain [ActivityScenario] + `setContent`, not a ComposeTestRule: the rule's controlled test
 * clock never lets the async Lottie composition resolve (the runner paints blank), and
 * `captureToImage()` stalls on the infinite shimmer/dust animations. A real activity uses the real
 * Compose frame clock + dispatchers, so Lottie loads and the runner strides for real.
 */
@RunWith(AndroidJUnit4::class)
class LoadingStateScreenshotTest {

    @Test
    fun captureLoadingState() {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            val colors = WalletPanelColors.Default
            activity.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.sheetBackground)
                        .padding(20.dp),
                ) {
                    WalletBalanceLoading(
                        colors = colors,
                        syncProgress = WalletSyncProgress(fraction = 0.42f, label = "Syncing dust"),
                        stage = "Syncing dust",
                    )
                }
            }
        }
        // Hold visible with animations LIVE for the host to `adb screencap`. This runs on the
        // instrumentation thread; the UI thread keeps rendering frames throughout.
        Thread.sleep(HOLD_MS)
        scenario.close()
    }

    private companion object {
        const val HOLD_MS = 12000L
    }
}
