package com.midnight.kuira.sdk.walletseed

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.identity.backup.SeedDeriver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [WalletRecovery] implementation (#252). Thin orchestration over the [RecoverySeedStore]
 * (vault entropy + restore) and the pure [SeedDeriver] codec (entropy ↔ phrase). Holds no secret
 * state of its own: the only thing it persists is the user's "I've saved it" acknowledgment, a
 * local boolean — never the phrase or entropy (see `docs/security/recovery-model.md`).
 *
 * Internal: consumers depend on the [WalletRecovery] contract, not this class.
 */
@Singleton
internal class RecoveryPhraseManager @Inject constructor(
    @ApplicationContext context: Context,
    private val seedStore: RecoverySeedStore,
) : WalletRecovery {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _isPhraseSaved = MutableStateFlow(prefs.getBoolean(KEY_PHRASE_SAVED, false))
    override val isPhraseSaved: StateFlow<Boolean> = _isPhraseSaved.asStateFlow()

    override suspend fun revealPhrase(activity: FragmentActivity): List<String> {
        val entropy = seedStore.loadEntropy(activity)
        return try {
            SeedDeriver.entropyToRecoveryWords(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    override suspend fun restoreFromPhrase(activity: FragmentActivity, words: List<String>) {
        if (seedStore.hasSeed()) {
            throw RecoveryNotAllowedException(
                "A wallet already exists on this device. Sign out before restoring from a phrase.",
            )
        }
        if (!isValidPhrase(words)) throw InvalidRecoveryPhraseException()

        val entropy = SeedDeriver.recoveryWordsToEntropy(words)
        try {
            // Reproduces the exact wallet from the phrase's entropy; the returned seed is wiped
            // here — the vault now holds the canonical copy.
            seedStore.acceptPreDerivedSeed(activity, entropy).fill(0)
        } finally {
            entropy.fill(0)
        }
        markPhraseSaved()
    }

    override fun isValidPhrase(words: List<String>): Boolean =
        SeedDeriver.isValidRecoveryPhrase(words)

    override fun markPhraseSaved() {
        prefs.edit().putBoolean(KEY_PHRASE_SAVED, true).apply()
        _isPhraseSaved.value = true
    }

    private companion object {
        const val PREFS = "kuira_recovery_phrase"
        const val KEY_PHRASE_SAVED = "phrase_saved"
    }
}
