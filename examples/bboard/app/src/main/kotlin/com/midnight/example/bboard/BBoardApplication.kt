package com.midnight.example.bboard

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt entry point for BBoard.
 *
 * `@HiltAndroidApp` triggers code generation for the app-scoped
 * component (`Hilt_BBoardApplication`) which is the SingletonComponent
 * the dapp-ui module's `@InstallIn(SingletonComponent::class)`
 * providers attach to. Without this annotation `hiltViewModel()` in
 * the sigil/wallet panels would crash at runtime with
 * `EntryPointAccessors: no entry point for ...`.
 *
 * Registered as `android:name=".BBoardApplication"` in the manifest.
 */
@HiltAndroidApp
class BBoardApplication : Application()
