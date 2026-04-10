// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.auth.di

import android.content.Context
import com.midnight.kuira.core.auth.SecurityCapabilities
import com.midnight.kuira.core.auth.WalletKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for wallet authentication dependencies.
 *
 * **Provided Dependencies:**
 * - [WalletKeyManager]: Manages the AES-256 master key in Android Keystore
 * - [SecurityCapabilities]: Detects device hardware security (StrongBox, TEE, biometrics)
 *
 * Both are singletons — the Keystore and BiometricManager are device-level resources.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideSecurityCapabilities(
        @ApplicationContext context: Context
    ): SecurityCapabilities {
        return SecurityCapabilities(context)
    }

    @Provides
    @Singleton
    fun provideWalletKeyManager(): WalletKeyManager {
        return WalletKeyManager()
    }
}
