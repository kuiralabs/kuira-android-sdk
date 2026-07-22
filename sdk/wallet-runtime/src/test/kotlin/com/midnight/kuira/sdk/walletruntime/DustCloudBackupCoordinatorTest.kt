package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.core.identity.backup.BackupStorage
import com.midnight.kuira.core.identity.backup.SeedDerivedKeyDeriver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric only for `android.util.Log` shadowing (the coordinator logs its
// fetch decisions); the logic under test is pure JVM.
@RunWith(RobolectricTestRunner::class)
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
        override fun clear() { map.clear() }
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

    // ── Network pinning ──
    //
    // The blob is ONE bundle for the whole wallet; the address embeds the network
    // (`mn_addr_preprod…` vs `mn_addr_undeployed…`), so per-address entry selection IS the
    // network selection. Pin it: a wallet on PreProd must restore the PreProd checkpoint —
    // seeding another network's dust state would corrupt sync — and uploading one network's
    // checkpoint must never clobber the other's.

    @Test
    fun `fetch selects the requested network's entry from a multi-network bundle`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        val preprodState = ByteArray(1024) { 1 }
        val undeployedState = ByteArray(1024) { 2 }
        c.upload("mn_addr_preprod1abc", preprodState, 111L)
        c.upload("mn_addr_undeployed1xyz", undeployedState, 222L)

        val preprod = c.fetch("mn_addr_preprod1abc")!!
        val undeployed = c.fetch("mn_addr_undeployed1xyz")!!

        assertEquals(111L, preprod.lastEventId)
        assertArrayEquals(preprodState, preprod.stateBytes)
        assertEquals(222L, undeployed.lastEventId)
        assertArrayEquals(undeployedState, undeployed.stateBytes)
    }

    @Test
    fun `uploading one network's checkpoint preserves the other's entry`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        c.upload("mn_addr_preprod1abc", ByteArray(64) { 1 }, 10L)
        c.upload("mn_addr_undeployed1xyz", ByteArray(64) { 2 }, 20L)

        // Re-upload preprod with a newer checkpoint — the merge must not drop undeployed.
        c.upload("mn_addr_preprod1abc", ByteArray(64) { 3 }, 30L)

        assertEquals(30L, c.fetch("mn_addr_preprod1abc")!!.lastEventId)
        assertEquals("the merge dropped the sibling network's checkpoint",
            20L, c.fetch("mn_addr_undeployed1xyz")!!.lastEventId)
    }

    @Test
    fun `fetch for an address with no entry returns null, never another network's state`() = runTest {
        val c = coordinator(FakeStorage())
        c.upload("mn_addr_undeployed1xyz", ByteArray(64) { 2 }, 20L)

        assertNull("a PreProd wallet must NEVER be seeded with another network's checkpoint",
            c.fetch("mn_addr_preprod1abc"))
    }

    @Test
    fun `clear deletes the blob and resets the digest guard so the same checkpoint re-uploads`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        val state = ByteArray(1_000) { 7 }

        c.upload("mn_addr1", state, 5L)
        assertEquals(1, storage.storeCount)
        c.upload("mn_addr1", state, 5L) // unchanged → digest guard skips the network round-trip
        assertEquals("digest guard skips an unchanged checkpoint", 1, storage.storeCount)

        c.clear()
        assertNull("blob deleted on clear", storage.blob)

        // After clear, the guard is reset → the SAME checkpoint uploads again (no stale skip).
        c.upload("mn_addr1", state, 5L)
        assertEquals("re-uploads after clear", 2, storage.storeCount)
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
