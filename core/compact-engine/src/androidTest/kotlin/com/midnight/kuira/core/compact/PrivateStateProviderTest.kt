package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.state.KeyStorePrivateStateProvider
import com.midnight.kuira.core.crypto.state.PrivateStateProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 6I: Tests for PrivateStateProvider — encrypted dApp secret storage.
 */
@RunWith(AndroidJUnit4::class)
class PrivateStateProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: PrivateStateProvider

    @Before
    fun setUp() {
        provider = KeyStorePrivateStateProvider(context)
        provider.clearContract(TEST_CONTRACT)
        provider.clearContract(OTHER_CONTRACT)
    }

    @Test
    fun storeAndRetrieve_roundTripsCorrectly() {
        val secretKey = ByteArray(32) { (it + 1).toByte() }

        provider.set(TEST_CONTRACT, "secretKey", secretKey)
        val retrieved = provider.get(TEST_CONTRACT, "secretKey")

        assertNotNull(retrieved)
        assertArrayEquals("Should round-trip correctly", secretKey, retrieved)
    }

    @Test
    fun get_returnsNull_whenNotStored() {
        assertNull(provider.get(TEST_CONTRACT, "nonexistent"))
    }

    @Test
    fun has_returnsFalse_whenNotStored() {
        assertFalse(provider.has(TEST_CONTRACT, "nonexistent"))
    }

    @Test
    fun has_returnsTrue_afterStore() {
        provider.set(TEST_CONTRACT, "secretKey", ByteArray(32))
        assertTrue(provider.has(TEST_CONTRACT, "secretKey"))
    }

    @Test
    fun remove_deletesState() {
        provider.set(TEST_CONTRACT, "secretKey", ByteArray(32))
        assertTrue(provider.has(TEST_CONTRACT, "secretKey"))

        provider.remove(TEST_CONTRACT, "secretKey")
        assertFalse(provider.has(TEST_CONTRACT, "secretKey"))
        assertNull(provider.get(TEST_CONTRACT, "secretKey"))
    }

    @Test
    fun differentContracts_haveIsolatedState() {
        val key1 = ByteArray(32) { 0xAA.toByte() }
        val key2 = ByteArray(32) { 0xBB.toByte() }

        provider.set(TEST_CONTRACT, "secretKey", key1)
        provider.set(OTHER_CONTRACT, "secretKey", key2)

        assertArrayEquals(key1, provider.get(TEST_CONTRACT, "secretKey"))
        assertArrayEquals(key2, provider.get(OTHER_CONTRACT, "secretKey"))
    }

    @Test
    fun differentStateIds_haveIsolatedState() {
        val data1 = byteArrayOf(1, 2, 3)
        val data2 = byteArrayOf(4, 5, 6)

        provider.set(TEST_CONTRACT, "secretKey", data1)
        provider.set(TEST_CONTRACT, "otherState", data2)

        assertArrayEquals(data1, provider.get(TEST_CONTRACT, "secretKey"))
        assertArrayEquals(data2, provider.get(TEST_CONTRACT, "otherState"))
    }

    @Test
    fun overwrite_replacesExistingState() {
        val original = ByteArray(32) { 0x01 }
        val updated = ByteArray(32) { 0x02 }

        provider.set(TEST_CONTRACT, "secretKey", original)
        provider.set(TEST_CONTRACT, "secretKey", updated)

        assertArrayEquals(updated, provider.get(TEST_CONTRACT, "secretKey"))
    }

    @Test
    fun get_returnsNull_whenDataCorrupted() {
        provider.set(TEST_CONTRACT, "secretKey", ByteArray(32) { 0x42 })
        assertTrue(provider.has(TEST_CONTRACT, "secretKey"))

        // Corrupt the stored ciphertext directly
        val prefs = context.getSharedPreferences("kuira_private_state", Context.MODE_PRIVATE)
        val key = "${TEST_CONTRACT.length}:$TEST_CONTRACT:secretKey"
        prefs.edit().putString(key, "corrupted_not_base64!!!").commit()

        assertNull("Corrupted data should return null", provider.get(TEST_CONTRACT, "secretKey"))
    }

    @Test
    fun storeAndRetrieve_emptyByteArray() {
        provider.set(TEST_CONTRACT, "empty", ByteArray(0))
        val retrieved = provider.get(TEST_CONTRACT, "empty")
        assertNotNull(retrieved)
        assertEquals(0, retrieved!!.size)
    }

    @Test
    fun clearContract_removesAllStateForContract() {
        provider.set(TEST_CONTRACT, "key1", byteArrayOf(1))
        provider.set(TEST_CONTRACT, "key2", byteArrayOf(2))
        provider.set(OTHER_CONTRACT, "key1", byteArrayOf(3))

        provider.clearContract(TEST_CONTRACT)

        assertFalse(provider.has(TEST_CONTRACT, "key1"))
        assertFalse(provider.has(TEST_CONTRACT, "key2"))
        assertTrue("Other contract should be unaffected", provider.has(OTHER_CONTRACT, "key1"))
    }

    @Test
    fun isKeyStoreHealthy_returnsTrue() {
        assertTrue("KeyStore should be healthy on test device", provider.isKeyStoreHealthy())
    }

    @Test(expected = IllegalArgumentException::class)
    fun set_rejectsTooLargeData() {
        val tooLarge = ByteArray(PrivateStateProvider.MAX_STATE_SIZE_BYTES + 1)
        provider.set(TEST_CONTRACT, "big", tooLarge)
    }

    companion object {
        private val TEST_CONTRACT = "a".repeat(64)
        private val OTHER_CONTRACT = "b".repeat(64)
    }
}
