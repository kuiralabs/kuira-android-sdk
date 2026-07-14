package com.midnight.kuira.dapp.sigil

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records HOW this install's identity came to be, and this identity's restore-prompt state.
 *
 * `identityRestored`: the sigil was signed in / reused (including a forge that converged on
 * an existing passkey via sign-in-if-exists) or the wallet came back via the recovery
 * phrase — i.e. the wallet existed before this install, so a cloud dust checkpoint plausibly
 * exists even when the app-state blob carries no prefs (Block Store is per-app; a sibling
 * dApp's blob can't reach us). That's the signal the dust restore gate uses to offer the ONE
 * consent prompt on a cross-dApp first run; a freshly forged wallet is never prompted.
 *
 * Wiped on sign-out so the next identity on this install never inherits the previous
 * wallet's restore signal. Writes use `commit()` (synchronous): the flag is written
 * immediately before flows that can kill or lose the process (sign-in hand-off) — an
 * `apply()` lost to a kill would silently disable restore continuity, the exact bug this
 * store serves.
 */
@Singleton
class IdentityProvenanceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var identityRestored: Boolean
        get() = prefs.getBoolean(KEY_RESTORED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RESTORED, value).commit()
        }

    /** Sign-out: the next identity must not inherit this one's restore signal. */
    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        internal const val PREFS_NAME = "kuira_identity_provenance"
        private const val KEY_RESTORED = "identity_restored"
    }
}
