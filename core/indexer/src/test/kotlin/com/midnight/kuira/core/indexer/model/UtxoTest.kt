package com.midnight.kuira.core.indexer.model

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class UtxoTest {

    @Test
    fun `given valid utxo when created then succeeds`() {
        val utxo = Utxo(
            value = "1000000",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123abc",
            outputIndex = 0,
            ctime = 1704067200,
            registeredForDustGeneration = false
        )

        assertEquals("1000000", utxo.value)
        assertEquals("mn_addr_testnet1abc123", utxo.owner)
        assertEquals("DUST", utxo.tokenType)
        assertEquals("0x123abc", utxo.intentHash)
        assertEquals(0, utxo.outputIndex)
        assertEquals(1704067200L, utxo.ctime)
        assertFalse(utxo.registeredForDustGeneration)
    }

    @Test
    fun `given utxo when valueBigInt called then returns BigInteger`() {
        val utxo = Utxo(
            value = "999999999999999999",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )

        val bigInt = utxo.valueBigInt()

        assertEquals(BigInteger("999999999999999999"), bigInt)
    }

    @Test
    fun `given utxo with transactionHash when identifier called then returns transactionHash colon outputIndex`() {
        // Note: Utxo.identifier() uses transactionHash:outputIndex for LOCAL database storage.
        // The blockchain identifies UTXOs by intentHash + outputNo, but that's handled separately.
        val utxo = Utxo(
            value = "1000",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0xabcdef123",
            outputIndex = 5,
            ctime = 0,
            registeredForDustGeneration = false,
            transactionHash = "0xtxhash456"  // This is used for identifier()
        )

        val identifier = utxo.identifier()

        assertEquals("0xtxhash456:5", identifier)  // Uses transactionHash, not intentHash
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given blank value when creating utxo then throws exception`() {
        Utxo(
            value = "",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given blank owner when creating utxo then throws exception`() {
        Utxo(
            value = "1000",
            owner = "",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given blank tokenType when creating utxo then throws exception`() {
        Utxo(
            value = "1000",
            owner = "mn_addr_testnet1abc123",
            tokenType = "",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given blank intentHash when creating utxo then throws exception`() {
        Utxo(
            value = "1000",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given negative outputIndex when creating utxo then throws exception`() {
        Utxo(
            value = "1000",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = -1,
            ctime = 0,
            registeredForDustGeneration = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given negative ctime when creating utxo then throws exception`() {
        Utxo(
            value = "1000",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = -1,
            registeredForDustGeneration = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given invalid BigInteger value when creating utxo then throws exception`() {
        Utxo(
            value = "not-a-number",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )
    }

    @Test
    fun `given zero value when creating utxo then succeeds`() {
        val utxo = Utxo(
            value = "0",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )

        assertEquals(BigInteger.ZERO, utxo.valueBigInt())
    }

    @Test
    fun `given very large value when creating utxo then succeeds`() {
        val largeValue = "123456789012345678901234567890"
        val utxo = Utxo(
            value = largeValue,
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )

        assertEquals(BigInteger(largeValue), utxo.valueBigInt())
    }

    @Test
    fun `given multiple token types when creating utxos then succeeds`() {
        val dustUtxo = Utxo(
            value = "1000",
            owner = "mn_addr_testnet1abc123",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )

        val otherUtxo = Utxo(
            value = "2000",
            owner = "mn_addr_testnet1abc123",
            tokenType = "OTHER_TOKEN",
            intentHash = "0x456",
            outputIndex = 0,
            ctime = 0,
            registeredForDustGeneration = false
        )

        assertEquals("DUST", dustUtxo.tokenType)
        assertEquals("OTHER_TOKEN", otherUtxo.tokenType)
    }
}
