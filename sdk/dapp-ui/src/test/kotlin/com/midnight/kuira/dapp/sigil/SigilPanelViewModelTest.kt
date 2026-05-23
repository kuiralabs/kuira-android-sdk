package com.midnight.kuira.dapp.sigil

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.identity.backup.AppStateBackup
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.RestoredAppState
import com.midnight.kuira.core.identity.passkey.P256PublicKey
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.passkey.PasskeyRegistrationResult
import com.midnight.kuira.core.identity.sigil.SigilDerivation
import com.midnight.kuira.core.identity.sigil.SigilIdentityProvider
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.testing.MainDispatcherRule
import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import com.midnight.kuira.sdk.walletseed.SigilSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Optional

/**
 * Unit tests for [SigilPanelViewModel] under the PRF-derived sigil
 * architecture.
 *
 * Coverage areas:
 *  - **Init probe gating:** the VM probes Block Store for an existing
 *    backup unless the user dismissed the prompt. Four branches:
 *    blob present, blob absent, probe failure, dismissed flag short-
 *    circuit.
 *  - **dismissBackup durability:** the flag must be `commit()`-written.
 *  - **forgeSigil:** create passkey → derive sigil DID via
 *    [SigilIdentityProvider] → persist triple → status = Forged.
 *    Two ceremonies on forge.
 *  - **restoreSeed (sign-in):** derive sigil DID via
 *    [SigilIdentityProvider] (no Block Store needed for the sigil
 *    itself), optionally pull appMetadata through
 *    [AppDataBackupProvider]. No SIGKILL — wallet seed is
 *    independently PRF-derived.
 *  - **backupSeed:** forwards appMetadata only — no seed, no sigil
 *    triple in the blob anymore.
 */
@RunWith(RobolectricTestRunner::class)
class SigilPanelViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val passkeyManager: PasskeyManager = mockk(relaxed = true)
    private val sigilIdentityProvider: SigilIdentityProvider = mockk(relaxed = true)
    private val sigilSession: SigilSession = mockk(relaxed = true)
    private val appStateBackup: AppStateBackup = mockk(relaxed = true)
    private val blockStoreStorage: BlockStoreBackupStorage = mockk(relaxed = true)
    private val activity: FragmentActivity = mockk(relaxed = true)

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
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
    fun `forgeSigil chains createPasskey + sigilIdentityProvider and persists triple`() = runTest {
        coEvery { blockStoreStorage.retrieve() } returns null
        coEvery {
            passkeyManager.createPasskey(activity, any(), any())
        } returns Fixtures.passkeyRegistrationResult()
        coEvery {
            sigilIdentityProvider.deriveSigilDid(activity, passkeyManager)
        } returns SigilDerivation(did = Fixtures.PRF_DID, credentialId = Fixtures.CREDENTIAL_ID)

        val vm = newVm()
        assertEquals(SigilStatus.None, vm.status.value)
        vm.forgeSigil(activity)

        val forged = vm.status.value as? SigilStatus.Forged
        assertTrue("expected Forged terminal state, got ${vm.status.value}", forged != null)
        forged!!
        assertEquals(Fixtures.PRF_DID, forged.did)
        assertEquals(Fixtures.CREDENTIAL_ID, forged.credentialId)
        // publicKeyHex comes from the createPasskey response — kept for
        // BBoard's KeyAuthorization flow, NOT used for the DID.
        assertEquals(Fixtures.PUBLIC_KEY_HEX_EXPECTED, forged.publicKeyHex)

        // Both ceremonies fired exactly once.
        coVerify(exactly = 1) { passkeyManager.createPasskey(activity, any(), any()) }
        coVerify(exactly = 1) { sigilIdentityProvider.deriveSigilDid(activity, passkeyManager) }

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
        coVerify(exactly = 0) { sigilIdentityProvider.deriveSigilDid(any(), any()) }
        // No provider bound → no appState restore attempt.
        coVerify(exactly = 0) { appStateBackup.restore(any()) }
    }

    @Test
    fun `restoreSeed hands non-empty appMetadata to AppDataBackupProvider`() = runTest {
        val markerBytes = byteArrayOf(0x77, 0x44, 0x33, 0x22)
        val provider = mockk<AppDataBackupProvider>(relaxed = true)
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        coEvery { sigilSession.signIn(activity) } returns
            SigilDerivation(did = Fixtures.PRF_DID, credentialId = Fixtures.CREDENTIAL_ID)
        coEvery { appStateBackup.restore(activity) } returns
            RestoredAppState(appMetadata = markerBytes.copyOf())

        val vm = newVm(appDataProvider = Optional.of(provider))
        vm.restoreSeed(activity)

        coVerify(exactly = 1) { sigilSession.signIn(activity) }
        coVerify(exactly = 1) { appStateBackup.restore(activity) }
        coVerify(exactly = 1) { provider.restore(any()) }
    }

    @Test
    fun `restoreSeed tolerates app-state restore failure and keeps sigil forged`() = runTest {
        val provider = mockk<AppDataBackupProvider>(relaxed = true)
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        coEvery { sigilSession.signIn(activity) } returns
            SigilDerivation(did = Fixtures.PRF_DID, credentialId = Fixtures.CREDENTIAL_ID)
        // App-state restore fails (legacy v1, empty storage, wrong key);
        // sigil must still land on Forged because the session already
        // succeeded.
        coEvery { appStateBackup.restore(activity) } throws BackupException("No backup found in storage")

        val vm = newVm(appDataProvider = Optional.of(provider))
        vm.restoreSeed(activity)

        val forged = vm.status.value as? SigilStatus.Forged
        assertTrue("expected Forged even when app-state restore failed", forged != null)
        assertEquals(Fixtures.PRF_DID, forged!!.did)
        coVerify(exactly = 0) { provider.restore(any()) }
    }

    // ── backupSeed ──

    @Test
    fun `backupSeed forwards provider snapshot bytes as appMetadata`() = runTest {
        val markerBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val provider = mockk<AppDataBackupProvider>().also {
            coEvery { it.snapshot() } returns markerBytes
        }
        forgedSetup()

        val vm = newVm(appDataProvider = Optional.of(provider))
        vm.forgeSigil(activity)
        vm.backupSeed(activity)

        coVerify(exactly = 1) { provider.snapshot() }
        coVerify(exactly = 1) { appStateBackup.backup(activity = activity, appMetadata = markerBytes) }
    }

    @Test
    fun `backupSeed passes null appMetadata when no provider is bound`() = runTest {
        forgedSetup()

        val vm = newVm()  // no provider
        vm.forgeSigil(activity)
        vm.backupSeed(activity)

        coVerify(exactly = 1) { appStateBackup.backup(activity = activity, appMetadata = null) }
    }

    @Test
    fun `backupSeed survives provider snapshot throwing`() = runTest {
        val provider = mockk<AppDataBackupProvider>().also {
            coEvery { it.snapshot() } throws RuntimeException("provider blew up")
        }
        forgedSetup()

        val vm = newVm(appDataProvider = Optional.of(provider))
        vm.forgeSigil(activity)
        vm.backupSeed(activity)

        // Provider threw → backup proceeds with null appMetadata.
        coVerify(exactly = 1) { appStateBackup.backup(activity = activity, appMetadata = null) }
    }

    // ── Helpers ──

    /** Shared "user has forged a sigil" stubbing. */
    private fun forgedSetup() {
        coEvery { blockStoreStorage.retrieve() } returns null
        coEvery {
            passkeyManager.createPasskey(activity, any(), any())
        } returns Fixtures.passkeyRegistrationResult()
        coEvery {
            sigilIdentityProvider.deriveSigilDid(activity, passkeyManager)
        } returns SigilDerivation(did = Fixtures.PRF_DID, credentialId = Fixtures.CREDENTIAL_ID)
    }

    private fun newVm(
        appDataProvider: Optional<AppDataBackupProvider> = Optional.empty(),
    ): SigilPanelViewModel = SigilPanelViewModel(
        context = context,
        passkeyManager = passkeyManager,
        sigilIdentityProvider = sigilIdentityProvider,
        sigilSession = sigilSession,
        sigilStateStore = SigilStateStore(context),
        appStateBackup = appStateBackup,
        blockStoreStorage = blockStoreStorage,
        appDataProvider = appDataProvider,
    )

    private fun prefs() = context.getSharedPreferences(
        SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE,
    )

    private fun freshPrefs() = context.getSharedPreferences(
        SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE,
    )

    private object Fixtures {
        private const val X_FILL: Byte = 0x11
        private const val Y_FILL: Byte = 0x21

        const val CREDENTIAL_ID = "test-credential-id-deadbeef"

        /** A plausible Ed25519 did:key — the actual value comes from the mocked provider. */
        const val PRF_DID = "did:key:z6MkTestEd25519FromPrfSalt"

        /** Compressed P-256 pubkey hex matching `P256PublicKey(X_FILL×32, Y_FILL×32)`. */
        const val PUBLIC_KEY_HEX_EXPECTED =
            "031111111111111111111111111111111111111111111111111111111111111111"

        fun passkeyRegistrationResult(): PasskeyRegistrationResult = PasskeyRegistrationResult(
            publicKey = P256PublicKey(
                x = ByteArray(32) { X_FILL },
                y = ByteArray(32) { Y_FILL },
            ),
            credentialId = CREDENTIAL_ID,
            registrationResponseJson = "{}",
        )

        /** Non-empty Block Store blob — content doesn't matter, only size>0 for probe. */
        fun backupBlob(): ByteArray = ByteArray(64) { 0xAB.toByte() }
    }
}
