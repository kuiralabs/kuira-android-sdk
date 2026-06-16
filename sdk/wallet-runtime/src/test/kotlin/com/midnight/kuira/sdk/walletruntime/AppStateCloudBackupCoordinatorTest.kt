package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.core.identity.backup.BackupStorage
import com.midnight.kuira.core.identity.backup.SeedDerivedKeyDeriver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for the app-state cloud backup coordinator: the silent,
 * seed-keyed, idempotent sibling of the dust coordinator that makes automatic
 * app-state backup possible (#244).
 */
class AppStateCloudBackupCoordinatorTest {

    /** In-memory [BackupStorage] standing in for Block Store / Drive. */
    private class FakeStorage : BackupStorage {
        var blob: ByteArray? = null
        var storeCount = 0
        override suspend fun store(encryptedBlob: ByteArray) { blob = encryptedBlob; storeCount++ }
        override suspend fun retrieve(): ByteArray? = blob
        override suspend fun delete() { blob = null }
        override suspend fun isAvailable(): Boolean = true
    }

    private class FakeDigestStore : AppStateBackupDigestStore {
        private var value: String? = null
        override fun get(): String? = value
        override fun put(digest: String) { value = digest }
        override fun clear() { value = null }
    }

    private val key = SeedDerivedKeyDeriver.deriveAppStateBackupKey(ByteArray(32) { 9 })

    private fun coordinator(storage: BackupStorage, digests: AppStateBackupDigestStore = FakeDigestStore()) =
        AppStateCloudBackupCoordinator(storage, key, digests)

    @Test
    fun `upload then fetch round-trips the app metadata`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        // App state is small by design — AppStateBackupEncryptor caps at 2 KB to
        // fit Block Store's 4 KB entry limit. Use a near-cap payload.
        val metadata = ByteArray(2000) { (it * 17).toByte() }

        c.uploadAppState(metadata)
        val restored = c.fetchAppState()

        assertArrayEquals(metadata, restored)
    }

    @Test
    fun `fetch returns null when nothing stored`() = runTest {
        assertNull(coordinator(FakeStorage()).fetchAppState())
    }

    @Test
    fun `fetch returns null for a blob from a different key`() = runTest {
        val storage = FakeStorage()
        coordinator(storage).uploadAppState(byteArrayOf(1, 2, 3))

        // A coordinator with a DIFFERENT seed-derived key can't decrypt it.
        val otherKey = SeedDerivedKeyDeriver.deriveAppStateBackupKey(ByteArray(32) { 8 })
        val other = AppStateCloudBackupCoordinator(storage, otherKey, FakeDigestStore())
        assertNull(other.fetchAppState())
    }

    @Test
    fun `upload is idempotent — unchanged metadata skips the store`() = runTest {
        val storage = FakeStorage()
        val digests = FakeDigestStore()
        val c = coordinator(storage, digests)
        val metadata = byteArrayOf(7, 7, 7, 7)

        c.uploadAppState(metadata)
        c.uploadAppState(metadata) // same content → guarded
        assertEquals("unchanged metadata must not re-store", 1, storage.storeCount)

        c.uploadAppState(byteArrayOf(7, 7, 7, 8)) // changed → stores
        assertEquals(2, storage.storeCount)
    }

    @Test
    fun `clear deletes the blob and resets the digest guard so the same app state re-uploads`() = runTest {
        val storage = FakeStorage()
        val c = coordinator(storage)
        val metadata = byteArrayOf(3, 3, 3, 3)

        c.uploadAppState(metadata)
        c.uploadAppState(metadata) // unchanged → guarded
        assertEquals(1, storage.storeCount)

        c.clear()
        assertNull("blob deleted on clear", storage.blob)

        c.uploadAppState(metadata) // guard reset → re-uploads
        assertEquals("re-uploads after clear", 2, storage.storeCount)
    }

    @Test
    fun `a key change forces re-upload despite unchanged metadata`() = runTest {
        val storage = FakeStorage()
        val digests = FakeDigestStore() // persists across the seed change
        val metadata = byteArrayOf(5, 5, 5)

        coordinator(storage, digests).uploadAppState(metadata)
        assertEquals(1, storage.storeCount)

        // Same metadata + same persisted digest store, but a NEW seed key (re-forge)
        // → must re-upload, else the blob stays encrypted under the old key and is
        // undecryptable under the new one.
        val newKey = SeedDerivedKeyDeriver.deriveAppStateBackupKey(ByteArray(32) { 2 })
        AppStateCloudBackupCoordinator(storage, newKey, digests).uploadAppState(metadata)
        assertEquals("a key change must re-upload", 2, storage.storeCount)
    }
}
