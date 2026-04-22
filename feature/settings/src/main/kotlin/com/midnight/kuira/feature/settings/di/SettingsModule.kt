package com.midnight.kuira.feature.settings.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * Hilt module for Settings feature. Build info strings are provided
 * here as @Named bindings so the ViewModel doesn't depend on any
 * app-module BuildConfig directly.
 *
 * TODO: These values are placeholders — the app module should
 * override them via a higher-priority Hilt module that reads from
 * the real BuildConfig. For now, they compile and demonstrate
 * the injection pattern.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Named("buildType")
    fun provideBuildType(): String = "debug" // overridden by app module

    @Provides
    @Named("versionName")
    fun provideVersionName(): String = "1.0.0" // overridden by app module

    @Provides
    @Named("gitHash")
    fun provideGitHash(): String = "dev" // overridden by app module
}
