package com.midnight.kuira.core.indexer.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnshieldedUtxoDaoTest {

    private lateinit var database: UtxoDatabase
    private lateinit var dao: UnshieldedUtxoDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = UtxoDatabase.createInMemoryDatabase(context)
        dao = database.unshieldedUtxoDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertUtxos_thenQueryById_returnsUtxo() = runBlocking {
        // Given
        val utxo = UnshieldedUtxoEntity(
            id = "0x123:0",
            transactionHash = "0x123",
            intentHash = "0x123",
            outputIndex = 0,
            owner = "mn_addr_testnet1abc",
            ownerPublicKey = null,
            tokenType = "DUST",
            value = "1000",
            ctime = 1704067200,
            registeredForDustGeneration = false,
            state = UtxoState.AVAILABLE
        )

        // When
        dao.insertUtxos(listOf(utxo))
        val result = dao.getUtxoById("0x123:0")

        // Then
        assertNotNull(result)
        assertEquals("0x123:0", result?.id)
        assertEquals("mn_addr_testnet1abc", result?.owner)
        assertEquals("1000", result?.value)
        assertEquals(UtxoState.AVAILABLE, result?.state)
    }

    @Test
    fun insertMultipleUtxos_thenGetUnspent_returnsAll() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val utxos = listOf(
            UnshieldedUtxoEntity(
                id = "0x123:0",
                transactionHash = "0x123",
                intentHash = "0x123",
                outputIndex = 0,
                owner = address,
                ownerPublicKey = null,
                tokenType = "DUST",
                value = "1000",
                ctime = 1,
                registeredForDustGeneration = false,
                state = UtxoState.AVAILABLE
            ),
            UnshieldedUtxoEntity(
                id = "0x123:1",
                transactionHash = "0x123",
                intentHash = "0x123",
                outputIndex = 1,
                owner = address,
                ownerPublicKey = null,
                tokenType = "DUST",
                value = "2000",
                ctime = 2,
                registeredForDustGeneration = false,
                state = UtxoState.AVAILABLE
            ),
            UnshieldedUtxoEntity(
                id = "0x456:0",
                transactionHash = "0x456",
                intentHash = "0x456",
                outputIndex = 0,
                owner = address,
                ownerPublicKey = null,
                tokenType = "OTHER",
                value = "5000",
                ctime = 3,
                registeredForDustGeneration = false,
                state = UtxoState.AVAILABLE
            )
        )

        // When
        dao.insertUtxos(utxos)
        val result = dao.getUnspentUtxos(address)

        // Then
        assertEquals(3, result.size)
    }

    @Test
    fun insertUtxos_thenMarkAsSpent_setsSpentFlag() = runBlocking {
        // Given
        val utxo = UnshieldedUtxoEntity(
            id = "0x123:0",
            transactionHash = "0x123",
            intentHash = "0x123",
            outputIndex = 0,
            owner = "mn_addr_testnet1abc",
            ownerPublicKey = null,
            tokenType = "DUST",
            value = "1000",
            ctime = 1,
            registeredForDustGeneration = false,
            state = UtxoState.AVAILABLE
        )
        dao.insertUtxos(listOf(utxo))

        // When
        dao.markAsSpent("0x123:0")
        val result = dao.getUtxoById("0x123:0")

        // Then
        assertNotNull(result)
        assertEquals(UtxoState.SPENT, result?.state)
    }

    @Test
    fun insertUtxos_withSomeSpent_thenGetUnspent_excludesSpent() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val utxos = listOf(
            UnshieldedUtxoEntity(
                id = "0x123:0",
                transactionHash = "0x123",
                intentHash = "0x123",
                outputIndex = 0,
                owner = address,
                ownerPublicKey = null,
                tokenType = "DUST",
                value = "1000",
                ctime = 1,
                registeredForDustGeneration = false,
                state = UtxoState.AVAILABLE
            ),
            UnshieldedUtxoEntity(
                id = "0x123:1",
                transactionHash = "0x123",
                intentHash = "0x123",
                outputIndex = 1,
                owner = address,
                ownerPublicKey = null,
                tokenType = "DUST",
                value = "2000",
                ctime = 2,
                registeredForDustGeneration = false,
                state = UtxoState.AVAILABLE
            )
        )
        dao.insertUtxos(utxos)

        // When
        dao.markAsSpent("0x123:0")
        val unspent = dao.getUnspentUtxos(address)

        // Then
        assertEquals(1, unspent.size)
        assertEquals("0x123:1", unspent[0].id)
    }

    @Test
    fun markMultipleAsSpent_thenGetUnspent_excludesAll() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val utxos = listOf(
            UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x2:0", "0x2", "0x2", 0, address, null, "DUST", "2000", 2, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x3:0", "0x3", "0x3", 0, address, null, "DUST", "3000", 3, false, UtxoState.AVAILABLE)
        )
        dao.insertUtxos(utxos)

        // When
        dao.markAsSpent(listOf("0x1:0", "0x2:0"))
        val unspent = dao.getUnspentUtxos(address)

        // Then
        assertEquals(1, unspent.size)
        assertEquals("0x3:0", unspent[0].id)
    }

    @Test
    fun getUnspentUtxosForToken_filtersCorrectly() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val utxos = listOf(
            UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x2:0", "0x2", "0x2", 0, address, null, "OTHER", "2000", 2, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x3:0", "0x3", "0x3", 0, address, null, "DUST", "3000", 3, false, UtxoState.AVAILABLE)
        )
        dao.insertUtxos(utxos)

        // When
        val dustUtxos = dao.getUnspentUtxosForToken(address, "DUST")

        // Then
        assertEquals(2, dustUtxos.size)
        assertTrue(dustUtxos.all { it.tokenType == "DUST" })
    }

    @Test
    fun observeUnspentUtxos_emitsUpdates() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val utxo = UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE)

        // When
        dao.insertUtxos(listOf(utxo))
        val result = dao.observeUnspentUtxos(address).first()

        // Then
        assertEquals(1, result.size)
        assertEquals("0x1:0", result[0].id)
    }

    @Test
    fun deleteUtxosForAddress_removesCorrectUtxos() = runBlocking {
        // Given
        val address1 = "mn_addr1"
        val address2 = "mn_addr2"
        val utxos = listOf(
            UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address1, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x2:0", "0x2", "0x2", 0, address2, null, "DUST", "2000", 2, false, UtxoState.AVAILABLE)
        )
        dao.insertUtxos(utxos)

        // When
        dao.deleteUtxosForAddress(address1)

        // Then
        val address1Utxos = dao.getUnspentUtxos(address1)
        val address2Utxos = dao.getUnspentUtxos(address2)
        assertEquals(0, address1Utxos.size)
        assertEquals(1, address2Utxos.size)
    }

    @Test
    fun deleteAll_removesAllUtxos() = runBlocking {
        // Given
        val utxos = listOf(
            UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, "addr1", null, "DUST", "1000", 1, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x2:0", "0x2", "0x2", 0, "addr2", null, "DUST", "2000", 2, false, UtxoState.AVAILABLE)
        )
        dao.insertUtxos(utxos)

        // When
        dao.deleteAll()

        // Then
        val count = dao.count()
        assertEquals(0, count)
    }

    @Test
    fun countUnspent_returnsCorrectCount() = runBlocking {
        // Given
        val address = "mn_addr_testnet1abc"
        val utxos = listOf(
            UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x2:0", "0x2", "0x2", 0, address, null, "DUST", "2000", 2, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x3:0", "0x3", "0x3", 0, address, null, "DUST", "3000", 3, false, UtxoState.AVAILABLE)
        )
        dao.insertUtxos(utxos)
        dao.markAsSpent("0x1:0")

        // When
        val count = dao.countUnspent(address)

        // Then
        assertEquals(2, count)
    }

    @Test
    fun insertDuplicateUtxo_replacesExisting() = runBlocking {
        // Given
        val utxo1 = UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, "addr", null, "DUST", "1000", 1, false, UtxoState.AVAILABLE)
        val utxo2 = UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, "addr", null, "DUST", "2000", 2, false, UtxoState.AVAILABLE)

        // When
        dao.insertUtxos(listOf(utxo1))
        dao.insertUtxos(listOf(utxo2))
        val result = dao.getUtxoById("0x1:0")

        // Then
        assertNotNull(result)
        assertEquals("2000", result?.value) // Updated value
        assertEquals(2L, result?.ctime) // Updated ctime
    }

    @Test
    fun markAsPending_changesStateFromAvailableToPending() = runBlocking {
        // Given
        val utxo = UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, "addr", null, "DUST", "1000", 1, false, UtxoState.AVAILABLE)
        dao.insertUtxos(listOf(utxo))

        // When
        dao.markAsPending("0x1:0")
        val result = dao.getUtxoById("0x1:0")

        // Then
        assertNotNull(result)
        assertEquals(UtxoState.PENDING, result?.state)
    }

    @Test
    fun markAsPending_excludesFromUnspentQuery() = runBlocking {
        // Given
        val address = "addr"
        val utxos = listOf(
            UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x2:0", "0x2", "0x2", 0, address, null, "DUST", "2000", 2, false, UtxoState.AVAILABLE)
        )
        dao.insertUtxos(utxos)

        // When
        dao.markAsPending("0x1:0")
        val unspent = dao.getUnspentUtxos(address)

        // Then
        assertEquals(1, unspent.size)
        assertEquals("0x2:0", unspent[0].id)
        assertEquals(UtxoState.AVAILABLE, unspent[0].state)
    }

    @Test
    fun markAsAvailable_changesStateFromPendingToAvailable() = runBlocking {
        // Given
        val utxo = UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, "addr", null, "DUST", "1000", 1, false, UtxoState.PENDING)
        dao.insertUtxos(listOf(utxo))

        // When
        dao.markAsAvailable("0x1:0")
        val result = dao.getUtxoById("0x1:0")

        // Then
        assertNotNull(result)
        assertEquals(UtxoState.AVAILABLE, result?.state)
    }

    @Test
    fun markAsAvailable_includesInUnspentQuery() = runBlocking {
        // Given
        val address = "addr"
        val utxo = UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.PENDING)
        dao.insertUtxos(listOf(utxo))

        // Before: PENDING utxos excluded
        val beforeUnspent = dao.getUnspentUtxos(address)
        assertEquals(0, beforeUnspent.size)

        // When
        dao.markAsAvailable("0x1:0")

        // After: now included
        val afterUnspent = dao.getUnspentUtxos(address)
        assertEquals(1, afterUnspent.size)
        assertEquals("0x1:0", afterUnspent[0].id)
    }

    @Test
    fun markMultipleAsPending_updatesAllStates() = runBlocking {
        // Given
        val address = "addr"
        val utxos = listOf(
            UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x2:0", "0x2", "0x2", 0, address, null, "DUST", "2000", 2, false, UtxoState.AVAILABLE),
            UnshieldedUtxoEntity("0x3:0", "0x3", "0x3", 0, address, null, "DUST", "3000", 3, false, UtxoState.AVAILABLE)
        )
        dao.insertUtxos(utxos)

        // When
        dao.markAsPending(listOf("0x1:0", "0x2:0"))

        // Then
        val unspent = dao.getUnspentUtxos(address)
        assertEquals(1, unspent.size)
        assertEquals("0x3:0", unspent[0].id)
    }

    @Test
    fun markMultipleAsAvailable_updatesAllStates() = runBlocking {
        // Given
        val address = "addr"
        val utxos = listOf(
            UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.PENDING),
            UnshieldedUtxoEntity("0x2:0", "0x2", "0x2", 0, address, null, "DUST", "2000", 2, false, UtxoState.PENDING)
        )
        dao.insertUtxos(utxos)

        // When
        dao.markAsAvailable(listOf("0x1:0", "0x2:0"))

        // Then
        val unspent = dao.getUnspentUtxos(address)
        assertEquals(2, unspent.size)
    }

    @Test
    fun stateTransitions_fullCycle() = runBlocking {
        // Given
        val address = "addr"
        val utxo = UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE)
        dao.insertUtxos(listOf(utxo))

        // AVAILABLE -> PENDING
        dao.markAsPending("0x1:0")
        assertEquals(UtxoState.PENDING, dao.getUtxoById("0x1:0")?.state)
        assertEquals(0, dao.getUnspentUtxos(address).size)

        // PENDING -> SPENT (success)
        dao.markAsSpent("0x1:0")
        assertEquals(UtxoState.SPENT, dao.getUtxoById("0x1:0")?.state)
        assertEquals(0, dao.getUnspentUtxos(address).size)
    }

    @Test
    fun stateTransitions_failureUnlocks() = runBlocking {
        // Given
        val address = "addr"
        val utxo = UnshieldedUtxoEntity("0x1:0", "0x1", "0x1", 0, address, null, "DUST", "1000", 1, false, UtxoState.AVAILABLE)
        dao.insertUtxos(listOf(utxo))

        // AVAILABLE -> PENDING
        dao.markAsPending("0x1:0")
        assertEquals(UtxoState.PENDING, dao.getUtxoById("0x1:0")?.state)

        // PENDING -> AVAILABLE (failure)
        dao.markAsAvailable("0x1:0")
        assertEquals(UtxoState.AVAILABLE, dao.getUtxoById("0x1:0")?.state)
        assertEquals(1, dao.getUnspentUtxos(address).size)
    }
}
