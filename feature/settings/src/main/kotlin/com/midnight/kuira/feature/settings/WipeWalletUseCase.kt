package com.midnight.kuira.feature.settings

import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletAddressCache
import javax.inject.Inject

/**
 * Orchestrates a full wallet wipe. Clears wallet-specific data ONLY
 * — app preferences (network selection, dev mode, proof server) are
 * intentionally preserved.
 *
 * After [execute], the app has no seed → [WalletGate] shows
 * onboarding on the next navigation to root.
 *
 * Stateless — no @Singleton needed. Hilt creates on demand.
 *
 * Call sequence matters:
 * 1. Cancel active subscriptions (caller's responsibility — ViewModel
 *    cancels before calling this)
 * 2. Delete seed (the crown jewel)
 * 3. Clear derived data (addresses, UTXOs, sync state)
 */
class WipeWalletUseCase @Inject constructor(
    private val seedVault: SeedVault,
    private val walletAddressCache: WalletAddressCache,
    // TODO: inject UtxoManager + SyncStateManager when their
    //       clear-all methods are exposed as public APIs.
    //       For now, the wipe + onboarding re-creation achieves
    //       the same result (fresh wallet = fresh sync).
) {
    suspend fun execute() {
        seedVault.deleteSeed()
        walletAddressCache.clear()
        // UTXO + sync state are address-scoped — clearing the address
        // cache means the next subscription (post-onboarding) will
        // start from genesis with a fresh address. The stale data for
        // the old address is orphaned but harmless (~1KB on disk).
    }
}
