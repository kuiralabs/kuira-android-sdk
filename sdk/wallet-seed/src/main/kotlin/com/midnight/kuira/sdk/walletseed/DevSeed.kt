package com.midnight.kuira.sdk.walletseed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * The dev seed a debuggable host app declares, so an automated run reaches a known funded wallet
 * without a passkey ceremony.
 *
 * Both halves have to come from the HOST APP. A library's own `BuildConfig.DEBUG` is false in
 * every published AAR, so a hatch gated on it is dead code for every consumer — which is why an
 * example app could never use this and each one needed a sigil forged by hand. And a value baked
 * into the library at publish time travels inside the artifact, so whoever publishes ships their
 * own seed to everyone who depends on it.
 *
 * Mirrors [com.midnight.kuira.core.network.BlockfrostProjectId]: manifest meta-data, because a
 * library cannot read its consumer's `BuildConfig` without reflection.
 */
object DevSeed {

    private const val TAG = "DevSeed"

    /** Manifest meta-data the host app declares; the contract plugin substitutes the value. */
    const val MANIFEST_KEY = "io.github.kuiralabs.DEV_SEED"

    /**
     * The seed this app declares, or null when there is none to use.
     *
     * Null for a release app even when one is declared: the point of the gate is that shipping a
     * build with the override still live cannot hand everyone the same wallet.
     */
    fun resolve(context: Context, explicit: String? = null): ByteArray? {
        val hex = explicit?.takeIf { it.isNotBlank() }?.trim() ?: fromManifest(context) ?: return null
        if (!isHostDebuggable(context)) {
            Log.w(TAG, "Ignoring the dev seed — ${context.packageName} is not a debuggable build")
            return null
        }
        return WalletSeedSource.devSeedBytes(isDebug = true, hex = hex)
    }

    /** The host app's own debuggable flag — not the library's, which is false once published. */
    fun isHostDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Quiet and non-fatal: an app that declares no meta-data simply has no override. */
    private fun fromManifest(context: Context): String? = try {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val raw = appInfo.metaData?.get(MANIFEST_KEY)?.toString()?.trim()
        // An unsubstituted placeholder ships the literal "${...}" into the APK; treat as absent.
        when {
            raw.isNullOrBlank() -> null
            raw.startsWith("\${") -> null
            else -> raw
        }
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "could not read the app's manifest meta-data", e)
        null
    }
}
