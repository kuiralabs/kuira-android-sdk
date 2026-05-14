package com.midnight.kuira.core.ledger.ui

import java.math.BigInteger
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility for formatting token amounts for display.
 *
 * **Responsibilities:**
 * - Convert raw amounts (BigInteger) to human-readable strings
 * - Add thousands separators (1,234,567)
 * - Handle decimal places (based on token precision)
 * - Append token symbols (e.g., "1,234.56 DUST")
 *
 * **Token Precision:**
 * - DUST: 15 decimals (1 DUST = 10^15 Specks)
 * - NIGHT/TNIGHT: 6 decimals (1 NIGHT = 1,000,000 Stars)
 * - Custom tokens: Configurable precision (default 6)
 *
 * **Performance:**
 * DecimalFormat is cached to avoid recreating on every format call.
 *
 * **Example Usage:**
 * ```kotlin
 * @Inject lateinit var formatter: BalanceFormatter
 * formatter.format(BigInteger("1234567890"), "DUST")  // "1,234.567890 DUST"
 * formatter.format(BigInteger.ZERO, "TNIGHT")          // "0.000000 TNIGHT"
 * ```
 */
@Singleton
class BalanceFormatter @Inject constructor() {

    private val locale: Locale = Locale.US

    // Cached DecimalFormat for performance (avoid recreating on every call)
    private val decimalFormatter = DecimalFormat("#,##0", DecimalFormatSymbols(locale))

    /**
     * Format amount with thousands separators and decimals.
     *
     * @param amount Raw amount in base units (BigInteger, must be non-negative)
     * @param tokenType Token type string (e.g., "DUST", "TNIGHT")
     * @param includeSymbol If true, append token symbol (e.g., " DUST")
     * @return Formatted string (e.g., "1,234.567890" or "1,234.567890 DUST")
     * @throws IllegalArgumentException if amount is negative
     */
    fun format(
        amount: BigInteger,
        tokenType: String,
        includeSymbol: Boolean = true
    ): String {
        require(amount >= BigInteger.ZERO) {
            "Amount cannot be negative: $amount"
        }

        val decimals = getDecimals(tokenType)
        val divisor = BigInteger.TEN.pow(decimals)

        // Split into integer and fractional parts
        val integerPart = amount.divide(divisor)
        val fractionalPart = amount.remainder(divisor)

        // Format integer part with thousands separators (using cached formatter)
        val formattedInteger = decimalFormatter.format(integerPart)

        // Format fractional part with leading zeros
        val fractionalStr = fractionalPart.toString().padStart(decimals, '0')

        val formatted = if (decimals > 0) {
            "$formattedInteger.$fractionalStr"
        } else {
            formattedInteger
        }

        return if (includeSymbol) {
            "$formatted $tokenType"
        } else {
            formatted
        }
    }

    /**
     * Format amount without token symbol (for calculations/comparisons).
     */
    fun formatAmount(amount: BigInteger, tokenType: String): String {
        return format(amount, tokenType, includeSymbol = false)
    }

    /**
     * Get decimal precision for token type.
     *
     * **Midnight Token Precision:**
     * - DUST: 15 decimals (1 DUST = 10^15 Specks, per midnight-ledger spec)
     * - TNIGHT/NIGHT: 6 decimals (1 NIGHT = 1,000,000 Stars)
     * - Custom tokens: Default 6 decimals
     */
    private fun getDecimals(tokenType: String): Int {
        return when (tokenType.uppercase()) {
            "DUST" -> DUST_TOKEN_DECIMALS
            "TNIGHT", "NIGHT" -> NIGHT_TOKEN_DECIMALS
            else -> DEFAULT_TOKEN_DECIMALS // Default for custom tokens
        }
    }

    /**
     * Format for display in lists (shorter format, truncate trailing zeros).
     *
     * Example:
     * - format() → "1.000000 DUST"
     * - formatCompact() → "1.0 DUST"
     */
    fun formatCompact(
        amount: BigInteger,
        tokenType: String,
        includeSymbol: Boolean = true
    ): String {
        val full = format(amount, tokenType, includeSymbol = false)

        // Remove trailing zeros after decimal point
        val compact = if (full.contains('.')) {
            full.trimEnd('0').trimEnd('.')
        } else {
            full
        }

        return if (includeSymbol) {
            "$compact $tokenType"
        } else {
            compact
        }
    }

    private companion object {
        // Midnight blockchain token precision
        // Reference: https://raw.githubusercontent.com/midnightntwrk/midnight-ledger/refs/heads/main/spec/dust.md
        //
        // DUST: 1 DUST = 10^15 Specks (SPECKS_PER_DUST = 1_000_000_000_000_000)
        // NIGHT: 1 NIGHT = 10^6 Stars (STARS_PER_NIGHT = 1_000_000)
        const val DUST_TOKEN_DECIMALS = 15
        const val NIGHT_TOKEN_DECIMALS = 6
        const val DEFAULT_TOKEN_DECIMALS = 6
    }
}
