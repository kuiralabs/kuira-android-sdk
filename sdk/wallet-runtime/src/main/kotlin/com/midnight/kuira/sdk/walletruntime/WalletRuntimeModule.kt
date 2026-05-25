package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import com.midnight.kuira.sdk.MidnightSdk
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for `sdk:wallet-runtime`.
 *
 * Provides the production [MidnightSdkFactory] — a thin wrapper over
 * [MidnightSdk.Builder]. Kept as a `@Provides` lambda rather than a class so
 * the construction recipe (network + seed + proving mode + optional proof URL)
 * reads in one place; tests swap in a fake factory instead.
 *
 * [MidnightSdkProvider] itself needs no binding here — it's a `@Singleton`
 * with an `@Inject` constructor, same pattern as `WalletSeedSource`.
 */
@Module
@InstallIn(SingletonComponent::class)
object WalletRuntimeModule {

    @Provides
    @Singleton
    fun provideMidnightSdkFactory(
        @ApplicationContext context: Context,
    ): MidnightSdkFactory = MidnightSdkFactory { config, seed ->
        MidnightSdk.Builder(context)
            .network(config.network)
            .seed(seed)
            .provingMode(config.provingMode)
            .also { builder -> config.proofServerUrl?.let { builder.proofServerUrl(it) } }
            .build()
    }
}
