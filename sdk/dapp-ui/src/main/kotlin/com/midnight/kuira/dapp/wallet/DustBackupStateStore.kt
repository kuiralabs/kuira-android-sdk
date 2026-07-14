package com.midnight.kuira.dapp.wallet

import android.content.Context
import com.midnight.kuira.sdk.WalletBackupPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SINGLE owner of the durable dust-backup choice — the tri-state {never-chosen / enabled /
 * opted-out} that was previously two independent pref keys + two StateFlows hand-synced at
 * five call sites inside [WalletPanelViewModel]. A singleton so the restore gate (a
 * [com.midnight.kuira.sdk.walletruntime.DustRestoreGate] impl, which outlives any ViewModel)
 * and the panel VM read/write the SAME live state: a gate-committed toggle is instantly
 * visible to the UI, and the wallet's upload-time prefs supplier
 * ([com.midnight.kuira.sdk.MidnightWallet.walletBackupPrefsProvider]) always reads current
 * truth — no pushed snapshot to go stale.
 *
 * Invariant (enforced here, not by caller discipline): `enabled` and `optedOut` are never
 * both true.
 */
@Singleton
class DustBackupStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _optedOut = MutableStateFlow(prefs.getBoolean(KEY_DUST_OPTED_OUT, false))

    /** True → the user disabled dust cloud backup (#246 semantics: stop uploading). */
    val optedOut: StateFlow<Boolean> = _optedOut.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_DUST_BACKUP_ENABLED, false))

    /**
     * True → the user has dust cloud backup ENABLED — the durable committed toggle (consent
     * granted + sync succeeded at least once, or restored from the wallet's cloud prefs).
     * Drives the Switch position, never the live sync status.
     */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The enable flow is starting: clear the opt-out now; [commitEnabled] lands on success. */
    fun clearOptOut() = write(optedOut = false, enabled = _enabled.value)

    /** Durable toggle ON (enable+sync succeeded, or the restore gate recovered the truth). */
    fun commitEnabled() = write(optedOut = false, enabled = true)

    /** Durable opt-out (user disabled, or the restore gate mirrored a restored opt-out). */
    fun commitOptedOut() = write(optedOut = true, enabled = false)

    /**
     * The one-time backup-setup offer (a NEW wallet's onboarding step) was shown — never
     * nag again; the BackupSection toggle stays as the manual path.
     */
    var onboardingOffered: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_OFFERED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_OFFERED, value).apply()
        }

    /** The blob-borne prefs snapshot the wallet's upload-time supplier reads. */
    fun currentPrefs() = WalletBackupPrefs(
        dustBackupEnabled = _enabled.value,
        dustBackupOptedOut = _optedOut.value,
    )

    private fun write(optedOut: Boolean, enabled: Boolean) {
        require(!(optedOut && enabled)) { "enabled and optedOut are mutually exclusive" }
        _optedOut.value = optedOut
        _enabled.value = enabled
        prefs.edit()
            .putBoolean(KEY_DUST_OPTED_OUT, optedOut)
            .putBoolean(KEY_DUST_BACKUP_ENABLED, enabled)
            .apply()
    }

    companion object {
        internal const val PREFS_NAME = "kuira_dust_backup"
        internal const val KEY_DUST_OPTED_OUT = "dust_backup_opted_out"
        internal const val KEY_DUST_BACKUP_ENABLED = "dust_backup_enabled"
        internal const val KEY_ONBOARDING_OFFERED = "backup_onboarding_offered"
    }
}
