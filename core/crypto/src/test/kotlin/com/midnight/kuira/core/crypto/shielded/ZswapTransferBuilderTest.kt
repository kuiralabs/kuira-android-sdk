package com.midnight.kuira.core.crypto.shielded

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for ZswapTransferBuilder — validation and JSON parsing.
 * These tests do NOT require the native library.
 *
 * Tests that require FFI calls are in androidTest/.
 */
class ZswapTransferBuilderTest {

    // ── Slice 1: Parse selected coins from FFI JSON ──

    @Test
    fun `given valid select_coins JSON when parsing then returns correct coins`() {
        // Given — JSON from zswap_select_coins FFI
        val json = """[{"type_hex":"0000000000000000000000000000000000000000000000000000000000000000","value":"5000000","nonce_hex":"abcd1234","mt_index":42,"nullifier_hex":"ff00ff00"}]"""

        // When
        val coins = ZswapTransferBuilder.parseSelectedCoins(json)

        // Then
        assertEquals(1, coins.size)
        val coin = coins[0]
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", coin.typeHex)
        assertEquals(BigInteger("5000000"), coin.value)
        assertEquals("abcd1234", coin.nonceHex)
        assertEquals(42L, coin.mtIndex)
        assertEquals("ff00ff00", coin.nullifierHex)
    }

    @Test
    fun `given multiple coins JSON when parsing then returns all coins`() {
        val json = """[
            {"type_hex":"aa","value":"100","nonce_hex":"bb","mt_index":0,"nullifier_hex":"cc"},
            {"type_hex":"aa","value":"200","nonce_hex":"dd","mt_index":1,"nullifier_hex":"ee"}
        ]"""

        val coins = ZswapTransferBuilder.parseSelectedCoins(json)

        assertEquals(2, coins.size)
        assertEquals(BigInteger("100"), coins[0].value)
        assertEquals(BigInteger("200"), coins[1].value)
    }

    @Test
    fun `given empty array JSON when parsing then returns empty list`() {
        val coins = ZswapTransferBuilder.parseSelectedCoins("[]")
        assertTrue(coins.isEmpty())
    }

    // ── Slice 2: Parse spend result ──

    @Test
    fun `given valid spend result JSON when parsing then returns SpendResult`() {
        val json = """{"new_state_ptr":12345,"input_hex":"aabb","binding_randomness_hex":"ccdd"}"""

        val result = ZswapTransferBuilder.parseSpendResult(json)

        assertNotNull(result)
        assertEquals(12345L, result!!.newStatePtr)
        assertEquals("aabb", result.inputHex)
        assertEquals("ccdd", result.bindingRandomnessHex)
    }

    // ── Slice 2: Parse output result ──

    @Test
    fun `given valid output result JSON when parsing then returns OutputResult`() {
        val json = """{"output_hex":"1122aabb","binding_randomness_hex":"3344ccdd"}"""

        val result = ZswapTransferBuilder.parseOutputResult(json)

        assertNotNull(result)
        assertEquals("1122aabb", result!!.outputHex)
        assertEquals("3344ccdd", result.bindingRandomnessHex)
    }

    // ── Slice 2: Parse offer result ──

    @Test
    fun `given valid offer result JSON when parsing then returns OfferResult`() {
        val json = """{"offer_hex":"deadbeef","binding_randomness_hex":"cafebabe"}"""

        val result = ZswapTransferBuilder.parseOfferResult(json)

        assertNotNull(result)
        assertEquals("deadbeef", result!!.offerHex)
        assertEquals("cafebabe", result.bindingRandomnessHex)
    }

    // ── Slice 2: SelectedCoin.toJson round-trip ──

    @Test
    fun `given SelectedCoin when serialized to JSON then can be parsed back`() {
        val coin = SelectedCoin(
            typeHex = "0000000000000000000000000000000000000000000000000000000000000000",
            value = BigInteger("7500000"),
            nonceHex = "aabbccdd",
            mtIndex = 99,
            nullifierHex = "eeff0011",
        )

        val json = coin.toJson()
        val parsed = ZswapTransferBuilder.parseSelectedCoins("[$json]")

        assertEquals(1, parsed.size)
        assertEquals(coin, parsed[0])
    }

    // ── Slice 3: Input validation ──

    @Test
    fun `given zero amount when validating then returns error`() {
        val error = ZswapTransferBuilder.validateTransferInputs(
            seed = ByteArray(32),
            recipientCoinPk = "aa".repeat(32),
            recipientEncPk = "bb".repeat(32),
            tokenType = "00".repeat(32),
            amount = BigInteger.ZERO,
        )
        assertNotNull(error)
        assertTrue("Error should mention amount: $error", error!!.contains("amount"))
    }

    @Test
    fun `given wrong seed length when validating then returns error`() {
        val error = ZswapTransferBuilder.validateTransferInputs(
            seed = ByteArray(16),
            recipientCoinPk = "aa".repeat(32),
            recipientEncPk = "bb".repeat(32),
            tokenType = "00".repeat(32),
            amount = BigInteger("1000000"),
        )
        assertNotNull(error)
        assertTrue("Error should mention seed: $error", error!!.contains("seed", ignoreCase = true))
    }

    @Test
    fun `given invalid hex coin pk when validating then returns error`() {
        val error = ZswapTransferBuilder.validateTransferInputs(
            seed = ByteArray(32),
            recipientCoinPk = "not_hex",
            recipientEncPk = "bb".repeat(32),
            tokenType = "00".repeat(32),
            amount = BigInteger("1000000"),
        )
        assertNotNull(error)
        assertTrue("Error should mention coin pk: $error", error!!.contains("coin", ignoreCase = true))
    }

    @Test
    fun `given valid inputs when validating then returns null`() {
        val error = ZswapTransferBuilder.validateTransferInputs(
            seed = ByteArray(32),
            recipientCoinPk = "aa".repeat(32),
            recipientEncPk = "bb".repeat(32),
            tokenType = "00".repeat(32),
            amount = BigInteger("1000000"),
        )
        assertNull("Valid inputs should return null error", error)
    }
}
