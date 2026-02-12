package com.midnight.kuira.core.network.di

import android.content.Context
import com.midnight.kuira.core.network.NetworkConfig
import com.midnight.kuira.core.network.NetworkRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for network configuration dependencies.
 *
 * **Provided Dependencies:**
 * - NetworkRepository: Manages network selection persistence
 * - NetworkConfig: Configuration for the selected network (URLs, etc.)
 *
 * **Singleton Scope:**
 * NetworkConfig is created once at app startup based on persisted network selection.
 * To change networks, the app must be restarted.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Provide NetworkRepository singleton.
     *
     * **Purpose:** Manages network selection persistence in DataStore.
     */
    @Provides
    @Singleton
    fun provideNetworkRepository(
        @ApplicationContext context: Context
    ): NetworkRepository {
        return NetworkRepository(context)
    }

    /**
     * Provide NetworkConfig singleton.
     *
     * **Initialization:** Reads the persisted network selection at startup.
     * If no selection is persisted, defaults to PREPROD.
     *
     * **MVP Limitation:** This is created once at app startup. Changing networks
     * requires an app restart to recreate this and dependent singletons.
     */
    @Provides
    @Singleton
    fun provideNetworkConfig(
        networkRepository: NetworkRepository
    ): NetworkConfig {
        return networkRepository.getNetworkConfigBlocking()
    }
}
