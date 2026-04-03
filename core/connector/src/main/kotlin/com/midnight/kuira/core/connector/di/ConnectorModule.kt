package com.midnight.kuira.core.connector.di

import com.midnight.kuira.core.connector.ApprovalManager
import com.midnight.kuira.core.connector.ConnectorManager
import com.midnight.kuira.core.network.NetworkConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectorModule {

    @Provides
    @Singleton
    fun provideConnectorManager(
        networkConfig: NetworkConfig,
        approvalManager: ApprovalManager,
    ): ConnectorManager {
        return ConnectorManager(networkConfig, approvalManager)
    }
}
