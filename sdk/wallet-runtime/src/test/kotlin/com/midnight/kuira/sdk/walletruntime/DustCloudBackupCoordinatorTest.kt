package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.core.identity.backup.BackupStorage
import com.midnight.kuira.core.identity.backup.SeedDerivedKeyDeriver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DustCloudBackupCoordinatorTest {

    /** In-memory [BackupStorage] standing in for Drive. */
    private class FakeStorage : BackupStorage {
        var blob: ByteArray? = null
        var storeCount = 0
        override suspend fun store(encryptedBlob: ByteArray) { blob = encryptedBlob; storeCount++ }
        override suspend fun retrieve(): ByteArray? = blob
        override suspend fun delete() { blob = null }
        override suspend fun isAvailable(): Boolean = true
    }

    private class FakeDigestStore : DustBackupDigestStore {
        private val map = mutableMapOf<String, String>()
        override fun get(address: String): String? = map[address]
        override fun put(address: String, digest: String) { map[address] = digest }
    }

    private val key = SeedDerivedKeyDeriver.deriveDustBackupKey(ByteArray(32) { 9 })

    private fun coordinator(storage: BackupStorage, digests: DustBackupDigestStore = FakeDigestStore()) =
        DustCloudBackupCoordinator(storage, key, digests)

    @Test
    fun `upload then fetch round-trips the checkpoint`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        val state = ByteArray(487_000) { (it * 13).toByte() }

        c.upload("mn_addr_preprod1abc", state, 921_634L)
        val restored = c.fetch("mn_addr_preprod1abc")!!

        assertEquals(921_634L, restored.lastEventId)
        assertArrayEquals(state, restored.stateBytes)
    }

    @Test
    fun `fetch returns null when nothing stored`() = runTest {
        assertNull(coordinator(FakeStorage()).fetch("mn_addr_preprod1abc"))
    }

    @Test
    fun `fetch returns null for an address absent from the bundle`() = runTest {
        val c = coordinator(FakeStorage())
        c.upload("mn_addr_preprod1abc", byteArrayOf(1, 2, 3), 10L)
        assertNull(c.fetch("mn_addr_undeployed1xyz"))
    }

    @Test
    fun `upload preserves other networks' entries`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        c.upload("mn_addr_preprod1abc", byteArrayOf(1, 1, 1), 100L)
        c.upload("mn_addr_undeployed1xyz", byteArrayOf(2, 2, 2), 200L)

        // Both survive in the single merged bundle.
        assertArrayEquals(byteArrayOf(1, 1, 1), c.fetch("mn_addr_preprod1abc")!!.stateBytes)
        assertArrayEquals(byteArrayOf(2, 2, 2), c.fetch("mn_addr_undeployed1xyz")!!.stateBytes)
    }

    @Test
    fun `re-uploading an unchanged checkpoint is skipped by the hash guard`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        c.upload("mn_addr_preprod1abc", byteArrayOf(1, 2, 3), 100L)
        assertEquals(1, storage.storeCount)

        c.upload("mn_addr_preprod1abc", byteArrayOf(1, 2, 3), 100L) // identical
        assertEquals("unchanged checkpoint must not re-upload", 1, storage.storeCount)
    }

    @Test
    fun `a changed checkpoint re-uploads`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        c.upload("mn_addr_preprod1abc", byteArrayOf(1, 2, 3), 100L)
        c.upload("mn_addr_preprod1abc", byteArrayOf(1, 2, 3), 105L) // newer event id
        assertEquals(2, storage.storeCount)
        assertEquals(105L, c.fetch("mn_addr_preprod1abc")!!.lastEventId)
    }
}
