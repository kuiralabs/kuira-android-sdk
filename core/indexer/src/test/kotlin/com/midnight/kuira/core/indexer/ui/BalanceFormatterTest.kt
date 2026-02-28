package com.midnight.kuira.core.indexer.ui

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
}
