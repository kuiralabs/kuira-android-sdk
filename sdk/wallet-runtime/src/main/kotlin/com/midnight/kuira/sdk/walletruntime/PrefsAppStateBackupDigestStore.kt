package com.midnight.kuira.sdk.walletruntime

import android.content.Context

/**
 * SharedPreferences-backed [AppStateBackupDigestStore]. Holds the SHA-256 of the
 * last app-state blob successfully uploaded, so [AppStateCloudBackupCoordinator]
 * skips the network round-trip when nothing changed. The digest is not secret
 * (it's a hash of already-encrypted-at-rest state), so plain prefs is fine.
 */
class PrefsAppStateBackupDigestStore(context: Context) : AppStateBackupDigestStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY, null)

    override fun put(digest: String) {
        prefs.edit().putString(KEY, digest).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "kuira_appstate_backup_digest"
        const val KEY = "digest"
    }
}
