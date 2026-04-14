package com.midnight.kuira

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
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
import com.midnight.kuira.core.auth.AuthenticationCancelledException
import com.midnight.kuira.core.auth.AuthenticationFailedException
import com.midnight.kuira.core.auth.AuthenticationLockedOutException
import com.midnight.kuira.core.auth.AuthenticationPermanentlyLockedOutException
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletAddressCache
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkRepository
import com.midnight.kuira.feature.onboarding.WalletAddressDeriver
import com.midnight.kuira.feature.onboarding.WalletGate
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
 * **Navigation:**
 * - Onboarding (first launch) → Balance → Send / Dust / Settings
 *
 * **Network selection:**
 * The top-bar network switcher lets the user change which Midnight network
 * the wallet talks to. Because Midnight addresses are network-prefixed
 * (`mn_addr_<network>1...`), switching networks requires:
 *   1. If the target network's addresses are NOT cached, prompt biometric,
 *      load the seed, derive fresh addresses with the target network's
 *      prefix, save them to [WalletAddressCache], wipe the seed
 *   2. Persist the network selection to DataStore
 *   3. Restart the app (Hilt singletons bind URLs at startup)
 *
 * If the target network IS already cached, skip step 1 — no biometric
 * needed, the restart alone is sufficient.
 *
 * This flow was added in 8B.3 to fix the issue where switching networks
 * left the wallet using the previous network's address (task #86).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var networkRepository: NetworkRepository

    @Inject
    lateinit var walletAddressCache: WalletAddressCache

    @Inject
    lateinit var seedVault: SeedVault

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
                // Gate the whole app behind the onboarding flow. On first launch
                // (or after a wallet reset), WalletGate shows OnboardingScreen
                // until the user creates or restores a wallet. Once a seed is
                // persisted, the content slot renders the normal app navigation.
                // Explicit @MainActivity to make the Activity reference unambiguous
                // (inside setContent the surrounding `this` could change if someone
                // wraps the lambda in a different scope)
                WalletGate(activity = this@MainActivity) {
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
                                        switchNetwork(network)
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
    }

    /**
     * Switch the wallet to a different Midnight network.
     *
     * If the target network's addresses are already cached, just persist the
     * selection and restart. Otherwise, prompt biometric, derive fresh
     * addresses with the target network's prefix, save them, and restart.
     *
     * If the user cancels the biometric prompt (or it fails), nothing is
     * persisted and the app stays on the current network.
     */
    private suspend fun switchNetwork(target: MidnightNetwork) {
        // Already-derived network → skip biometric, just restart.
        val cached = walletAddressCache.load(target)
        if (cached != null) {
            Log.d(TAG, "Switching to ${target.name}: cached addresses found, no derivation needed")
            networkRepository.setSelectedNetwork(target)
            restartApp()
            return
        }

        // Need to derive. Prompt biometric first — only persist + restart
        // after the cache is populated, so a cancelled biometric leaves the
        // app in its current working state.
        Log.d(TAG, "Switching to ${target.name}: no cached addresses, need to derive")
        var plaintextSeed: PlaintextSeed? = null
        try {
            plaintextSeed = seedVault.loadSeed(this@MainActivity)
            val addresses = WalletAddressDeriver.derive(plaintextSeed.bip39Seed, target)
            walletAddressCache.save(target, addresses)
            Log.d(TAG, "Derived and cached ${target.name} addresses")
        } catch (e: AuthenticationCancelledException) {
            Log.d(TAG, "Network switch cancelled: biometric dismissed")
            return
        } catch (e: AuthenticationLockedOutException) {
            Log.w(TAG, "Network switch aborted: biometric temporarily locked out")
            return
        } catch (e: AuthenticationPermanentlyLockedOutException) {
            Log.w(TAG, "Network switch aborted: biometric permanently locked out")
            return
        } catch (e: AuthenticationFailedException) {
            Log.w(TAG, "Network switch aborted: biometric auth failed", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "Network switch failed during derivation", e)
            return
        } finally {
            plaintextSeed?.wipe()
        }

        // Derivation succeeded. Commit + restart.
        networkRepository.setSelectedNetwork(target)
        restartApp()
    }

    /**
     * Restart the app by finishing the activity and killing the process.
     *
     * Needed because Hilt singletons (IndexerClient, NodeRpcClient,
     * ProofServerClient, NetworkConfig) are bound to network URLs at app
     * startup. To switch networks, the whole object graph must be rebuilt.
     */
    private fun restartApp() {
        finishAffinity()
        exitProcess(0)
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
