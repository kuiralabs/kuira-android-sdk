package com.midnight.kuira.dapp.sigil

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.auth.AuthenticationCancelledException
import com.midnight.kuira.core.auth.BiometricGate
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.sigil.SigilDerivation
import com.midnight.kuira.core.identity.sigil.SigilOverwriteException
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.testing.MainDispatcherRule
import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.MidnightWallet
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import com.midnight.kuira.sdk.walletseed.DevSeed
import com.midnight.kuira.sdk.walletseed.EstablishResult
import com.midnight.kuira.sdk.walletseed.SigilSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Optional

/**
 * Unit tests for [SigilPanelViewModel] under the PRF-derived sigil
 * architecture.
 *
 * Coverage areas:
 *  - **Init probe gating:** the VM probes Block Store for an existing
 *  backup unless the user dismissed the prompt. Four branches:
 *  blob present, blob absent, probe failure, dismissed flag short-
 *  circuit.
 *  - **dismissBackup durability:** the flag must be `commit()`-written.
 *  - **forgeSigil:** create passkey → derive sigil DID via
 *  `SigilIdentityProvider` → persist triple → status = Forged.
 *  Two ceremonies on forge.
 *  - **restoreSeed (sign-in):** derive sigil DID via SigilSession, then
 *  pull host app-state through the wallet's silent seed-keyed
 *  [com.midnight.kuira.sdk.MidnightWallet.fetchAppState] into the
 *  [AppDataBackupProvider]. The old PRF backup/restore path is retired
 *  (it clobbered the same Block Store slot with a different key).
 */
@RunWith(RobolectricTestRunner::class)
class SigilPanelViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val sigilSession: SigilSession = mockk(relaxed = true)
    private val blockStoreStorage: BlockStoreBackupStorage = mockk(relaxed = true)
    private val biometricGate: BiometricGate = mockk(relaxed = true)
    private val activity: FragmentActivity = mockk(relaxed = true)

    // The wallet behind the provider — sign-in's app-state restore reads
    // [MidnightWallet.fetchAppState] off it (the retired PRF path is gone).
    private val wallet: MidnightWallet = mockk(relaxed = true)
    private val sdkProvider: MidnightSdkProvider = mockk(relaxed = true)

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        val sdk: MidnightSdk = mockk(relaxed = true)
        every { sdk.wallet } returns wallet
        coEvery { sdkProvider.awaitSdk() } returns sdk
        coEvery { wallet.fetchAppState() } returns null
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    // ── init probe — four branches ──

    @Test
    fun `init transitions to BackupAvailable when blob present`() = runTest {
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        val vm = newVm()
        assertEquals(SigilStatus.BackupAvailable, vm.status.value)
        coVerify(exactly = 1) { blockStoreStorage.retrieve() }
    }

    @Test
    fun `init transitions to None when no blob present`() = runTest {
        coEvery { blockStoreStorage.retrieve() } returns null
        val vm = newVm()
        assertEquals(SigilStatus.None, vm.status.value)
    }

    @Test
    fun `init settles on None when probe throws`() = runTest {
        // Play Services not yet bound, network failure, etc. — must
        // not surface to the user; fall back to None.
        coEvery { blockStoreStorage.retrieve() } throws RuntimeException("Play Services unbound")
        val vm = newVm()
        assertEquals(SigilStatus.None, vm.status.value)
    }

    @Test
    fun `init skips probe when backup previously dismissed`() = runTest {
        prefs().edit().putBoolean(SigilStateStore.KEY_BACKUP_DISMISSED, true).commit()
        val vm = newVm()
        coVerify(exactly = 0) { blockStoreStorage.retrieve() }
        assertEquals(SigilStatus.None, vm.status.value)
    }

    /**
     * The fifth probe branch, and the one a device found: a build that declares a dev seed was
     * still asked to choose between restoring a cloud backup and starting fresh.
     *
     * The seed overrides both answers — it is resolved before the sigil gate and before the vault
     * — so the question has no consequence, and the panel sits on it. Nothing in an automated run
     * can answer it, and the wallet never opens: the starter reported "Forge a sigil above" with a
     * funded wallet one tap away. This asserts the offer is not raised at all.
     */
    @Test
    fun `init skips the offer when this app declares a dev seed`() = runTest {
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        declareDevSeed()

        val vm = newVm()

        assertEquals(SigilStatus.None, vm.status.value)
        coVerify(exactly = 0) { blockStoreStorage.retrieve() }
    }

    // ── dismissBackup durability ──

    @Test
    fun `dismissBackup writes durably so the flag survives restart`() = runTest {
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        val vm = newVm()
        assertEquals(SigilStatus.BackupAvailable, vm.status.value)

        vm.dismissBackup()

        // Fresh handle bypasses the in-memory cache so we're testing
        // on-disk state — apply() vs commit() distinction is invisible
        // through the original handle's buffered write.
        val fresh = freshPrefs()
        assertTrue(
            "${SigilStateStore.KEY_BACKUP_DISMISSED} must be durable immediately after dismissBackup",
            fresh.getBoolean(SigilStateStore.KEY_BACKUP_DISMISSED, false),
        )
        assertEquals(SigilStatus.None, vm.status.value)
    }

    // ── forgeSigil ──

    @Test
    fun `forgeSigil delegates to SigilSession and persists the triple`() = runTest {
        // Post-#23: forge is one PRF-on-create ceremony owned by SigilSession.
        // The VM is a thin wrapper — it surfaces the result + persists the
        // triple (the create returns the P-256 pubkey, kept for KeyAuthorization).
        coEvery { blockStoreStorage.retrieve() } returns null
        coEvery { sigilSession.establishSigil(activity) } returns EstablishResult(
            did = Fixtures.PRF_DID,
            credentialId = Fixtures.CREDENTIAL_ID,
            publicKeyHex = Fixtures.PUBLIC_KEY_HEX_EXPECTED,
            reused = false,
        )

        val vm = newVm()
        assertEquals(SigilStatus.None, vm.status.value)
        vm.forgeSigil(activity)

        val forged = vm.status.value as? SigilStatus.Forged
        assertTrue("expected Forged terminal state, got ${vm.status.value}", forged != null)
        forged!!
        assertEquals(Fixtures.PRF_DID, forged.did)
        assertEquals(Fixtures.CREDENTIAL_ID, forged.credentialId)
        assertEquals(Fixtures.PUBLIC_KEY_HEX_EXPECTED, forged.publicKeyHex)

        // Single delegated ceremony — no direct create/derive from the VM.
        coVerify(exactly = 1) { sigilSession.establishSigil(activity) }

        // Triple is durably persisted (commit() inside SigilStateStore).
        val onDisk = freshPrefs()
        assertEquals(Fixtures.PRF_DID, onDisk.getString(SigilStateStore.KEY_DID, null))
        assertEquals(Fixtures.CREDENTIAL_ID, onDisk.getString(SigilStateStore.KEY_CREDENTIAL_ID, null))
        assertEquals(
            Fixtures.PUBLIC_KEY_HEX_EXPECTED,
            onDisk.getString(SigilStateStore.KEY_PUBLIC_KEY_HEX, null),
        )
    }

    // ── restoreSeed (sign-in flow) ──

    @Test
    fun `restoreSeed delegates to SigilSession and emits Forged on success`() = runTest {
        // Post-SigilSession refactor: the VM is a thin wrapper. The
        // session owns the multi-salt PRF ceremony, the SeedVault
        // pre-warm, and the SigilStateStore write. The VM's job is
        // just to surface the result as a status.
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        coEvery { sigilSession.signIn(activity) } returns
            SigilDerivation(did = Fixtures.PRF_DID, credentialId = Fixtures.CREDENTIAL_ID)

        val vm = newVm()
        assertEquals(SigilStatus.BackupAvailable, vm.status.value)
        vm.restoreSeed(activity)

        val forged = vm.status.value as? SigilStatus.Forged
        assertTrue("expected Forged after sign-in, got ${vm.status.value}", forged != null)
        forged!!
        assertEquals(Fixtures.PRF_DID, forged.did)
        assertEquals(Fixtures.CREDENTIAL_ID, forged.credentialId)
        // Assertion ceremony doesn't return pubkey on sign-in.
        assertEquals("", forged.publicKeyHex)

        // Session is the single entry point — no manual provider/store
        // calls from the VM anymore.
        coVerify(exactly = 1) { sigilSession.signIn(activity) }
        // No provider bound → no app-state fetch attempt.
        coVerify(exactly = 0) { wallet.fetchAppState() }
    }

    @Test
    fun `restoreSeed hands non-empty appMetadata to AppDataBackupProvider`() = runTest {
        val markerBytes = byteArrayOf(0x77, 0x44, 0x33, 0x22)
        val provider = mockk<AppDataBackupProvider>(relaxed = true)
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        coEvery { sigilSession.signIn(activity) } returns
            SigilDerivation(did = Fixtures.PRF_DID, credentialId = Fixtures.CREDENTIAL_ID)
        coEvery { wallet.fetchAppState() } returns markerBytes.copyOf()

        val vm = newVm(appDataProvider = Optional.of(provider))
        vm.restoreSeed(activity)

        coVerify(exactly = 1) { sigilSession.signIn(activity) }
        coVerify(exactly = 1) { wallet.fetchAppState() }
        coVerify(exactly = 1) { provider.restore(any()) }
    }

    @Test
    fun `restoreSeed tolerates app-state restore failure and keeps sigil forged`() = runTest {
        val provider = mockk<AppDataBackupProvider>(relaxed = true)
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        coEvery { sigilSession.signIn(activity) } returns
            SigilDerivation(did = Fixtures.PRF_DID, credentialId = Fixtures.CREDENTIAL_ID)
        // App state is fetched, but the host provider chokes applying it; the
        // sigil must still land on Forged (the session already succeeded — the
        // app-state restore is best-effort and caught).
        coEvery { wallet.fetchAppState() } returns byteArrayOf(0x09, 0x09)
        coEvery { provider.restore(any()) } throws RuntimeException("provider apply blew up")

        val vm = newVm(appDataProvider = Optional.of(provider))
        vm.restoreSeed(activity)

        val forged = vm.status.value as? SigilStatus.Forged
        assertTrue("expected Forged even when app-state restore failed", forged != null)
        assertEquals(Fixtures.PRF_DID, forged!!.did)
        coVerify(exactly = 1) { provider.restore(any()) }
    }

    // ── persistSigil overwrite guard ──

    @Test
    fun `persistSigil refuses to overwrite a different sigil unless replace is set`() {
        val store = SigilStateStore(context)
        store.persistSigil(did = "did:a", credentialId = "cred-A", publicKeyHex = "")

        // Same identity → idempotent re-persist is always allowed (sign-in / reuse).
        store.persistSigil(did = "did:a", credentialId = "cred-A", publicKeyHex = "aa")
        assertEquals("cred-A", freshPrefs().getString(SigilStateStore.KEY_CREDENTIAL_ID, null))

        // Different identity, replace=false → refused; the original survives.
        var threw = false
        try {
            store.persistSigil(did = "did:b", credentialId = "cred-B", publicKeyHex = "")
        } catch (e: SigilOverwriteException) {
            threw = true
        }
        assertTrue("expected SigilOverwriteException on a different-identity overwrite", threw)
        assertEquals("cred-A", freshPrefs().getString(SigilStateStore.KEY_CREDENTIAL_ID, null))

        // Explicit replace=true → the intentional "start fresh" path is allowed.
        store.persistSigil(did = "did:b", credentialId = "cred-B", publicKeyHex = "", replace = true)
        assertEquals("cred-B", freshPrefs().getString(SigilStateStore.KEY_CREDENTIAL_ID, null))
    }

    // ── signOut ──

    @Test
    fun `signOut clears the local sigil and closes the SDK on biometric success`() = runTest {
        // Persist a sigil first → the VM loads it on init and starts Forged.
        SigilStateStore(context).persistSigil(
            did = Fixtures.PRF_DID,
            credentialId = Fixtures.CREDENTIAL_ID,
            publicKeyHex = "",
        )
        coEvery { biometricGate.authenticatePresence(any(), any(), any()) } returns Unit
        val vm = newVm()
        assertTrue("expected Forged start, got ${vm.status.value}", vm.status.value is SigilStatus.Forged)

        vm.signOut(activity)

        assertEquals(SigilStatus.None, vm.status.value)
        assertEquals(
            "local sigil pointer must be cleared",
            null,
            freshPrefs().getString(SigilStateStore.KEY_CREDENTIAL_ID, null),
        )
        verify(exactly = 1) { sdkProvider.close() }
    }

    @Test
    fun `signOut cancelled keeps the sigil and does not close the SDK`() = runTest {
        SigilStateStore(context).persistSigil(
            did = Fixtures.PRF_DID,
            credentialId = Fixtures.CREDENTIAL_ID,
            publicKeyHex = "",
        )
        coEvery { biometricGate.authenticatePresence(any(), any(), any()) } throws
            AuthenticationCancelledException("user cancelled")
        val vm = newVm()

        vm.signOut(activity)

        assertTrue("sigil must survive a cancelled sign-out", vm.status.value is SigilStatus.Forged)
        assertEquals(
            Fixtures.CREDENTIAL_ID,
            freshPrefs().getString(SigilStateStore.KEY_CREDENTIAL_ID, null),
        )
        verify(exactly = 0) { sdkProvider.close() }
    }

    // NOT TESTED HERE: a Block Store probe that HANGS rather than throws. The probe is bounded in
    // production (BACKUP_PROBE_TIMEOUT_MS), but this class's MainDispatcherRule builds its own
    // UnconfinedTestDispatcher with a private scheduler, so runTest's virtual clock never drives
    // the view model's coroutine and the timeout cannot be made to fire. Testing it needs the
    // timeout injected or the rule sharing the test scheduler. Verified on device instead:
    // Midnight Tally sat on "checking for cloud backup…" indefinitely without the bound.

    // ── Helpers ──

    private fun newVm(
        appDataProvider: Optional<AppDataBackupProvider> = Optional.empty(),
    ): SigilPanelViewModel = SigilPanelViewModel(
        sigilSession = sigilSession,
        sigilStateStore = SigilStateStore(context),
        identityProvenance = IdentityProvenanceStore(context),
        blockStoreStorage = blockStoreStorage,
        biometricGate = biometricGate,
        sdkProvider = sdkProvider,
        appDataProvider = appDataProvider,
        appContext = context,
    )

    /** A debuggable host app declaring a seed in its manifest — what a driven example app is. */
    private fun declareDevSeed() {
        val info = shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!
        info.metaData = android.os.Bundle().apply { putString(DevSeed.MANIFEST_KEY, Fixtures.DEV_SEED_HEX) }
        info.flags = info.flags or ApplicationInfo.FLAG_DEBUGGABLE
        context.applicationInfo.flags = info.flags
    }

    private fun prefs() = context.getSharedPreferences(
        SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE,
    )

    private fun freshPrefs() = context.getSharedPreferences(
        SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE,
    )

    private object Fixtures {
        const val CREDENTIAL_ID = "test-credential-id-deadbeef"

        /** A plausible Ed25519 did:key — the actual value comes from the mocked provider. */
        const val PRF_DID = "did:key:z6MkTestEd25519FromPrfSalt"

        /** Compressed P-256 pubkey hex returned by the create ceremony on forge. */
        const val PUBLIC_KEY_HEX_EXPECTED =
            "031111111111111111111111111111111111111111111111111111111111111111"

        /** 64 bytes of hex — the shape [DevSeed] accepts; the value is never used to sign. */
        val DEV_SEED_HEX = "9f".repeat(64)

        /** Non-empty Block Store blob — content doesn't matter, only size>0 for probe. */
        fun backupBlob(): ByteArray = ByteArray(64) { 0xAB.toByte() }
    }
}
