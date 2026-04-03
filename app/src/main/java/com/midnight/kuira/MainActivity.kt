package com.midnight.kuira

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkRepository
import com.midnight.kuira.navigation.AppNavigation
import com.midnight.kuira.service.ConnectorService
import com.midnight.kuira.ui.components.NetworkSelectorBar
import com.midnight.kuira.core.designsystem.theme.KuiraTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * Main entry point for Kuira Wallet.
 *
 * **Current Implementation:**
 * Phase 2F MVP - Balance viewing, send transactions, and network selection.
 *
 * **Navigation:**
 * - Balance Screen (default): View wallet balance
 * - Send Screen: Send NIGHT transactions
 *
 * **Network Selection:**
 * - Segmented button bar at top to switch between Preprod, Preview, Undeployed
 * - Network change requires app restart (Hilt singletons are created at startup)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var networkRepository: NetworkRepository

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ConnectorService.start(this)
        setContent {
            val selectedNetwork by networkRepository.selectedNetworkFlow.collectAsState(
                initial = MidnightNetwork.DEFAULT
            )
            val scope = rememberCoroutineScope()

            KuiraTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        NetworkSelectorBar(
                            selectedNetwork = selectedNetwork,
                            onNetworkSelected = { network ->
                                scope.launch {
                                    networkRepository.setSelectedNetwork(network)
                                    // Restart the app
                                    restartApp()
                                }
                            }
                        )
                        HorizontalDivider()
                        AppNavigation()
                    }
                }
            }
        }
    }

    /**
     * Restart the app by finishing the activity and killing the process.
     *
     * This is needed because Hilt singletons (IndexerClient, NodeRpcClient, etc.)
     * are created at app startup with network-specific URLs. To switch networks,
     * we need to restart the app so these singletons are recreated.
     */
    private fun restartApp() {
        finishAffinity()
        exitProcess(0)
    }
}
