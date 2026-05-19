package com.midnight.kuira.dapp.di

import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Optional binding for [AppDataBackupProvider].
 *
 * `@BindsOptionalOf` declares that a binding of the interface *may*
 * exist downstream, without actually providing one here. Apps that
 * implement the interface provide a concrete `@Provides AppDataBackupProvider`
 * elsewhere in their Hilt graph; apps that don't (e.g. BBoard) get an
 * empty `Optional` injected.
 *
 * Consumers — currently only [com.midnight.kuira.dapp.sigil.SigilPanelViewModel] —
 * inject `Optional<AppDataBackupProvider>` and treat absence as
 * "no app-data round-trip needed" (the previous behavior).
 *
 * The module is `abstract` because `@BindsOptionalOf` only works on
 * abstract methods inside abstract classes / interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppDataBackupModule {
    @BindsOptionalOf
    abstract fun maybeAppDataBackupProvider(): AppDataBackupProvider
}
