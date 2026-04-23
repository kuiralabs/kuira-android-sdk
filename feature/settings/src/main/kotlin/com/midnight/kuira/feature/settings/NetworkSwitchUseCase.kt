package com.midnight.kuira.feature.settings

import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletAddressCache
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkRepository
import com.midnight.kuira.feature.onboarding.WalletAddressDeriver
import javax.inject.Inject

/**
 * Switches the wallet to a different Midnight network. Handles the
 * full flow: check cache → derive addresses if needed (biometric) →
 * persist selection.
 *
 * Replaces the old `MainActivity.switchNetwork()` that was deleted
 * when the NetworkSelectorBar was retired. The logic is identical
 * but lives in the data layer where it belongs.
 *
 * **Why address derivation is needed:** Midnight addresses are
 * network-prefixed (`mn_addr_<network>1...`). Each network needs
 * its own derived addresses. If the target network's addresses are
 * already cached, we skip derivation (no biometric needed).
 *
 * Returns [Result.success] if the switch succeeded, or
 * [Result.failure] with the exception if biometric was cancelled
 * or derivation failed.
 */
class NetworkSwitchUseCase @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val walletAddressCache: WalletAddressCache,
    private val seedVault: SeedVault,
) {
    /**
     * Switch to [target] network.
     *
     * @param target the network to switch to
     * @param activity required for biometric prompt if addresses
     *   need derivation. Pass null to skip derivation (will fail
     *   if addresses aren't cached).
     */
    suspend fun execute(
        target: MidnightNetwork,
        activity: FragmentActivity?,
    ): Result<Unit> {
        // Already cached → just persist, no biometric needed
        val cached = walletAddressCache.load(target)
        if (cached != null) {
            networkRepository.setSelectedNetwork(target)
            return Result.success(Unit)
        }

        // Need to derive — requires Activity for biometric
        if (activity == null) {
            return Result.failure(
                IllegalStateException("No Activity for biometric — cannot derive addresses for ${target.name}")
            )
        }

        return try {
            val plaintextSeed = seedVault.loadSeed(activity)
            try {
                val addresses = WalletAddressDeriver.derive(plaintextSeed.bip39Seed, target)
                walletAddressCache.save(target, addresses)
            } finally {
                plaintextSeed.wipe()
            }
            networkRepository.setSelectedNetwork(target)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
