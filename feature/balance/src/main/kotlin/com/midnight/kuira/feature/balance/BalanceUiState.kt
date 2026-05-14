package com.midnight.kuira.feature.balance

import com.midnight.kuira.core.indexer.model.TokenBalance
import com.midnight.kuira.core.ledger.ui.BalanceFormatter
import java.math.BigInteger

/**
 * UI state for balance viewing screens.
 *
 * Sealed class representing all possible states when loading and displaying balances.
 *
 * **State Transitions:**
 * ```
 * Loading → Success (balances loaded)
 * Loading → Error (network/database error)
 * Success → Loading (pull-to-refresh)
 * Error → Loading (retry)
 * ```
 *
 * **Example Usage:**
 * ```kotlin
 * val uiState by viewModel.balanceState.collectAsState()
 *
 * when (uiState) {
 *     is BalanceUiState.Loading -> LoadingIndicator()
 *     is BalanceUiState.Success -> BalanceList(uiState.balances)
 *     is BalanceUiState.Error -> ErrorMessage(uiState.message)
 * }
 * ```
 */
sealed class BalanceUiState {

    /**
     * Loading state - fetching balances from database/network.
     *
     * @param isRefreshing True if this is a pull-to-refresh (show small indicator)
     */
    data class Loading(
        val isRefreshing: Boolean = false
    ) : BalanceUiState()

    /**
     * Success state - balances loaded successfully.
     *
     * @param balances List of token balances (DUST, TNIGHT, etc.)
     * @param lastUpdated Timestamp when balances were last updated (human-readable)
     * @param totalBalance Total balance across all tokens (BigInteger to prevent overflow)
     */
    data class Success(
        val balances: List<TokenBalanceDisplay>,
        val lastUpdated: String,
        val totalBalance: BigInteger,
        val shieldedBalances: Map<String, BigInteger>? = null,
        val shieldedAddress: String? = null
    ) : BalanceUiState()

    /**
     * Error state - failed to load balances.
     *
     * @param message User-friendly error message
     * @param throwable Original exception (for logging)
     */
    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : BalanceUiState()
}

/**
 * Display model for a single token balance (ready for UI consumption).
 *
 * **Why separate from TokenBalance?**
 * - TokenBalance is domain model (raw BigInteger from database)
 * - TokenBalanceDisplay is UI model (formatted strings, display logic)
 *
 * **Example:**
 * ```kotlin
 * TokenBalanceDisplay(
 *     tokenType = "DUST",
 *     balanceFormatted = "1,234.567890 DUST",
 *     utxoCount = 3,
 *     balanceRaw = BigInteger("1234567890")
 * )
 * ```
 */
data class TokenBalanceDisplay(
    val tokenType: String,
    val balanceFormatted: String,  // e.g., "1,234.567890 DUST"
    val utxoCount: Int,
    val balanceRaw: BigInteger     // Raw amount (for calculations)
)

/**
 * Convert domain model to UI display model.
 */
fun TokenBalance.toDisplay(formatter: BalanceFormatter): TokenBalanceDisplay {
    return TokenBalanceDisplay(
        tokenType = tokenType,
        balanceFormatted = formatter.formatCompact(balance, tokenType),
        utxoCount = utxoCount,
        balanceRaw = balance
    )
}
