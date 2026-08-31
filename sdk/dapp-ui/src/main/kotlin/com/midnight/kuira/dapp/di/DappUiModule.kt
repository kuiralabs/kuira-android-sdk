package com.midnight.kuira.dapp.di

import android.content.Context
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.AppStateBackup
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for `dapp-ui`-owned types only.
 *
 * Intentionally minimal — everything wallet-side
 * (`WalletKeyManager`, `BiometricGate`, `SeedVault`) is owned by
 * `core:auth:AuthModule`, and passkey identity (`PasskeyConfig`,
 * `PasskeyManager`) is owned by `core:identity:IdentityModule`.
 * Any consumer that depends on `dapp-ui` transitively pulls those
 * modules into its Hilt graph, so re-providing them here would
 * trigger Dagger's `DuplicateBindings` failure.
 *
 * **What this provides (and why each is here, not upstream):**
 *
 *  - [BlockStoreBackupStorage] — Block Store is consumed by both
 *    `dapp-ui`'s sigil panel *and* (eventually) the parent app's
 *    backup wizard. It would belong in `core:identity` if more than
 *    one upstream module needed it, but right now only the panels
 *    do, so providing it here keeps `core:identity`'s graph free of
 *    Block Store coupling until that changes.
 *
 *  - [AppStateBackup] — pure composition of [PasskeyManager] +
 *    [BlockStoreBackupStorage]. Same rationale: only consumed by
 *    the sigil panel today.
 *
 * **`rpId` belongs to the consumer, and there is no default.**
 * `core:identity`'s `IdentityModule` deliberately binds no
 * `PasskeyConfig`: the relying-party domain must match the
 * `assetlinks.json` the consuming app hosts on its *own* domain, so
 * an SDK default would route every consumer through the maintainer's
 * domain and make this SDK effectively permissioned. Each app
 * supplies its own `@Provides` for `PasskeyConfig`; omitting it is a
 * fail-fast Dagger missing-binding error at build time, which is the
 * intended "declare your domain" signal.
 *
 * No domain is named here on purpose — a documented example rpId
 * becomes a wrong one the day the ecosystem moves.
 */
@Module
@InstallIn(SingletonComponent::class)
object DappUiModule {

    @Provides
    @Singleton
    fun provideBlockStoreBackupStorage(
        @ApplicationContext context: Context,
    ): BlockStoreBackupStorage = BlockStoreBackupStorage(context)

    @Provides
    @Singleton
    fun provideAppStateBackup(
        passkeyManager: PasskeyManager,
        blockStoreStorage: BlockStoreBackupStorage,
    ): AppStateBackup = AppStateBackup(
        passkeyManager = passkeyManager,
        storage = blockStoreStorage,
    )
}

