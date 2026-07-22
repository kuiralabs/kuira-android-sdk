package com.midnight.kuira.di

import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The Kuira wallet app's passkey relying-party config.
 *
 * The SDK ([com.midnight.kuira.core.identity.di.IdentityModule]) deliberately
 * provides NO default `PasskeyConfig` — `rpId` is the passkey domain and must
 * match the `assetlinks.json` this app hosts, so each consuming app declares
 * its own (wishlist ). This is the wallet app's declaration; third-party
 * dApps ship the equivalent with their own domain + name.
 */
@Module
@InstallIn(SingletonComponent::class)
object IdentityConfigModule {

    @Provides
    @Singleton
    fun providePasskeyConfig(): PasskeyConfig = PasskeyConfig(
        rpId = RP_ID,
        rpName = RP_NAME,
    )

    // Digital Asset Links host for the Kuira wallet's passkeys.
    // Dev: nel349.github.io (DAL on GitHub Pages). Swap to the production
    // domain once DAL is configured there.
    private const val RP_ID = "nel349.github.io"
    private const val RP_NAME = "Kuira"
}
