package com.midnight.kuira.sdk.walletseed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `constructor migrates away from legacy P-256 DID and clears triple`() {
        // Simulate a pre-migration install: legacy P-256 DID
        // (did:key:zDn…) plus the rest of the triple already persisted.
        // The constructor's migration must detect the legacy prefix
        // and wipe the triple so the next forge / sign-in re-derives
        // via the Ed25519 PRF path.
        val prefs = context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SigilStateStore.KEY_DID, "did:key:zDnLegacyP256Example")
            .putString(SigilStateStore.KEY_CREDENTIAL_ID, "legacy-cred-id")
            .putString(SigilStateStore.KEY_PUBLIC_KEY_HEX, "0213abc")
            .putBoolean(SigilStateStore.KEY_BACKUP_DISMISSED, true)
            .commit()

        // Construct AFTER seeding legacy state — migration runs here.
        val migrated = SigilStateStore(context)

        assertFalse("legacy DID must be cleared", migrated.hasSigil())
        assertNull(migrated.getDid())
        assertNull(migrated.getCredentialId())
        assertNull(migrated.getPublicKeyHex())
        // Dismissed flag is independent of sigil identity; migration
        // must preserve it so the user's prior "stop nagging about
        // restore" intent isn't undone.
        assertTrue(
            "dismissed flag must survive the migration",
            migrated.isBackupDismissed(),
        )
    }

    @Test
    fun `constructor leaves Ed25519 DID untouched`() {
        // A modern Ed25519 DID (did:key:z6Mk…) must NOT trigger
        // migration. Guards against a regression that broadens the
        // detector to match all DIDs.
        val prefs = context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SigilStateStore.KEY_DID, "did:key:z6MkEd25519Example")
            .putString(SigilStateStore.KEY_CREDENTIAL_ID, "cred-id")
            .putString(SigilStateStore.KEY_PUBLIC_KEY_HEX, "0214abcd")
            .commit()

        val constructed = SigilStateStore(context)

        assertTrue(constructed.hasSigil())
        assertEquals("did:key:z6MkEd25519Example", constructed.getDid())
        assertEquals("cred-id", constructed.getCredentialId())
    }

    // ── snapshotFlow reactive observability ──

    @Test
    fun `snapshotFlow exposes initial null on a fresh install`() {
        // Fresh install — no triple persisted, no migration to fire.
        // The flow must start at null so subscribers don't see a stale
        // sigil that doesn't exist on disk.
        assertNull(store.snapshotFlow.value)
    }

    @Test
    fun `snapshotFlow seeds with existing prefs on construction`() {
        // A pre-existing sigil in prefs (e.g. carried over from a
        // previous app session) must be reflected in the flow on the
        // FIRST subscription, without any explicit write through this
        // class.
        val prefs = context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SigilStateStore.KEY_DID, "did:key:z6MkExisting")
            .putString(SigilStateStore.KEY_CREDENTIAL_ID, "existing-cred")
            .putString(SigilStateStore.KEY_PUBLIC_KEY_HEX, "0212abcd")
            .commit()

        val fresh = SigilStateStore(context)

        assertEquals("did:key:z6MkExisting", fresh.snapshotFlow.value?.did)
        assertEquals("existing-cred", fresh.snapshotFlow.value?.credentialId)
    }

    @Test
    fun `snapshotFlow emits on persistSigil`() {
        // Write through persistSigil must update the flow's current
        // value. This is the seam WalletPanelViewModel.observeSigilForAutoRetry
        // relies on to clear SigilRequired when the user signs in.
        store.persistSigil("did:key:z6MkA", "cred-a", "")
        assertEquals("did:key:z6MkA", store.snapshotFlow.value?.did)

        // A subsequent write with a DIFFERENT credential is a legitimate identity
        // replacement (the "start fresh" / restore path), so it passes replace=true
        // — the #250 overwrite guard rejects a silent overwrite otherwise. The seam
        // under test is that the flow tracks the latest write either way.
        store.persistSigil("did:key:z6MkB", "cred-b", "0214", replace = true)
        assertEquals(
            "Subsequent writes must update the flow, not stay pinned on the first",
            "did:key:z6MkB",
            store.snapshotFlow.value?.did,
        )
        assertEquals("0214", store.snapshotFlow.value?.publicKeyHex)
    }

    @Test
    fun `snapshotFlow emits null on clear`() {
        store.persistSigil("did:key:z6MkA", "cred-a", "")
        assertNotNull(store.snapshotFlow.value)

        store.clear()
        assertNull(
            "Clear must drop the flow back to null so observers see the wipe",
            store.snapshotFlow.value,
        )
    }

    @Test
    fun `snapshotFlow is null after legacy P-256 migration clears a stale triple`() {
        // Legacy install: P-256 DID present on disk; constructor runs
        // the migration which wipes it; flow should be null even
        // though the BEFORE-construction state had something stored.
        val prefs = context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SigilStateStore.KEY_DID, "did:key:zDnLegacy")
            .putString(SigilStateStore.KEY_CREDENTIAL_ID, "legacy-cred")
            .putString(SigilStateStore.KEY_PUBLIC_KEY_HEX, "0212abcd")
            .commit()

        val migrated = SigilStateStore(context)

        assertNull(
            "Migration ran during init; flow must reflect post-migration null state",
            migrated.snapshotFlow.value,
        )
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
