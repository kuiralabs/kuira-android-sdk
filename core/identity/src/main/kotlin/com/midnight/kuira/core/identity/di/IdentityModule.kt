package com.midnight.kuira.core.identity.di

import android.content.Context
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.identity.auth.AuthorizationStore
import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.sigil.Ed25519PrfSigilProvider
import com.midnight.kuira.core.identity.sigil.SigilIdentityProvider
import dagger.Binds
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

/**
 * Binds the default [SigilIdentityProvider] implementation
 * ([Ed25519PrfSigilProvider]) into the Hilt graph.
 *
 * Why a separate `@Module` (not just a `@Provides` next door): `@Binds`
 * abstract members require an abstract class/interface owner, while
 * the existing [IdentityModule] is an `object` of `@Provides`
 * factories. Splitting into two modules at the same install scope is
 * Hilt's idiomatic way to mix both styles without rewriting the
 * existing surface.
 *
 * Apps that want a different `SigilIdentityProvider` (e.g. a future
 * zk-passport implementation, midnightOS-Passkeys interop, a fake for
 * tests) replace this module via Hilt's `@UninstallModules` +
 * `@InstallIn(SingletonComponent::class)` pattern.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SigilIdentityModule {
    @Binds
    @Singleton
    abstract fun bindSigilIdentityProvider(
        impl: Ed25519PrfSigilProvider,
    ): SigilIdentityProvider
}
