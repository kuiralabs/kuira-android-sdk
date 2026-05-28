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

    // NOTE: there is intentionally NO default `PasskeyConfig` provider here.
    //
    // `rpId` is the passkey relying-party domain — it MUST match the
    // `assetlinks.json` the consuming app hosts on its OWN domain. A hardcoded
    // SDK default would silently route every consumer through the maintainer's
    // domain and break PRF unless the maintainer added them to a maintainer-
    // hosted assetlinks.json — i.e. the SDK would be effectively permissioned
    // (wishlist #22). So each consuming app MUST bind its own:
    //
    //   @Module @InstallIn(SingletonComponent::class)
    //   object MyAppIdentityModule {
    //       @Provides @Singleton
    //       fun providePasskeyConfig() =
    //           PasskeyConfig(rpId = "myapp.example.com", rpName = "My App")
    //   }
    //
    // Omitting it is a fail-fast Dagger missing-binding error at build time —
    // the intended "declare your domain" signal.
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
