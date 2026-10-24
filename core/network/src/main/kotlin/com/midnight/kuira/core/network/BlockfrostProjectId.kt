package com.midnight.kuira.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Resolves the Blockfrost project id PreProd requires: an explicit value first, else the host
 * app's manifest meta-data. Manifest rather than `BuildConfig` because a library cannot read its
 * consumer's `BuildConfig` without reflection.
 */
object BlockfrostProjectId {

    private const val TAG = "BlockfrostProjectId"

    /** The project id for this app, or `null` when none is configured. Never throws. */
    fun resolve(context: Context, explicit: String? = null): String? {
        explicit?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return fromManifest(context)
    }

    /** Quiet and non-fatal: a Preview or localnet dapp declares no meta-data, which is correct. */
    private fun fromManifest(context: Context): String? = try {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val raw = appInfo.metaData
            ?.get(NetworkConfig.BLOCKFROST_PROJECT_ID_MANIFEST_KEY)
            ?.toString()
            ?.trim()
        // An unsubstituted placeholder ships the literal "${...}" into the APK; treat as absent.
        when {
            raw.isNullOrBlank() -> null
            raw.startsWith("\${") -> {
                Log.w(
                    TAG,
                    "manifest meta-data '${NetworkConfig.BLOCKFROST_PROJECT_ID_MANIFEST_KEY}' is an " +
                        "unsubstituted placeholder — set kuira.blockfrost.projectId in your app's " +
                        "local.properties, or the build will ship no project id at all",
                )
                null
            }
            else -> raw
        }
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "could not read the app's manifest meta-data", e)
        null
    }
}
