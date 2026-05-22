package com.midnight.kuira.sdk.walletseed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [SigilStateStore] — the single source of truth for the
 * sigil_identity SharedPreferences schema.
 *
 * **Note on test placement:** `SigilStateStore` lives in
 * `core:identity` but its test lives here in `sdk:wallet-seed`.
 * `core:identity` is intentionally lean (no Robolectric on its test
 * classpath); the Android-resource scaffolding needed to exercise
 * SharedPreferences runs only in modules that opt in to Robolectric.
 * `sdk:wallet-seed` is the closest consumer of `SigilStateStore` that
 * already has Robolectric set up — co-locating the test here avoids
 * dragging the Robolectric dep into every module that ships
 * SharedPreferences-using utilities.
 *
 * Pins:
 *  - Empty initial state on a fresh install.
 *  - Round-trip persistence of the (did, credentialId, publicKeyHex)
 *    triple.
 *  - `hasSigil` derives from credentialId presence (not did/publicKeyHex)
 *    — important contract because external consumers
 *    (`WalletSeedSource`) use this as their gate.
 *  - `clear` removes the triple but leaves the dismissed flag alone.
 *  - Backup-dismissed flag round-trip + durability (it's the gate that
 *    prevents the restore prompt from re-appearing on every launch).
 */
@RunWith(RobolectricTestRunner::class)
class SigilStateStoreTest {

    private lateinit var context: Context
    private lateinit var store: SigilStateStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric reuses SharedPreferences state across tests in
        // the same VM — clear before each test.
        context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = SigilStateStore(context)
    }

    @Test
    fun `fresh install has no sigil and no dismissed flag`() {
        assertFalse(store.hasSigil())
        assertNull(store.getDid())
        assertNull(store.getCredentialId())
        assertNull(store.getPublicKeyHex())
        assertNull(store.snapshot())
        assertFalse(store.isBackupDismissed())
    }

    @Test
    fun `persistSigil round-trips all three fields`() {
        store.persistSigil(
            did = "did:key:zABC",
            credentialId = "cred-123",
            publicKeyHex = "0214abcd",
        )
        assertTrue(store.hasSigil())
        assertEquals("did:key:zABC", store.getDid())
        assertEquals("cred-123", store.getCredentialId())
        assertEquals("0214abcd", store.getPublicKeyHex())
    }

    @Test
    fun `snapshot returns the full triple after persist`() {
        store.persistSigil("did:key:zABC", "cred-123", "0214abcd")
        val snap = store.snapshot()
        assertEquals("did:key:zABC", snap?.did)
        assertEquals("cred-123", snap?.credentialId)
        assertEquals("0214abcd", snap?.publicKeyHex)
    }

    @Test
    fun `hasSigil reflects credentialId presence specifically`() {
        // Persisting just the credentialId (bypassing persistSigil) is
        // the contract hasSigil cares about. WalletSeedSource queries
        // this as its gate — must agree with this state regardless of
        // which other fields exist.
        context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SigilStateStore.KEY_CREDENTIAL_ID, "cred-only")
            .commit()
        assertTrue(store.hasSigil())
    }

    @Test
    fun `clear removes the triple but leaves dismissed flag intact`() {
        store.persistSigil("did:key:z", "cred", "pub")
        store.markBackupDismissed()
        assertTrue(store.hasSigil())
        assertTrue(store.isBackupDismissed())

        store.clear()
        assertFalse(store.hasSigil())
        assertNull(store.snapshot())
        // Dismissed flag survives reset — the user already told us
        // "I don't want the restore prompt anymore" and that intent
        // outlives a sigil reset.
        assertTrue(store.isBackupDismissed())
    }

    @Test
    fun `markBackupDismissed writes durably so a fresh handle sees it`() {
        store.markBackupDismissed()
        // Open a fresh handle (bypasses the in-memory cache held by
        // the original store instance) to verify `.commit()` actually
        // landed the flag. apply()'s async write would race the
        // post-write SIGKILL of the restore flow.
        val fresh = SigilStateStore(context)
        assertTrue(fresh.isBackupDismissed())
    }
}
