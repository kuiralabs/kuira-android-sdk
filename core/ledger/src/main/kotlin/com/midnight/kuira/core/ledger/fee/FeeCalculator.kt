// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.fee

import android.util.Log
import java.math.BigInteger

/**
 * Calculates transaction fees using midnight-ledger's fee calculation.
 *
 * **Fee Calculation Process:**
 * 1. Deserializes transaction from SCALE-encoded hex
 * 2. Deserializes ledger parameters from SCALE-encoded hex
 * 3. Calls `transaction.fees_with_margin(params, margin)`
 * 4. Adds safety overhead (0.3 Dust = 300 trillion Specks)
 * 5. Returns total fee in Specks
 *
 * **Safety Overhead:**
 * - Matches TypeScript SDK's `additionalFeeOverhead` parameter
 * - 0.3 Dust = 300,000,000,000,000 Specks
 * - Accounts for potential fee price fluctuations
 *
 * **Fee Blocks Margin:**
 * - Default: 5 blocks (typical value)
 * - Accounts for blockchain price changes between transaction creation and confirmation
 * - Higher margin = higher fee, but more safety against rejection
 *
 * @see `/midnight-wallet/packages/dust-wallet/src/Transacting.ts:274` (TypeScript SDK reference)
 */
object FeeCalculator {

    private const val TAG = "FeeCalculator"

    init {
        System.loadLibrary("kuira_crypto_ffi")
    }

    /**
     * Calculates transaction fee in Specks.
     *
     * @param transactionHex Hex-encoded SCALE-serialized transaction
     * @param ledgerParamsHex Hex-encoded SCALE-serialized ledger parameters
     * @param feeBlocksMargin Safety margin in blocks (default: 5)
     * @return Fee in Specks, or null if calculation fails
     *
     * @throws IllegalArgumentException if inputs are invalid format
     */
    fun calculateFee(
        transactionHex: String,
        ledgerParamsHex: String,
        feeBlocksMargin: Int = 5
    ): BigInteger? {
        require(transactionHex.isNotEmpty()) { "transactionHex cannot be empty" }
        require(ledgerParamsHex.isNotEmpty()) { "ledgerParamsHex cannot be empty" }
        require(feeBlocksMargin >= 0) { "feeBlocksMargin must be non-negative" }

        val feeString = nativeCalculateFee(transactionHex, ledgerParamsHex, feeBlocksMargin)
        if (feeString == null) {
            Log.e(TAG, "Fee calculation failed - check Rust logs")
            return null
        }

        return try {
            BigInteger(feeString)
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Failed to parse fee string: $feeString", e)
            null
        }
    }

    /**
     * JNI bridge to Rust FFI `calculate_transaction_fee()`.
     *
     * @param txHex Hex-encoded SCALE-serialized transaction
     * @param paramsHex Hex-encoded SCALE-serialized ledger parameters
     * @param margin Fee blocks margin
     * @return Fee in Specks as decimal string, or null on error
     */
    private external fun nativeCalculateFee(
        txHex: String,
        paramsHex: String,
        margin: Int
    ): String?
}
