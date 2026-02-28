package com.midnight.kuira.feature.dust

import java.math.BigInteger

/**
 * UI state for Dust screen.
 *
 * **Pattern:** Sealed class for exhaustive when() handling
 * **States:**
 * - Loading: Checking dust status on blockchain
 * - Status: Dust exists, show tank display
 * - NoDust: No dust registered, show registration form
 * - Registering: Registration in progress with step tracking
 * - RegistrationSuccess: Registration tx finalized
 * - Error: Error state
 *
 * **Usage:**
 * ```kotlin
 * when (state) {
 *     is DustUiState.Loading -> ShowLoading()
 *     is DustUiState.Status -> ShowDustTank(state.balance, state.nightBalance)
 *     is DustUiState.NoDust -> ShowRegistrationForm()
 *     is DustUiState.Registering -> ShowProgress(state.step)
 *     is DustUiState.RegistrationSuccess -> ShowSuccess(state.txHash)
 *     is DustUiState.Error -> ShowError(state.message)
 * }
 * ```
 */
sealed class DustUiState {

    /**
     * Initial state — waiting for user to trigger a status check.
     */
    data object Idle : DustUiState()

    /**
     * Checking dust status on blockchain.
     */
    data object Loading : DustUiState()

    /**
     * Dust exists — show tank display.
     *
     * @property balance Current dust balance in Specks (time-adjusted)
     * @property nightBalance Total NIGHT balance (in Stars) backing dust generation
     * @property timeToCapacityMs Milliseconds until full generation (0 if at capacity)
     * @property isAtCapacity Whether all tokens are at max capacity
     */
    data class Status(
        val balance: BigInteger,
        val nightBalance: BigInteger,
        val timeToCapacityMs: Long,
        val isAtCapacity: Boolean
    ) : DustUiState()

    /**
     * No dust registered — show registration form.
     */
    data object NoDust : DustUiState()

    /**
     * Registration in progress.
     *
     * @property step Current step in the registration flow
     */
    data class Registering(val step: RegistrationStep) : DustUiState()

    /**
     * Registration tx finalized successfully.
     *
     * @property txHash Transaction hash from finalization
     */
    data class RegistrationSuccess(val txHash: String) : DustUiState()

    /**
     * Error state.
     *
     * @property message User-friendly error message
     * @property throwable Original exception (for debugging)
     */
    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : DustUiState()
}

/**
 * Steps in the dust registration flow.
 *
 * Each step maps to a phase of the transaction lifecycle:
 * build → prove → seal → submit
 */
enum class RegistrationStep(val displayText: String) {
    BUILDING("Building registration transaction..."),
    PROVING("Proving transaction (this may take a few minutes)..."),
    SEALING("Sealing proven transaction..."),
    SUBMITTING("Submitting to blockchain...")
}
