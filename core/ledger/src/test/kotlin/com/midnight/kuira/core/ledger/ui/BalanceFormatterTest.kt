package com.midnight.kuira.core.ledger.ui

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.util.Locale

/**
 * Unit tests for BalanceFormatter.
 *
 * **Test Coverage:**
 * - Basic formatting with thousands separators
 * - Decimal precision (6 decimals for TNIGHT, 15 for DUST)
 * - Token symbol inclusion/exclusion
 * - Compact formatting (trim trailing zeros)
 * - Edge cases (zero, large amounts)
 * - Locale handling (US format)
 */
class BalanceFormatterTest {

    private lateinit var formatter: BalanceFormatter

    @Before
    fun setup() {
        formatter = BalanceFormatter()
    }

    // ==================== Basic Formatting ====================

    @Test
    fun `format zero amount`() {
        val result = formatter.format(BigInteger.ZERO, "TNIGHT")
        assertEquals("0.000000 TNIGHT", result)
    }

    @Test
    fun `format one token (1 million base units)`() {
        val result = formatter.format(BigInteger.valueOf(1_000_000), "TNIGHT")
        assertEquals("1.000000 TNIGHT", result)
    }

    @Test
    fun `format with thousands separators`() {
        val result = formatter.format(BigInteger.valueOf(1_234_567_890), "TNIGHT")
        assertEquals("1,234.567890 TNIGHT", result)
    }

    @Test
    fun `format large amount with multiple thousand separators`() {
        val result = formatter.format(BigInteger.valueOf(123_456_789_012_345), "TNIGHT")
        assertEquals("123,456,789.012345 TNIGHT", result)
    }

    // ==================== Decimal Precision ====================

    @Test
    fun `format preserves 6 decimal places for TNIGHT`() {
        val result = formatter.format(BigInteger.valueOf(1_123_456), "TNIGHT")
        assertEquals("1.123456 TNIGHT", result)
    }

    @Test
    fun `format preserves 15 decimal places for DUST`() {
        // 1 DUST = 10^15 Specks
        val oneDust = BigInteger("1000000000000000") // 10^15
        val result = formatter.format(oneDust, "DUST")
        assertEquals("1.000000000000000 DUST", result)
    }

    @Test
    fun `format handles fractional amounts correctly`() {
        // 0.000001 TNIGHT = 1 base unit
        val result = formatter.format(BigInteger.ONE, "TNIGHT")
        assertEquals("0.000001 TNIGHT", result)
    }

    // ==================== Symbol Inclusion ====================

    @Test
    fun `format without symbol`() {
        val result = formatter.format(BigInteger.valueOf(1_234_567), "TNIGHT", includeSymbol = false)
        assertEquals("1.234567", result)
    }

    @Test
    fun `formatAmount returns value without symbol`() {
        val result = formatter.formatAmount(BigInteger.valueOf(1_000_000), "TNIGHT")
        assertEquals("1.000000", result)
    }

    // ==================== Compact Formatting ====================

    @Test
    fun `formatCompact trims trailing zeros`() {
        val result = formatter.formatCompact(BigInteger.valueOf(1_000_000), "TNIGHT")
        assertEquals("1 TNIGHT", result)
    }

    @Test
    fun `formatCompact preserves significant decimals`() {
        val result = formatter.formatCompact(BigInteger.valueOf(1_234_560), "TNIGHT")
        assertEquals("1.23456 TNIGHT", result)
    }

    @Test
    fun `formatCompact trims partial trailing zeros`() {
        val result = formatter.formatCompact(BigInteger.valueOf(1_200_000), "TNIGHT")
        assertEquals("1.2 TNIGHT", result)
    }

    @Test
    fun `formatCompact without symbol`() {
        val result = formatter.formatCompact(BigInteger.valueOf(1_000_000), "TNIGHT", includeSymbol = false)
        assertEquals("1", result)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `format handles maximum Long value`() {
        val result = formatter.format(BigInteger.valueOf(Long.MAX_VALUE), "TNIGHT")
        // Should not throw, just format the huge number
        assert(result.contains("TNIGHT"))
        assert(result.contains(",")) // Has thousands separators
    }

    @Test
    fun `format handles small fractional amounts`() {
        // 0.000123 TNIGHT
        val result = formatter.format(BigInteger.valueOf(123), "TNIGHT")
        assertEquals("0.000123 TNIGHT", result)
    }

    // ==================== Token Types ====================

    @Test
    fun `format DUST token`() {
        // 5.5 DUST = 5.5 * 10^15 Specks
        val fiveAndHalfDust = BigInteger("5500000000000000")
        val result = formatter.format(fiveAndHalfDust, "DUST")
        assertEquals("5.500000000000000 DUST", result)
    }

    @Test
    fun `formatCompact works with DUST`() {
        // 5.5 DUST = 5.5 * 10^15 Specks
        val fiveAndHalfDust = BigInteger("5500000000000000")
        val result = formatter.formatCompact(fiveAndHalfDust, "DUST")
        assertEquals("5.5 DUST", result)
    }

    @Test
    fun `format DUST small amount in Specks`() {
        // 16037980000000 Specks ≈ 0.01604 DUST
        val specks = BigInteger("16037980000000")
        val result = formatter.formatCompact(specks, "DUST")
        assertEquals("0.01603798 DUST", result)
    }

    // ==================== Precision Tests ====================

    @Test
    fun `format does not round - shows exact 6 decimals`() {
        // 1.123456 TNIGHT (exact)
        val result = formatter.format(BigInteger.valueOf(1_123_456), "TNIGHT")
        assertEquals("1.123456 TNIGHT", result)
    }

    @Test
    fun `format shows all trailing zeros in standard format`() {
        // 1.100000 TNIGHT
        val result = formatter.format(BigInteger.valueOf(1_100_000), "TNIGHT")
        assertEquals("1.100000 TNIGHT", result)
    }

    @Test
    fun `formatCompact removes all trailing zeros after decimal`() {
        // 1.100000 → 1.1
        val result = formatter.formatCompact(BigInteger.valueOf(1_100_000), "TNIGHT")
        assertEquals("1.1 TNIGHT", result)
    }

    // ==================== Abbreviated (Pill / Chip Format) ====================
    //
    // Spec:
    //  - whole < 1,000      → integer (no fractional, no suffix)
    //  - 1K..999.9K         → one decimal, K suffix (trailing .0 dropped)
    //  - 1M..999.9M         → one decimal, M suffix
    //  - 1B..999.9B         → B suffix
    //  - 1T+                → T suffix
    //  - truncates (does not round) so dust accruing in the background
    //    doesn't visually jump the integer up

    @Test
    fun `formatAbbreviated zero`() {
        assertEquals("0", formatter.formatAbbreviated(BigInteger.ZERO, "NIGHT"))
    }

    @Test
    fun `formatAbbreviated below 1K shows integer only`() {
        // 1 NIGHT = 1,000,000 base units → "1"
        assertEquals("1", formatter.formatAbbreviated(BigInteger.valueOf(1_000_000), "NIGHT"))
        // 850 NIGHT → "850"
        assertEquals("850", formatter.formatAbbreviated(BigInteger.valueOf(850_000_000), "NIGHT"))
        // 999 NIGHT → "999"
        assertEquals("999", formatter.formatAbbreviated(BigInteger.valueOf(999_000_000), "NIGHT"))
    }

    @Test
    fun `formatAbbreviated truncates fractional below 1K`() {
        // 364.409… DUST (15 decimals) → "364" (no decimal point below 1K)
        val dustAmount = BigInteger("364409359999999999")
        assertEquals("364", formatter.formatAbbreviated(dustAmount, "DUST"))
    }

    @Test
    fun `formatAbbreviated K suffix exact thousand drops decimal`() {
        // 1,000 NIGHT → "1K" not "1.0K"
        assertEquals("1K", formatter.formatAbbreviated(BigInteger.valueOf(1_000_000_000), "NIGHT"))
        // 10,000 NIGHT → "10K"
        assertEquals("10K", formatter.formatAbbreviated(BigInteger.valueOf(10_000_000_000), "NIGHT"))
    }

    @Test
    fun `formatAbbreviated K suffix with one decimal`() {
        // 1,500 NIGHT → "1.5K"
        assertEquals("1.5K", formatter.formatAbbreviated(BigInteger.valueOf(1_500_000_000), "NIGHT"))
        // 12,700 NIGHT → "12.7K"
        assertEquals("12.7K", formatter.formatAbbreviated(BigInteger.valueOf(12_700_000_000L), "NIGHT"))
    }

    @Test
    fun `formatAbbreviated K suffix truncates not rounds`() {
        // 1,599 NIGHT → "1.5K" (not "1.6K") — truncating avoids surprise jumps
        // as dust accrues mid-display.
        assertEquals("1.5K", formatter.formatAbbreviated(BigInteger.valueOf(1_599_000_000), "NIGHT"))
        // 1,990 NIGHT → "1.9K"
        assertEquals("1.9K", formatter.formatAbbreviated(BigInteger.valueOf(1_990_000_000), "NIGHT"))
    }

    @Test
    fun `formatAbbreviated M suffix`() {
        // 1,000,000 NIGHT → "1M"
        assertEquals("1M", formatter.formatAbbreviated(BigInteger("1000000000000"), "NIGHT"))
        // 2,300,000 NIGHT → "2.3M"
        assertEquals("2.3M", formatter.formatAbbreviated(BigInteger("2300000000000"), "NIGHT"))
    }

    @Test
    fun `formatAbbreviated B suffix`() {
        // 1B NIGHT → "1B"
        assertEquals("1B", formatter.formatAbbreviated(BigInteger("1000000000000000"), "NIGHT"))
    }

    @Test
    fun `formatAbbreviated T suffix for very large amounts`() {
        // 5T NIGHT → "5T"
        assertEquals("5T", formatter.formatAbbreviated(BigInteger("5000000000000000000"), "NIGHT"))
    }

    @Test
    fun `formatAbbreviated K boundary 999_900 NIGHT stays in K`() {
        // 999,900 NIGHT → "999.9K" — just under the M boundary
        assertEquals("999.9K", formatter.formatAbbreviated(BigInteger("999900000000"), "NIGHT"))
    }

    @Test
    fun `formatAbbreviated DUST integers below 1K`() {
        // 1 DUST (10^15 base units) → "1"
        assertEquals("1", formatter.formatAbbreviated(BigInteger("1000000000000000"), "DUST"))
        // 999 DUST → "999"
        assertEquals("999", formatter.formatAbbreviated(BigInteger("999000000000000000"), "DUST"))
    }

    @Test
    fun `formatAbbreviated DUST K range`() {
        // 1,500 DUST → "1.5K"
        assertEquals("1.5K", formatter.formatAbbreviated(BigInteger("1500000000000000000"), "DUST"))
    }
}
