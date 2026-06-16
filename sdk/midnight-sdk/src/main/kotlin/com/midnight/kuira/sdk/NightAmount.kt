package com.midnight.kuira.sdk

import java.math.BigInteger

/**
 * Decimal places NIGHT is denominated in: a raw [WalletBalance] amount is in 10^-[NIGHT_DECIMALS]
 * NIGHT units (the smallest unit). The one source of this scale — presentation layers
 * (dapp-ui, wallet-runtime notifications) reference this rather than re-hardcoding `6`.
 */
const val NIGHT_DECIMALS = 6

/**
 * Format a raw NIGHT balance (smallest units) as a human display string, trimming trailing
 * zeros: `5_000_000` → `"5"`, `1_500_000` → `"1.5"`, `0` → `"0"`.
 */
fun formatNight(base: BigInteger): String =
    base.toBigDecimal().movePointLeft(NIGHT_DECIMALS).stripTrailingZeros().toPlainString()
