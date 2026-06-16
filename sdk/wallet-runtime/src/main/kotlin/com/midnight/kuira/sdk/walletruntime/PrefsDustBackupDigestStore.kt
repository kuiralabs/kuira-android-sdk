package com.midnight.kuira.sdk.walletruntime

import android.content.Context

/**
 * SharedPreferences-backed [DustBackupDigestStore]. Holds the SHA-256 of the
 * last checkpoint successfully uploaded per address, so [DustCloudBackupCoordinator]
 * skips the network round-trip when nothing changed. The digest is not secret
 * (it's a hash of an already-encrypted-at-rest checkpoint), so plain prefs is fine.
 */
class PrefsDustBackupDigestStore(context: Context) : DustBackupDigestStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(address: String): String? = prefs.getString(address, null)

    override fun put(address: String, digest: String) {
        prefs.edit().putString(address, digest).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "kuira_dust_backup_digests"
    }
}
