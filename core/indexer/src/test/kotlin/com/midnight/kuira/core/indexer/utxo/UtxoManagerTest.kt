package com.midnight.kuira.core.indexer.utxo

import com.midnight.kuira.core.indexer.database.UnshieldedUtxoDao
import com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity
import com.midnight.kuira.core.indexer.database.UtxoState
import com.midnight.kuira.core.indexer.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.math.BigInteger

class UtxoManagerTest {

    private lateinit var mockDao: UnshieldedUtxoDao
    private lateinit var utxoManager: UtxoManager

    @Before
    fun setup() {
        mockDao = mock()
        utxoManager = UtxoManager(mockDao)
    }

    // Helper to create test UTXO entity with named parameters
    private fun createUtxoEntity(
        id: String,
        intentHash: String,
        outputIndex: Int = 0,
        owner: String,
        ownerPublicKey: String? = null,
        tokenType: String,
        value: String,
        ctime: Long = 1,
        registeredForDustGeneration: Boolean = false,
        state: UtxoState = UtxoState.AVAILABLE,
        transactionHash: String = "tx_$intentHash"  // Default to derived from intentHash for tests
    ): UnshieldedUtxoEntity {
        return UnshieldedUtxoEntity(
            id = id,
            transactionHash = transactionHash,
            intentHash = intentHash,
            outputIndex = outputIndex,
            owner = owner,
            ownerPublicKey = ownerPublicKey,
            tokenType = tokenType,
            value = value,
            ctime = ctime,
            registeredForDustGeneration = registeredForDustGeneration,
            state = state
        )
    }

    @Test
    fun `given transaction update when processUpdate then inserts created and marks spent`() = runBlocking {
        // Given
        val createdUtxo = Utxo(
            value = "1000",
            owner = "mn_addr_testnet1abc",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 1,
            registeredForDustGeneration = false
        )
        val spentUtxo = Utxo(
            value = "500",
            owner = "mn_addr_testnet1abc",
            tokenType = "DUST",
            intentHash = "0x456",
            outputIndex = 0,
            ctime = 2,
            registeredForDustGeneration = false
        )

        val transaction = UnshieldedTransaction(
            id = 100,
            hash = "0xtxhash",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            block = UnshieldedTransaction.BlockInfo(timestamp = 1704067200000),
            transactionResult = UnshieldedTransaction.TransactionResult(
                status = TransactionStatus.SUCCESS,
                segments = null
            )
        )

        val update = UnshieldedTransactionUpdate.Transaction(
            transaction = transaction,
            createdUtxos = listOf(createdUtxo),
            spentUtxos = listOf(spentUtxo)
        )

        // Mock: getUtxoByIntentHash returns existing entity for spent UTXO lookup
        val existingSpentEntity = createUtxoEntity(
            id = "tx_0x456:0",
            intentHash = "0x456",
            outputIndex = 0,
            owner = "mn_addr_testnet1abc",
            tokenType = "DUST",
            value = "500",
            transactionHash = "tx_0x456"
        )
        whenever(mockDao.getUtxoByIntentHash("0x456", 0)).thenReturn(existingSpentEntity)

        // When
        val result = utxoManager.processUpdate(update)

        // Then
        verify(mockDao).insertUtxos(argThat { list ->
            list.size == 1 &&
            list[0].intentHash == "0x123" &&  // Check intentHash, not id
            list[0].value == "1000" &&
            list[0].state == UtxoState.AVAILABLE
        })
        verify(mockDao).markAsSpent(listOf("tx_0x456:0"))  // Uses database ID (transactionHash:outputIndex)

        assertTrue(result is UtxoManager.ProcessingResult.TransactionProcessed)
        val txResult = result as UtxoManager.ProcessingResult.TransactionProcessed
        assertEquals(100, txResult.transactionId)
        assertEquals("0xtxhash", txResult.transactionHash)
        assertEquals(1, txResult.createdCount)
        assertEquals(1, txResult.spentCount)
        assertEquals(TransactionStatus.SUCCESS, txResult.status)
    }

    @Test
    fun `given transaction with only created utxos when processUpdate then only inserts`() = runBlocking {
        // Given
        val createdUtxo = Utxo(
            value = "1000",
            owner = "addr",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 1,
            registeredForDustGeneration = false
        )
        val transaction = UnshieldedTransaction(
            id = 100,
            hash = "0xtx",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            block = UnshieldedTransaction.BlockInfo(0)
        )
        val update = UnshieldedTransactionUpdate.Transaction(
            transaction = transaction,
            createdUtxos = listOf(createdUtxo),
            spentUtxos = emptyList()
        )

        // When
        utxoManager.processUpdate(update)

        // Then
        verify(mockDao).insertUtxos(any())
        verify(mockDao, never()).markAsSpent(any<List<String>>())
    }

    @Test
    fun `given transaction with only spent utxos when processUpdate then only marks spent`() = runBlocking {
        // Given
        val spentUtxo = Utxo(
            value = "1000",
            owner = "addr",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 1,
            registeredForDustGeneration = false
        )
        val transaction = UnshieldedTransaction(
            id = 100,
            hash = "0xtx",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            block = UnshieldedTransaction.BlockInfo(0)
        )
        val update = UnshieldedTransactionUpdate.Transaction(
            transaction = transaction,
            createdUtxos = emptyList(),
            spentUtxos = listOf(spentUtxo)
        )

        // Mock: getUtxoByIntentHash returns existing entity for spent UTXO lookup
        val existingSpentEntity = createUtxoEntity(
            id = "tx_0x123:0",
            intentHash = "0x123",
            outputIndex = 0,
            owner = "addr",
            tokenType = "DUST",
            value = "1000",
            transactionHash = "tx_0x123"
        )
        whenever(mockDao.getUtxoByIntentHash("0x123", 0)).thenReturn(existingSpentEntity)

        // When
        utxoManager.processUpdate(update)

        // Then
        verify(mockDao, never()).insertUtxos(any())
        verify(mockDao).markAsSpent(listOf("tx_0x123:0"))  // Uses database ID
    }

    @Test
    fun `given progress update when processUpdate then returns progress result`() = runBlocking {
        // Given
        val update = UnshieldedTransactionUpdate.Progress(
            highestTransactionId = 500
        )

        // When
        val result = utxoManager.processUpdate(update)

        // Then
        assertTrue(result is UtxoManager.ProcessingResult.ProgressUpdate)
        val progressResult = result as UtxoManager.ProcessingResult.ProgressUpdate
        assertEquals(500, progressResult.highestTransactionId)

        // Verify no database operations
        verifyNoInteractions(mockDao)
    }

    @Test
    fun `given system transaction when processUpdate then status is SUCCESS`() = runBlocking {
        // Given
        val transaction = UnshieldedTransaction(
            id = 100,
            hash = "0xtx",
            type = TransactionType.SystemTransaction,
            protocolVersion = 1,
            block = UnshieldedTransaction.BlockInfo(0),
            transactionResult = null // System transactions don't have results
        )
        val update = UnshieldedTransactionUpdate.Transaction(
            transaction = transaction,
            createdUtxos = emptyList(),
            spentUtxos = emptyList()
        )

        // When
        val result = utxoManager.processUpdate(update) as UtxoManager.ProcessingResult.TransactionProcessed

        // Then
        assertEquals(TransactionStatus.SUCCESS, result.status)
    }

    @Test
    fun `given address with utxos when calculateBalance then groups by token type`() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val utxos = listOf(
            createUtxoEntity(id = "0x1:0", intentHash = "0x1", outputIndex = 0, owner = address, tokenType = "DUST", value = "1000", ctime = 1),
            createUtxoEntity(id = "0x2:0", intentHash = "0x2", outputIndex = 0, owner = address, tokenType = "DUST", value = "2000", ctime = 2),
            createUtxoEntity(id = "0x3:0", intentHash = "0x3", outputIndex = 0, owner = address, tokenType = "OTHER", value = "5000", ctime = 3)
        )
        whenever(mockDao.getUnspentUtxos(address)).thenReturn(utxos)

        // When
        val balance = utxoManager.calculateBalance(address)

        // Then
        assertEquals(2, balance.size)
        assertEquals(BigInteger("3000"), balance["DUST"]) // 1000 + 2000
        assertEquals(BigInteger("5000"), balance["OTHER"])
    }

    @Test
    fun `given address with no utxos when calculateBalance then returns empty map`() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        whenever(mockDao.getUnspentUtxos(address)).thenReturn(emptyList())

        // When
        val balance = utxoManager.calculateBalance(address)

        // Then
        assertTrue(balance.isEmpty())
    }

    @Test
    fun `given address when observeBalance then returns Flow of balances`() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val utxos = listOf(
            createUtxoEntity(id = "0x1:0", intentHash = "0x1", outputIndex = 0, owner = address, tokenType = "DUST", value = "1000")
        )
        whenever(mockDao.observeUnspentUtxos(address)).thenReturn(flowOf(utxos))

        // When
        val balanceFlow = utxoManager.observeBalance(address)
        val balance = balanceFlow.first()

        // Then
        assertEquals(BigInteger("1000"), balance["DUST"])
    }

    @Test
    fun `given address when getUnspentUtxos then returns from DAO`() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val expected = listOf(
            createUtxoEntity(id = "0x1:0", intentHash = "0x1", outputIndex = 0, owner = address, tokenType = "DUST", value = "1000")
        )
        whenever(mockDao.getUnspentUtxos(address)).thenReturn(expected)

        // When
        val result = utxoManager.getUnspentUtxos(address)

        // Then
        assertEquals(expected, result)
    }

    @Test
    fun `given address when clearUtxos then calls DAO deleteUtxosForAddress`() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"

        // When
        utxoManager.clearUtxos(address)

        // Then
        verify(mockDao).deleteUtxosForAddress(address)
    }

    @Test
    fun `given multiple utxos of same type when calculateBalance then sums correctly`() = runBlocking {
        // Given
        val address = "mn_addr"
        val utxos = listOf(
            createUtxoEntity(id = "0x1:0", intentHash = "0x1", outputIndex = 0, owner = address, tokenType = "DUST", value = "100", ctime = 1),
            createUtxoEntity(id = "0x2:0", intentHash = "0x2", outputIndex = 0, owner = address, tokenType = "DUST", value = "200", ctime = 2),
            createUtxoEntity(id = "0x3:0", intentHash = "0x3", outputIndex = 0, owner = address, tokenType = "DUST", value = "300", ctime = 3),
            createUtxoEntity(id = "0x4:0", intentHash = "0x4", outputIndex = 0, owner = address, tokenType = "DUST", value = "400", ctime = 4)
        )
        whenever(mockDao.getUnspentUtxos(address)).thenReturn(utxos)

        // When
        val balance = utxoManager.calculateBalance(address)

        // Then
        assertEquals(BigInteger("1000"), balance["DUST"])
    }

    @Test
    fun `given very large utxo values when calculateBalance then handles correctly`() = runBlocking {
        // Given
        val address = "mn_addr"
        val largeValue = "999999999999999999999999999999"
        val utxos = listOf(
            createUtxoEntity(id = "0x1:0", intentHash = "0x1", outputIndex = 0, owner = address, tokenType = "DUST", value = largeValue, ctime = 1),
            createUtxoEntity(id = "0x2:0", intentHash = "0x2", outputIndex = 0, owner = address, tokenType = "DUST", value = largeValue, ctime = 2)
        )
        whenever(mockDao.getUnspentUtxos(address)).thenReturn(utxos)

        // When
        val balance = utxoManager.calculateBalance(address)

        // Then
        val expected = BigInteger(largeValue).multiply(BigInteger.TWO)
        assertEquals(expected, balance["DUST"])
    }

    @Test
    fun `given failed transaction when processUpdate then status is FAILURE`() = runBlocking {
        // Given
        val transaction = UnshieldedTransaction(
            id = 100,
            hash = "0xtx",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            block = UnshieldedTransaction.BlockInfo(0),
            transactionResult = UnshieldedTransaction.TransactionResult(
                status = TransactionStatus.FAILURE,
                segments = null
            )
        )
        val update = UnshieldedTransactionUpdate.Transaction(
            transaction = transaction,
            createdUtxos = emptyList(),
            spentUtxos = emptyList()
        )

        // When
        val result = utxoManager.processUpdate(update) as UtxoManager.ProcessingResult.TransactionProcessed

        // Then
        assertEquals(TransactionStatus.FAILURE, result.status)
    }

    @Test
    fun `given failed transaction with spent utxos when processUpdate then unlocks utxos`() = runBlocking {
        // Given
        val spentUtxo1 = Utxo(
            value = "1000",
            owner = "addr",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 1,
            registeredForDustGeneration = false
        )
        val spentUtxo2 = Utxo(
            value = "2000",
            owner = "addr",
            tokenType = "DUST",
            intentHash = "0x456",
            outputIndex = 0,
            ctime = 2,
            registeredForDustGeneration = false
        )
        val transaction = UnshieldedTransaction(
            id = 100,
            hash = "0xtx",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            block = UnshieldedTransaction.BlockInfo(0),
            transactionResult = UnshieldedTransaction.TransactionResult(
                status = TransactionStatus.FAILURE,
                segments = null
            )
        )
        val update = UnshieldedTransactionUpdate.Transaction(
            transaction = transaction,
            createdUtxos = emptyList(),
            spentUtxos = listOf(spentUtxo1, spentUtxo2)
        )

        // Mock: getUtxoByIntentHash returns existing entities for spent UTXO lookup
        val existingEntity1 = createUtxoEntity(
            id = "tx_0x123:0",
            intentHash = "0x123",
            outputIndex = 0,
            owner = "addr",
            tokenType = "DUST",
            value = "1000",
            transactionHash = "tx_0x123"
        )
        val existingEntity2 = createUtxoEntity(
            id = "tx_0x456:0",
            intentHash = "0x456",
            outputIndex = 0,
            owner = "addr",
            tokenType = "DUST",
            value = "2000",
            transactionHash = "tx_0x456"
        )
        whenever(mockDao.getUtxoByIntentHash("0x123", 0)).thenReturn(existingEntity1)
        whenever(mockDao.getUtxoByIntentHash("0x456", 0)).thenReturn(existingEntity2)

        // When
        val result = utxoManager.processUpdate(update)

        // Then
        // Should mark as AVAILABLE (unlock) instead of marking as SPENT
        verify(mockDao).markAsAvailable(listOf("tx_0x123:0", "tx_0x456:0"))  // Uses database IDs
        verify(mockDao, never()).markAsSpent(any<List<String>>())
        verify(mockDao, never()).insertUtxos(any())

        assertTrue(result is UtxoManager.ProcessingResult.TransactionProcessed)
        val txResult = result as UtxoManager.ProcessingResult.TransactionProcessed
        assertEquals(TransactionStatus.FAILURE, txResult.status)
        assertEquals(0, txResult.createdCount)
        assertEquals(2, txResult.spentCount)
    }

    @Test
    fun `given failed transaction with created utxos when processUpdate then does not insert`() = runBlocking {
        // Given
        val createdUtxo = Utxo(
            value = "1000",
            owner = "addr",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 1,
            registeredForDustGeneration = false
        )
        val transaction = UnshieldedTransaction(
            id = 100,
            hash = "0xtx",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            block = UnshieldedTransaction.BlockInfo(0),
            transactionResult = UnshieldedTransaction.TransactionResult(
                status = TransactionStatus.FAILURE,
                segments = null
            )
        )
        val update = UnshieldedTransactionUpdate.Transaction(
            transaction = transaction,
            createdUtxos = listOf(createdUtxo),
            spentUtxos = emptyList()
        )

        // When
        val result = utxoManager.processUpdate(update)

        // Then
        // Should NOT insert UTXOs from failed transactions
        verify(mockDao, never()).insertUtxos(any())
        verify(mockDao, never()).markAsSpent(any<List<String>>())

        assertTrue(result is UtxoManager.ProcessingResult.TransactionProcessed)
        val txResult = result as UtxoManager.ProcessingResult.TransactionProcessed
        assertEquals(TransactionStatus.FAILURE, txResult.status)
        assertEquals(1, txResult.createdCount) // Count is reported but not inserted
    }

    @Test
    fun `given partial success transaction when processUpdate then marks as spent`() = runBlocking {
        // Given
        val createdUtxo = Utxo(
            value = "1000",
            owner = "addr",
            tokenType = "DUST",
            intentHash = "0x123",
            outputIndex = 0,
            ctime = 1,
            registeredForDustGeneration = false
        )
        val spentUtxo = Utxo(
            value = "500",
            owner = "addr",
            tokenType = "DUST",
            intentHash = "0x456",
            outputIndex = 0,
            ctime = 2,
            registeredForDustGeneration = false
        )
        val transaction = UnshieldedTransaction(
            id = 100,
            hash = "0xtx",
            type = TransactionType.RegularTransaction,
            protocolVersion = 1,
            block = UnshieldedTransaction.BlockInfo(0),
            transactionResult = UnshieldedTransaction.TransactionResult(
                status = TransactionStatus.PARTIAL_SUCCESS,
                segments = listOf(
                    UnshieldedTransaction.TransactionResult.SegmentResult(0, true),
                    UnshieldedTransaction.TransactionResult.SegmentResult(1, false)
                )
            )
        )
        val update = UnshieldedTransactionUpdate.Transaction(
            transaction = transaction,
            createdUtxos = listOf(createdUtxo),
            spentUtxos = listOf(spentUtxo)
        )

        // Mock: getUtxoByIntentHash returns existing entity for spent UTXO lookup
        val existingSpentEntity = createUtxoEntity(
            id = "tx_0x456:0",
            intentHash = "0x456",
            outputIndex = 0,
            owner = "addr",
            tokenType = "DUST",
            value = "500",
            transactionHash = "tx_0x456"
        )
        whenever(mockDao.getUtxoByIntentHash("0x456", 0)).thenReturn(existingSpentEntity)

        // When
        val result = utxoManager.processUpdate(update)

        // Then
        // PARTIAL_SUCCESS should behave like SUCCESS: insert created, mark spent
        verify(mockDao).insertUtxos(argThat { list ->
            list.size == 1 &&
            list[0].intentHash == "0x123" &&  // Check intentHash, not id
            list[0].state == UtxoState.AVAILABLE
        })
        verify(mockDao).markAsSpent(listOf("tx_0x456:0"))  // Uses database ID

        assertTrue(result is UtxoManager.ProcessingResult.TransactionProcessed)
        val txResult = result as UtxoManager.ProcessingResult.TransactionProcessed
        assertEquals(TransactionStatus.PARTIAL_SUCCESS, txResult.status)
    }
}
