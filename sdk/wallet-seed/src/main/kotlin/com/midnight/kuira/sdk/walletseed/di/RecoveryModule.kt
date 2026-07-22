package com.midnight.kuira.sdk.walletseed.di

import com.midnight.kuira.sdk.walletseed.RecoveryPhraseManager
import com.midnight.kuira.sdk.walletseed.RecoverySeedStore
import com.midnight.kuira.sdk.walletseed.WalletRecovery
import com.midnight.kuira.sdk.walletseed.WalletSeedSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Wires the recovery feature into the Hilt graph so any dApp component can
 * `@Inject lateinit var recovery: WalletRecovery` and build its own recovery UX — independent of
 * the bundled wallet pill / panel.
 *
 * - [WalletRecovery] → [RecoveryPhraseManager] (the public contract → its default impl)
 * - [RecoverySeedStore] → [WalletSeedSource] (the internal seam → the real vault authority)
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecoveryModule {

    @Binds
    abstract fun bindWalletRecovery(impl: RecoveryPhraseManager): WalletRecovery

    @Binds
    abstract fun bindRecoverySeedStore(impl: WalletSeedSource): RecoverySeedStore
}
