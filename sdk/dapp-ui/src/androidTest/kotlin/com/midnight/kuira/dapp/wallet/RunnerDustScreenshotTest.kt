package com.midnight.kuira.dapp.wallet

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.dapp.backup.RunnerDustProgress
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dev look-and-feel aid (NOT a pass/fail): renders [RunnerDustProgress] at the INDETERMINATE
 * (progress=null → runner parked at the far-left start) case that the "Registering for dust
 * generation…" balance-card state uses, plus a low determinate frac. Proves the dust trail kicks
 * up BEHIND the runner (to its left), not in front. Held so a host `adb screencap` catches it.
 */
@RunWith(AndroidJUnit4::class)
class RunnerDustScreenshotTest {
    @Test
    fun captureRunnerDust() {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { a ->
            a.setContent {
                Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0B)).padding(24.dp)) {
                    Text("indeterminate (progress = null) — the register/sync case", color = Color(0xFFAAAAAA))
                    Spacer(Modifier.height(8.dp))
                    RunnerDustProgress(progress = null, colors = WalletPanelColors.Default, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(48.dp))
                    Text("determinate 15%", color = Color(0xFFAAAAAA))
                    Spacer(Modifier.height(8.dp))
                    RunnerDustProgress(progress = 0.15f, colors = WalletPanelColors.Default, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Thread.sleep(9000)
        scenario.close()
    }
}
