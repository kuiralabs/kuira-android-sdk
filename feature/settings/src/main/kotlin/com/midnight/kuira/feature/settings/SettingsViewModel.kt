package com.midnight.kuira.feature.settings

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import com.midnight.kuira.core.network.NetworkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Named
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings screen ViewModel. Combines multiple reactive Flows into
 * a single [SettingsUiState]. All network-dependent data uses the
 * reactive `flatMapLatest` architecture — no app restart needed.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val devModeRepository: DevModeRepository,
    private val proofServerRepository: ProofServerRepository,
    private val seedVault: SeedVault,
    private val wipeWalletUseCase: WipeWalletUseCase,
    @Named("buildType") private val buildType: String,
    @Named("versionName") private val versionName: String,
    @Named("gitHash") private val gitHash: String,
) : ViewModel() {

    /** Mutable local state for tap counting + transient UI. */
    private val _localState = MutableStateFlow(LocalState())

    val uiState: StateFlow<SettingsUiState> = combine(
        networkRepository.selectedNetworkFlow,
        devModeRepository.isUnlocked,
        proofServerRepository.customUrl,
        _localState,
    ) { network, devMode, customProofUrl, local ->
        val config = NetworkConfig.forNetwork(network)
        val proofUrl = customProofUrl ?: config.proofServerUrl

        SettingsUiState(
            network = network,
            lastSyncAgo = "12s ago", // TODO: wire to SyncStateManager timestamp
            devModeUnlocked = devMode,
            proofServerUrl = proofUrl,
            buildType = buildType,
            commitHash = gitHash,
            versionName = versionName,
            versionTapCount = local.versionTapCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    // ── Actions ──

    fun onNetworkSelected(network: MidnightNetwork) {
        viewModelScope.launch {
            networkRepository.setSelectedNetwork(network)
            // Reactive architecture: selectedNetworkFlow emits →
            // all repositories reconnect via flatMapLatest.
            // No restart needed.
        }
    }

    fun onVersionTapped() {
        _localState.update { state ->
            val newCount = state.versionTapCount + 1
            if (newCount >= 7) {
                viewModelScope.launch { devModeRepository.setUnlocked(true) }
            }
            state.copy(versionTapCount = newCount)
        }
    }

    fun onDisableDevMode() {
        viewModelScope.launch { devModeRepository.setUnlocked(false) }
        _localState.update { it.copy(versionTapCount = 0) }
    }

    fun onProofServerUrlChanged(url: String) {
        viewModelScope.launch {
            proofServerRepository.setCustomUrl(url.ifBlank { null })
        }
    }

    fun onForceResync() {
        // TODO: wire to SubscriptionManager.startSubscription(forceFullResync = true)
        // Needs the current wallet address from WalletAddressCache.
        // For now the confirmation sheet shows; the actual resync
        // action will be wired when SubscriptionManager is injectable.
    }

    /**
     * Trigger a biometric test. Returns the seed on success (caller
     * must wipe it), throws on failure. Requires Activity context
     * for the biometric prompt — passed as parameter, NOT stored.
     */
    suspend fun testBiometric(activity: FragmentActivity): PlaintextSeed? {
        return seedVault.loadSeed(activity)
    }

    fun onWipeWallet() {
        viewModelScope.launch {
            // Cancel subscriptions would happen here if we had a ref
            wipeWalletUseCase.execute()
            // Navigation to onboarding is handled by the caller
            // (WalletGate detects hasSeed() = false)
        }
    }

    private data class LocalState(
        val versionTapCount: Int = 0,
    )
}
