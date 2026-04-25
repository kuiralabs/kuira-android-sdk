package com.midnight.kuira

import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import com.midnight.kuira.feature.onboarding.WalletGate
import com.midnight.kuira.navigation.AppNavigation
import com.midnight.kuira.service.ConnectorService
import com.midnight.kuira.core.designsystem.theme.KuiraTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main entry point for Kuira. Hosts the WalletGate → AppNavigation composition.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ConnectorService.start(this)
        setContent {
            KuiraTheme {
                // Gate the whole app behind the onboarding flow. On first launch
                // (or after a wallet wipe), WalletGate shows OnboardingScreen
                // until the user forges a sigil. Once a seed is persisted, the
                // content slot renders the 4-tab navigation.
                WalletGate(activity = this@MainActivity) { onWalletWiped ->
                    AppNavigation(onWalletWiped = onWalletWiped)
                }
            }
        }
    }
}
