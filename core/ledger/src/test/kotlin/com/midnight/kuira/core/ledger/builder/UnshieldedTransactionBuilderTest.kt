// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.builder

import com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity
import com.midnight.kuira.core.indexer.database.UtxoState
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.indexer.utxo.UtxoSelector
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.model.UtxoSpend
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class UnshieldedTransactionBuilderTest {

    private lateinit var utxoManager: UtxoManager
    private lateinit var builder: UnshieldedTransactionBuilder

    private val senderAddress = "mn_addr_test_sender"
    private val recipientAddress = "mn_addr_test_recipient"
    private val tokenType = UtxoOutput.NATIVE_TOKEN_TYPE
    private val senderPublicKey = UtxoSpend.TEST_PUBLIC_KEY // 64 hex chars (32 bytes BIP-340 x-only)

    @Before
    fun setUp() {
        utxoManager = mockk()
        builder = UnshieldedTransactionBuilder(utxoManager)
    }

    // Helper to create test UTXO
    private fun createUtxo(
        value: Long,
        intentHash: String = "tx_hash_123",
        outputIndex: Int = 0,
        transactionHash: String = "transaction_hash_abc"
    ): UnshieldedUtxoEntity {
        return UnshieldedUtxoEntity(
            id = "$transactionHash:$outputIndex", // Database ID uses transactionHash
            transactionHash = transactionHash,
            intentHash = intentHash,
            outputIndex = outputIndex,
            owner = senderAddress,
            ownerPublicKey = UtxoSpend.TEST_PUBLIC_KEY, // Test public key for spending
            tokenType = tokenType,
            value = value.toString(),
            ctime = System.currentTimeMillis() / 1000,
            registeredForDustGeneration = false,
            state = UtxoState.AVAILABLE
        )
    }

    @Test
    fun `given exact UTXO amount when building transfer then creates intent without change`() = runTest {
        // Given - Select 100, need 100 (no change)
        val selectedUtxo = createUtxo(100)
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(selectedUtxo),
            totalSelected = BigInteger("100"),
            change = BigInteger.ZERO
        )

        // When
        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey
        )

        // Then
        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        val success = result as UnshieldedTransactionBuilder.BuildResult.Success
        val intent = success.intent

        // Verify intent structure
        assertNotNull(intent.guaranteedUnshieldedOffer)
        assertNull(intent.fallibleUnshieldedOffer)
        assertTrue(intent.ttl > System.currentTimeMillis())

        // Verify offer
        val offer = intent.guaranteedUnshieldedOffer!!
        assertEquals(1, offer.inputs.size)
        assertEquals(1, offer.outputs.size) // Only recipient, no change
        assertEquals(0, offer.signatures.size)

        // Verify input
        val input = offer.inputs[0]
        assertEquals("tx_hash_123", input.intentHash)
        assertEquals(0, input.outputNo)
        assertEquals(BigInteger("100"), input.value)
        assertEquals(senderAddress, input.owner)
        assertEquals(tokenType, input.tokenType)

        // Verify recipient output
        val output = offer.outputs[0]
        assertEquals(BigInteger("100"), output.value)
        assertEquals(recipientAddress, output.owner)
        assertEquals(tokenType, output.tokenType)

        // Verify lockedUtxos matches selected
        assertEquals(1, success.lockedUtxos.size)
        assertEquals(selectedUtxo.id, success.lockedUtxos[0].id)
    }

    @Test
    fun `given UTXO larger than amount when building transfer then creates change output`() = runTest {
        // Given - Select 150, need 100 (change = 50)
        val selectedUtxo = createUtxo(150)
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(selectedUtxo),
            totalSelected = BigInteger("150"),
            change = BigInteger("50")
        )

        // When
        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey
        )

        // Then
        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        val success = result as UnshieldedTransactionBuilder.BuildResult.Success
        val offer = success.intent.guaranteedUnshieldedOffer!!

        assertEquals(1, offer.inputs.size)
        assertEquals(2, offer.outputs.size) // Recipient + change

        // Verify recipient output (first output)
        assertEquals(BigInteger("100"), offer.outputs[0].value)
        assertEquals(recipientAddress, offer.outputs[0].owner)
        assertEquals(tokenType, offer.outputs[0].tokenType)

        // Verify change output (second output)
        assertEquals(BigInteger("50"), offer.outputs[1].value)
        assertEquals(senderAddress, offer.outputs[1].owner) // Back to sender
        assertEquals(tokenType, offer.outputs[1].tokenType)

        // Verify lockedUtxos matches selected
        assertEquals(1, success.lockedUtxos.size)
        assertEquals(selectedUtxo.id, success.lockedUtxos[0].id)
    }

    @Test
    fun `given multiple UTXOs when building transfer then includes all in inputs`() = runTest {
        // Given - Select 3 UTXOs (30 + 40 + 50 = 120), need 100 (change = 20)
        val utxo1 = createUtxo(30, "tx1", 0, "txhash1")
        val utxo2 = createUtxo(40, "tx2", 1, "txhash2")
        val utxo3 = createUtxo(50, "tx3", 2, "txhash3")

        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(utxo1, utxo2, utxo3),
            totalSelected = BigInteger("120"),
            change = BigInteger("20")
        )

        // When
        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey
        )

        // Then
        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        val success = result as UnshieldedTransactionBuilder.BuildResult.Success
        val offer = success.intent.guaranteedUnshieldedOffer!!

        // Verify all 3 inputs included with complete field verification
        assertEquals(3, offer.inputs.size)

        // Input 0 (from utxo1)
        assertEquals("tx1", offer.inputs[0].intentHash)
        assertEquals(0, offer.inputs[0].outputNo)
        assertEquals(BigInteger("30"), offer.inputs[0].value)
        assertEquals(senderAddress, offer.inputs[0].owner)
        assertEquals(tokenType, offer.inputs[0].tokenType)

        // Input 1 (from utxo2)
        assertEquals("tx2", offer.inputs[1].intentHash)
        assertEquals(1, offer.inputs[1].outputNo)
        assertEquals(BigInteger("40"), offer.inputs[1].value)
        assertEquals(senderAddress, offer.inputs[1].owner)
        assertEquals(tokenType, offer.inputs[1].tokenType)

        // Input 2 (from utxo3)
        assertEquals("tx3", offer.inputs[2].intentHash)
        assertEquals(2, offer.inputs[2].outputNo)
        assertEquals(BigInteger("50"), offer.inputs[2].value)
        assertEquals(senderAddress, offer.inputs[2].owner)
        assertEquals(tokenType, offer.inputs[2].tokenType)

        // Verify outputs (recipient + change)
        assertEquals(2, offer.outputs.size)
        assertEquals(BigInteger("100"), offer.outputs[0].value) // Recipient
        assertEquals(recipientAddress, offer.outputs[0].owner)
        assertEquals(tokenType, offer.outputs[0].tokenType)
        assertEquals(BigInteger("20"), offer.outputs[1].value) // Change
        assertEquals(senderAddress, offer.outputs[1].owner)
        assertEquals(tokenType, offer.outputs[1].tokenType)

        // Verify lockedUtxos matches all selected
        assertEquals(3, success.lockedUtxos.size)
        assertEquals(utxo1.id, success.lockedUtxos[0].id)
        assertEquals(utxo2.id, success.lockedUtxos[1].id)
        assertEquals(utxo3.id, success.lockedUtxos[2].id)
    }

    @Test
    fun `given largestFirst when building transfer then selects via largest-first (fewest inputs)`() = runTest {
        // : NIGHT transfers pass largestFirst=true to minimize the input count
        // (the ledger time-to-dismiss budget caps a fee-paying transfer at ~2 inputs).
        // The builder must forward the flag to the selector; default stays smallest-first.
        val selectedUtxo = createUtxo(150)
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"), largestFirst = true)
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(selectedUtxo),
            totalSelected = BigInteger("150"),
            change = BigInteger("50"),
        )

        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey,
            largestFirst = true,
        )

        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        coVerify(exactly = 1) {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"), largestFirst = true)
        }
        coVerify(exactly = 0) {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"), largestFirst = false)
        }
    }

    @Test
    fun `given insufficient funds when building transfer then returns InsufficientFunds`() = runTest {
        // Given
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.InsufficientFunds(
            required = BigInteger("100"),
            available = BigInteger("50"),
            shortfall = BigInteger("50")
        )

        // When
        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey
        )

        // Then
        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.InsufficientFunds)
        val failure = result as UnshieldedTransactionBuilder.BuildResult.InsufficientFunds
        assertEquals(BigInteger("100"), failure.required)
        assertEquals(BigInteger("50"), failure.available)
        assertEquals(BigInteger("50"), failure.shortfall)
    }

    @Test
    fun `given custom TTL when building transfer then uses custom TTL`() = runTest {
        // Given
        val selectedUtxo = createUtxo(100)
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(selectedUtxo),
            totalSelected = BigInteger("100"),
            change = BigInteger.ZERO
        )

        val customTtlMinutes = 60

        // When
        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey,
            ttlMinutes = customTtlMinutes
        )

        // Then
        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        val success = result as UnshieldedTransactionBuilder.BuildResult.Success

        // TTL should be current time + 60 minutes (with some tolerance)
        val expectedTtl = System.currentTimeMillis() + (customTtlMinutes * 60 * 1000)
        val actualTtl = success.intent.ttl

        // Allow 1 second tolerance
        assertTrue(Math.abs(actualTtl - expectedTtl) < 1000)
    }

    @Test
    fun `given default TTL when building transfer then uses 30 minutes`() = runTest {
        // Given
        val selectedUtxo = createUtxo(100)
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(selectedUtxo),
            totalSelected = BigInteger("100"),
            change = BigInteger.ZERO
        )

        // When - Don't specify ttlMinutes (use default)
        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey
        )

        // Then
        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        val success = result as UnshieldedTransactionBuilder.BuildResult.Success

        // TTL should be current time + 30 minutes (with some tolerance)
        val expectedTtl = System.currentTimeMillis() + (30 * 60 * 1000)
        val actualTtl = success.intent.ttl

        // Allow 1 second tolerance
        assertTrue(Math.abs(actualTtl - expectedTtl) < 1000)
    }

    @Test
    fun `given chain-block time when building transfer then TTL anchors to it not wall-clock`() = runTest {
        // REGRESSION GUARD — node error 182 (TransactionApplicationError / IntentTtlTooFarInFuture).
        // The intent TTL MUST be computed from the supplied CHAIN block time, never the device's
        // System.currentTimeMillis(): a node validates the TTL against its chain time, and on a
        // network where the two diverge (localnet) a wall-clock TTL lands outside the accepted
        // window. A fixed timestamp years from "now" makes this fail the instant the build reverts
        // to wall-clock. See UnshieldedTransactionBuilder + the dust-registration anchoring.
        val selectedUtxo = createUtxo(100)
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(selectedUtxo),
            totalSelected = BigInteger("100"),
            change = BigInteger.ZERO,
        )

        val chainNowMs = 1_700_000_000_000L // fixed (~Nov 2023), deliberately far from System.now()
        val ttlMinutes = 30

        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey,
            ttlMinutes = ttlMinutes,
            nowMs = chainNowMs,
        )

        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        val ttl = (result as UnshieldedTransactionBuilder.BuildResult.Success).intent.ttl
        // EXACT — anchored to chainNowMs with no wall-clock leakage.
        assertEquals(chainNowMs + ttlMinutes * 60 * 1000L, ttl)
    }

    @Test
    fun `given ttlSecondsOverride when building transfer then exact seconds window wins over ttlMinutes`() = runTest {
        // The SDK sizes ttlSecondsOverride to the chain's global_ttl; it must win over the coarse
        // ttlMinutes default so a 30-min window can't overshoot a tight node (custom error 182).
        val selectedUtxo = createUtxo(100)
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(selectedUtxo),
            totalSelected = BigInteger("100"),
            change = BigInteger.ZERO,
        )

        val chainNowMs = 1_700_000_000_000L
        val overrideSecs = 85L // e.g. a localnet global_ttl of 100s minus margin

        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey,
            ttlMinutes = 30, // deliberately large — must be ignored when the override is set
            nowMs = chainNowMs,
            ttlSecondsOverride = overrideSecs,
        )

        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        val ttl = (result as UnshieldedTransactionBuilder.BuildResult.Success).intent.ttl
        assertEquals(chainNowMs + overrideSecs * 1000L, ttl)
    }

    @Test
    fun `given zero amount when building transfer then throws IllegalArgumentException`() {
        // When/Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runTest {
                builder.buildTransfer(
                    from = senderAddress,
                    to = recipientAddress,
                    amount = BigInteger.ZERO,
                    tokenType = tokenType,
                    senderPublicKey = senderPublicKey
                )
            }
        }

        // Verify exception message
        assertTrue(exception.message?.contains("positive") == true)

        // Verify UtxoManager was never called (validation happens first)
        coVerify(exactly = 0) { utxoManager.selectAndLockUtxos(any(), any(), any()) }
    }

    @Test
    fun `given negative amount when building transfer then throws IllegalArgumentException`() {
        // When/Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runTest {
                builder.buildTransfer(
                    from = senderAddress,
                    to = recipientAddress,
                    amount = BigInteger("-100"),
                    tokenType = tokenType,
                    senderPublicKey = senderPublicKey
                )
            }
        }

        // Verify exception message
        assertTrue(exception.message?.contains("positive") == true)

        // Verify UtxoManager was never called (validation happens first)
        coVerify(exactly = 0) { utxoManager.selectAndLockUtxos(any(), any(), any()) }
    }

    @Test
    fun `given blank sender address when building transfer then throws IllegalArgumentException`() {
        // When/Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runTest {
                builder.buildTransfer(
                    from = "",
                    to = recipientAddress,
                    amount = BigInteger("100"),
                    tokenType = tokenType,
                    senderPublicKey = senderPublicKey
                )
            }
        }

        // Verify exception message
        assertTrue(exception.message?.contains("Sender address") == true)

        // Verify UtxoManager was never called (validation happens first)
        coVerify(exactly = 0) { utxoManager.selectAndLockUtxos(any(), any(), any()) }
    }

    @Test
    fun `given blank recipient address when building transfer then throws IllegalArgumentException`() {
        // When/Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runTest {
                builder.buildTransfer(
                    from = senderAddress,
                    to = "",
                    amount = BigInteger("100"),
                    tokenType = tokenType,
                    senderPublicKey = senderPublicKey
                )
            }
        }

        // Verify exception message
        assertTrue(exception.message?.contains("Recipient address") == true)

        // Verify UtxoManager was never called (validation happens first)
        coVerify(exactly = 0) { utxoManager.selectAndLockUtxos(any(), any(), any()) }
    }

    @Test
    fun `given transfer with change when building then outputs are ordered recipient then change`() = runTest {
        // Given
        val selectedUtxo = createUtxo(150)
        coEvery {
            utxoManager.selectAndLockUtxos(senderAddress, tokenType, BigInteger("100"))
        } returns UtxoSelector.SelectionResult.Success(
            selectedUtxos = listOf(selectedUtxo),
            totalSelected = BigInteger("150"),
            change = BigInteger("50")
        )

        // When
        val result = builder.buildTransfer(
            from = senderAddress,
            to = recipientAddress,
            amount = BigInteger("100"),
            tokenType = tokenType,
            senderPublicKey = senderPublicKey
        )

        // Then
        assertTrue(result is UnshieldedTransactionBuilder.BuildResult.Success)
        val success = result as UnshieldedTransactionBuilder.BuildResult.Success
        val offer = success.intent.guaranteedUnshieldedOffer!!

        // Verify output ordering: recipient first, change second
        assertEquals(2, offer.outputs.size)
        assertEquals(recipientAddress, offer.outputs[0].owner) // Recipient MUST be first
        assertEquals(BigInteger("100"), offer.outputs[0].value)
        assertEquals(senderAddress, offer.outputs[1].owner) // Change MUST be second
        assertEquals(BigInteger("50"), offer.outputs[1].value)
    }
}
