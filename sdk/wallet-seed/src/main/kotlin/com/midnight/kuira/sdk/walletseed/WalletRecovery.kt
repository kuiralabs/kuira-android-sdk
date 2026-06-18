package com.midnight.kuira.sdk.walletseed

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.StateFlow

/**
 * Sovereign recovery-phrase capability (#252): reveal and restore a wallet's 24-word BIP-39
 * recovery phrase.
 *
 * This is the SDK's recommended building block for ANY recovery experience. The bundled wallet
 * pill / panel is one consumer; a dApp that wants its own onboarding and reveal screens depends on
 * this interface directly — inject it (`@Inject lateinit var recovery: WalletRecovery`) or read it
 * off `MidnightSdkProvider.recovery` — and renders however it likes. The SDK owns the cryptography
 * and the secure vault handling; the UI is yours.
 *
 * **Why a phrase exists at all.** The Sigil seed is `PRF(passkey)`, but that PRF output *is*
 * standard BIP-39 entropy, so every wallet has a canonical 24-word phrase that reconstructs it
 * exactly — independent of the passkey, the device, or any provider. It is the final, self-custody
 * recovery path (see `docs/security/recovery-model.md`).
 *
 * **Caller security contract.** The words from [revealPhrase] are the keys to the wallet: render
 * them only on a secured screen (`FLAG_SECURE`), never log them, never persist or transmit them,
 * and drop the reference promptly.
 */
interface WalletRecovery {

    /**
     * Reveal the wallet's 24-word recovery phrase. Biometric-gated; the wallet must already exist.
     *
     * Revealing does NOT by itself mark the phrase saved — call [markPhraseSaved] when the user
     * confirms they've written it down, so [isPhraseSaved] reflects a real acknowledgment.
     *
     * @throws IllegalStateException if no wallet exists to reveal.
     */
    suspend fun revealPhrase(activity: FragmentActivity): List<String>

    /**
     * Restore a wallet from a 24-word recovery phrase onto a fresh vault. Validates the BIP-39
     * checksum first, then reproduces the exact wallet. Marks the phrase saved on success (a
     * restored wallet's phrase is, by definition, already in the user's hands).
     *
     * @throws InvalidRecoveryPhraseException if [words] is not a valid BIP-39 phrase.
     * @throws RecoveryNotAllowedException if a wallet already exists on this device.
     */
    suspend fun restoreFromPhrase(activity: FragmentActivity, words: List<String>)

    /** Whether the user has acknowledged saving their phrase — drives the backup-status UI. */
    val isPhraseSaved: StateFlow<Boolean>

    /** Record that the user has saved their phrase (call on their "I've written it down" confirm). */
    fun markPhraseSaved()

    /**
     * Validate a phrase (known words + correct checksum) without restoring — for live validation
     * in a custom input UI. Normalizes casing/whitespace, so user-typed input is handled.
     */
    fun isValidPhrase(words: List<String>): Boolean
}

/** The supplied recovery phrase failed BIP-39 validation (word count, unknown word, or checksum). */
class InvalidRecoveryPhraseException(
    message: String = "Recovery phrase is not a valid BIP-39 phrase",
) : Exception(message)

/** Restore was attempted while a wallet already exists; sign out first. */
class RecoveryNotAllowedException(message: String) : Exception(message)
