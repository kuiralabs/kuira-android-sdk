package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.DriveAuthManager
import com.midnight.kuira.core.identity.backup.DriveBackupStorage
import com.midnight.kuira.core.identity.backup.PlayServicesDriveAuthManager
import com.midnight.kuira.core.identity.backup.SeedDerivedKeyDeriver
import com.midnight.kuira.core.identity.backup.silentTokenOrThrow
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
    fun provideDriveAuthManager(
        @ApplicationContext context: Context,
    ): DriveAuthManager = PlayServicesDriveAuthManager(context)

    @Provides
    @Singleton
    fun provideMidnightSdkFactory(
        @ApplicationContext context: Context,
        driveAuth: DriveAuthManager,
    ): MidnightSdkFactory = MidnightSdkFactory { config, seed ->
        MidnightSdk.Builder(context)
            .network(config.network)
            .seed(seed)
            .provingMode(config.provingMode)
            // #235: the embedded-wallet provider keeps dust pre-synced in the
            // background (live tip-advance subscription) so a tx never waits on a
            // cold sync. The SDK Builder default is off (for raw SDK consumers);
            // every dapp-ui host opts in here. Surfaced via WalletForegroundService.
            .proactiveDustSync(true)
            .also { builder -> config.proofServerUrl?.let { builder.proofServerUrl(it) } }
            // Cross-device dust backup: assemble the coordinator from the proven
            // pieces. Drive REST (DriveBackupStorage) gets a silent token; if
            // consent hasn't been granted via the UI yet, silentTokenOrThrow
            // throws → fetch returns null / upload is skipped, both best-effort.
            .dustCloudBackupFactory { address, dustSeed ->
                DustCloudBackupCoordinator(
                    storage = DriveBackupStorage(
                        fileName = DUST_BACKUP_FILE,
                        tokenProvider = { _ -> driveAuth.silentTokenOrThrow() },
                    ),
                    encryptionKey = SeedDerivedKeyDeriver.deriveDustBackupKey(dustSeed),
                    digestStore = PrefsDustBackupDigestStore(context),
                )
            }
            // Silent app-state backup (≤2 KB) over Block Store, keyed off the
            // master seed (no per-backup biometric → safe to run automatically).
            .appStateBackupFactory { seed ->
                AppStateCloudBackupCoordinator(
                    storage = BlockStoreBackupStorage(context),
                    encryptionKey = SeedDerivedKeyDeriver.deriveAppStateBackupKey(seed),
                    digestStore = PrefsAppStateBackupDigestStore(context),
                )
            }
            .build()
    }

    private const val DUST_BACKUP_FILE = "kuira-dust-backup.bin"
}
