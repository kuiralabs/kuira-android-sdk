package com.midnight.example.bboard.di

import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * BBoard's passkey relying-party config.
 *
 * The SDK provides no default `PasskeyConfig` (wishlist #22) — `rpId` is the
 * passkey domain and must match the `assetlinks.json` this app hosts, so each
 * consuming app declares its own. BBoard uses the shared dev DAL host for now;
 * `rpName` is what shows in the biometric prompt.
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

    // Dev Digital Asset Links host (GitHub Pages).
    private const val RP_ID = "nel349.github.io"
    private const val RP_NAME = "BBoard"
}
