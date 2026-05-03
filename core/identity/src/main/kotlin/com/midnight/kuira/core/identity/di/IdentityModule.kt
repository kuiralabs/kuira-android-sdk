package com.midnight.kuira.core.identity.di

import android.content.Context
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.identity.auth.AuthorizationStore
import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for identity dependencies.
 *
 * Provides PasskeyManager, AuthorizationStore, and related components.
 * AccessKeyManager is not provided here — it requires an HDWallet instance
 * which is session-scoped (created after biometric unlock), not singleton.
 */
@Module
@InstallIn(SingletonComponent::class)
object IdentityModule {

    @Provides
    @Singleton
    fun providePasskeyConfig(): PasskeyConfig {
        return PasskeyConfig(
            rpId = DEFAULT_RP_ID,
            rpName = DEFAULT_RP_NAME,
        )
    }

    @Provides
    @Singleton
    fun providePasskeyManager(
        config: PasskeyConfig,
    ): PasskeyManager {
        return PasskeyManager(config)
    }

    @Provides
    @Singleton
    fun provideAuthorizationStore(
        @ApplicationContext context: Context,
        keyManager: WalletKeyManager,
    ): AuthorizationStore {
        return AuthorizationStore(context, keyManager)
    }

    /**
     * Default RP ID — must match Digital Asset Links configuration.
     * Production: kuira.midnight.network (once DAL is configured there)
     * Development: nel349.github.io (DAL hosted on GitHub Pages)
     */
    private const val DEFAULT_RP_ID = "nel349.github.io"
    private const val DEFAULT_RP_NAME = "Kuira"
}
