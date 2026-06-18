package com.midnight.kuira.sdk.walletseed

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.identity.backup.SeedDeriver
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #252: proves the reveal ↔ restore loop at the manager level, headless. A fake [RecoverySeedStore]
 * stands in for the real vault, so the test exercises the manager's orchestration (codec + the
 * saved-flag + the guards) without biometrics or a device. The headline test — reveal a phrase,
 * then restore from those exact words and confirm the SAME entropy is fed back — is what guarantees
 * "the phrase recovers the wallet."
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryPhraseManagerTest {

    /** The vault's 32-byte entropy under test. */
    private val entropy = ByteArray(32) { it.toByte() }
    private val activity = mockk<FragmentActivity>(relaxed = true) // ignored by the fake store

    private lateinit var context: Context

    /** In-memory [RecoverySeedStore]: serves [entropy] on reveal, records what restore feeds back. */
    private class FakeSeedStore(
        private val stored: ByteArray?,
        private var existing: Boolean,
    ) : RecoverySeedStore {
        var acceptedEntropy: ByteArray? = null
            private set

        override suspend fun hasSeed(): Boolean = existing

        override suspend fun loadEntropy(activity: FragmentActivity): ByteArray =
            requireNotNull(stored) { "no seed" }.copyOf()

        override suspend fun acceptPreDerivedSeed(activity: FragmentActivity, prfEntropy: ByteArray): ByteArray {
            acceptedEntropy = prfEntropy.copyOf()
            existing = true
            return ByteArray(64) // a dummy 64-byte BIP-39 seed
        }
    }

    private fun manager(store: RecoverySeedStore) = RecoveryPhraseManager(context, store)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric reuses SharedPreferences across tests in a class — start each test clean.
        context.getSharedPreferences("kuira_recovery_phrase", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `reveal returns the phrase for the vault entropy`() = runTest {
        val words = manager(FakeSeedStore(entropy, existing = true)).revealPhrase(activity)
        assertEquals(SeedDeriver.entropyToRecoveryWords(entropy), words)
    }

    @Test
    fun `reveal then restore feeds back the SAME entropy`() = runTest {
        // Reveal on the existing wallet…
        val words = manager(FakeSeedStore(entropy, existing = true)).revealPhrase(activity)
        // …then restore on a fresh device (no wallet yet).
        val freshStore = FakeSeedStore(stored = null, existing = false)
        manager(freshStore).restoreFromPhrase(activity, words)
        assertArrayEquals(entropy, freshStore.acceptedEntropy)
    }

    @Test
    fun `restore rejects an invalid phrase`() = runTest {
        var threw = false
        try {
            manager(FakeSeedStore(null, existing = false))
                .restoreFromPhrase(activity, listOf("not", "a", "valid", "bip39", "phrase"))
        } catch (e: InvalidRecoveryPhraseException) {
            threw = true
        }
        assertTrue("expected InvalidRecoveryPhraseException", threw)
    }

    @Test
    fun `restore over an existing wallet is not allowed`() = runTest {
        val words = SeedDeriver.entropyToRecoveryWords(entropy)
        var threw = false
        try {
            manager(FakeSeedStore(entropy, existing = true)).restoreFromPhrase(activity, words)
        } catch (e: RecoveryNotAllowedException) {
            threw = true
        }
        assertTrue("expected RecoveryNotAllowedException", threw)
    }

    @Test
    fun `markPhraseSaved flips the status flag`() = runTest {
        val mgr = manager(FakeSeedStore(entropy, existing = true))
        assertFalse(mgr.isPhraseSaved.value)
        mgr.markPhraseSaved()
        assertTrue(mgr.isPhraseSaved.value)
    }

    @Test
    fun `a successful restore marks the phrase saved`() = runTest {
        val words = SeedDeriver.entropyToRecoveryWords(entropy)
        val mgr = manager(FakeSeedStore(null, existing = false))
        assertFalse(mgr.isPhraseSaved.value)
        mgr.restoreFromPhrase(activity, words)
        assertTrue(mgr.isPhraseSaved.value)
    }
}
