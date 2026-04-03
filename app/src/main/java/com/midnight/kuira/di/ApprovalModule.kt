package com.midnight.kuira.di

import android.content.Context
import com.midnight.kuira.core.connector.ApprovalManager
import com.midnight.kuira.ui.approval.KuiraApprovalManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApprovalModule {

    @Provides
    @Singleton
    fun provideApprovalManager(
        @ApplicationContext context: Context,
    ): ApprovalManager {
        return KuiraApprovalManager(context)
    }
}
