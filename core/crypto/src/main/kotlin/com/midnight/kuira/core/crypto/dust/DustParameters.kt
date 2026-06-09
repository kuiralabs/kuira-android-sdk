// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.dust

/**
 * Parameters for Dust generation and decay.
 *
 * **What are Dust Parameters?**
 * These control how Dust tokens generate and decay over time:
 * - **Night Dust Ratio**: Maximum dust a Night UTXO can generate (capacity)
 * - **Generation/Decay Rate**: How fast dust accumulates and decays
 *
 * **Dust Generation Formula:**
 * ```
 * max_capacity = night_value × night_dust_ratio
 * generation_rate = night_value × generation_decay_rate
 * decay_rate = night_value × generation_decay_rate (same as generation)
 * ```
 *
 * **Example:**
 * ```
 * Night value: 100 NIGHT
 * night_dust_ratio: 1000
 * generation_decay_rate: 10
 *
 * → Max capacity: 100 × 1000 = 100,000 Specks
 * → Generation rate: 100 × 10 = 1,000 Specks/second
 * → Time to full: 100,000 / 1,000 = 100 seconds
 * → Decay rate: 1,000 Specks/second (back to zero in 100 seconds)
 * ```
 *
 * **Ledger Mapping:**
 * Mirrors the ledger's DustParameters: the Night-to-dust ratio, the
 * generation/decay rate, and a grace period. The ratio is an unsigned 64-bit
 * value (mapped to Kotlin `Long`) and the rate is an unsigned 32-bit value
 * (mapped to Kotlin `Int`); both ranges are sufficient for valid positive values.
 *
 * **Network Defaults:**
 * - Testnet: Uses the initial dust parameters.
 * - Mainnet: Determined by governance.
 *
 * **Usage:**
 * ```kotlin
 * val params = DustParameters.TESTNET_DEFAULTS
 *
 * // Calculate max capacity for a Night UTXO
 * val nightValue = BigInteger.valueOf(100_000_000) // 100 NIGHT
 * val maxDust = params.calculateMaxCapacity(nightValue)
 * println("Max dust: $maxDust Specks")
 *
 * // Calculate generation rate
 * val rate = params.calculateGenerationRate(nightValue)
 * println("Generation: $rate Specks/second")
 * ```
 *
 * @property nightDustRatio Ratio of Night value to maximum dust capacity (u64 → Long)
 * @property generationDecayRate Rate of dust generation and decay per second (u32 → Int)
 */
data class DustParameters(
    val nightDustRatio: Long,
    val generationDecayRate: Int
) {
    /**
     * Calculates the maximum dust capacity for a given Night value.
     *
     * **Formula:**
     * ```
     * max_capacity = night_value × night_dust_ratio
     * ```
     *
     * @param nightValue Value of the Night UTXO in Specks
     * @return Maximum dust capacity in Specks
     */
    fun calculateMaxCapacity(nightValue: java.math.BigInteger): java.math.BigInteger {
        return nightValue.multiply(java.math.BigInteger.valueOf(nightDustRatio))
    }

    /**
     * Calculates the generation rate for a given Night value.
     *
     * **Formula:**
     * ```
     * generation_rate = night_value × generation_decay_rate
     * ```
     *
     * @param nightValue Value of the Night UTXO in Specks
     * @return Dust generation rate in Specks per second
     */
    fun calculateGenerationRate(nightValue: java.math.BigInteger): java.math.BigInteger {
        return nightValue.multiply(java.math.BigInteger.valueOf(generationDecayRate.toLong()))
    }

    /**
     * Calculates the decay rate for a given Night value.
     *
     * **Note:** Decay rate equals generation rate in Midnight's design.
     *
     * @param nightValue Value of the Night UTXO in Specks
     * @return Dust decay rate in Specks per second
     */
    fun calculateDecayRate(nightValue: java.math.BigInteger): java.math.BigInteger {
        return calculateGenerationRate(nightValue)
    }

    /**
     * Calculates the time to reach full capacity for a given Night value.
     *
     * **Formula:**
     * ```
     * time_to_full = max_capacity / generation_rate
     * ```
     *
     * @param nightValue Value of the Night UTXO in Specks
     * @param initialDust Initial dust value (default 0)
     * @return Time to full capacity in seconds
     */
    fun calculateTimeToFull(
        nightValue: java.math.BigInteger,
        initialDust: java.math.BigInteger = java.math.BigInteger.ZERO
    ): Long {
        val capacity = calculateMaxCapacity(nightValue)
        val rate = calculateGenerationRate(nightValue)

        if (rate == java.math.BigInteger.ZERO) {
            return Long.MAX_VALUE // Never fills if rate is zero
        }

        val dustToGenerate = capacity.subtract(initialDust)
        if (dustToGenerate <= java.math.BigInteger.ZERO) {
            return 0 // Already full
        }

        return dustToGenerate.divide(rate).toLong()
    }

    /**
     * Calculates the time to decay to zero for a given Night value.
     *
     * **Formula:**
     * ```
     * time_to_empty = current_dust / decay_rate
     * ```
     *
     * @param nightValue Value of the Night UTXO in Specks
     * @param currentDust Current dust value (default: max capacity)
     * @return Time to decay to zero in seconds
     */
    fun calculateTimeToEmpty(
        nightValue: java.math.BigInteger,
        currentDust: java.math.BigInteger = calculateMaxCapacity(nightValue)
    ): Long {
        val rate = calculateDecayRate(nightValue)

        if (rate == java.math.BigInteger.ZERO) {
            return Long.MAX_VALUE // Never decays if rate is zero
        }

        if (currentDust <= java.math.BigInteger.ZERO) {
            return 0 // Already empty
        }

        return currentDust.divide(rate).toLong()
    }

    companion object {
        /**
         * Testnet default parameters, matching the ledger's initial dust parameters.
         *
         * - `nightDustRatio` = 5,000,000,000 (5 Dust per Night).
         * - `generationDecayRate` = 8,267 (generation time of roughly one week).
         *
         * Reference constants: 1 Dust = 1,000,000,000,000,000 Specks (10^15) and
         * 1 NIGHT = 1,000,000 Stars (10^6).
         */
        val TESTNET_DEFAULTS = DustParameters(
            nightDustRatio = 5_000_000_000L,  // u64: 5 billion (5 Dust per Night)
            generationDecayRate = 8_267        // u32: ~1 week generation time
        )

        /**
         * Mainnet parameters.
         *
         * **Note:** Mainnet values are determined by Midnight governance; until
         * then these mirror the testnet defaults.
         */
        val MAINNET_DEFAULTS = TESTNET_DEFAULTS

        /**
         * Validates dust parameters are reasonable.
         *
         * @param params Parameters to validate
         * @return true if valid, false otherwise
         */
        fun isValid(params: DustParameters): Boolean {
            // Ratios and rates must be positive
            if (params.nightDustRatio <= 0 || params.generationDecayRate <= 0) {
                return false
            }

            return true
        }
    }
}
