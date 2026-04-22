package com.midnight.kuira.di

import com.midnight.kuira.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * Provides build info strings from the app module's [BuildConfig].
 * Feature modules inject these via @Named qualifiers without
 * depending on any specific BuildConfig class.
 */
@Module
@InstallIn(SingletonComponent::class)
object BuildInfoModule {

    @Provides
    @Named("buildType")
    fun provideBuildType(): String = BuildConfig.BUILD_TYPE

    @Provides
    @Named("versionName")
    fun provideVersionName(): String = BuildConfig.VERSION_NAME

    @Provides
    @Named("gitHash")
    fun provideGitHash(): String = "dev" // TODO: inject via Gradle git rev-parse
}
