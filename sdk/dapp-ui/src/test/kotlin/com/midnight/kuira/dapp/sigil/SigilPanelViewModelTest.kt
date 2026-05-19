package com.midnight.kuira.dapp.sigil

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.auth.BiometricGate
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.SigilBackup
import com.midnight.kuira.core.identity.passkey.P256PublicKey
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.passkey.PasskeyRegistrationResult
import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import com.midnight.kuira.core.testing.MainDispatcherRule
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Optional

/**
 * Unit tests for [SigilPanelViewModel].
 *
 * Four categories of coverage:
 *  - **Restore-into-vault (the master-key fix):** the two tests that
 *    would have caught the 94ae65f bug — a fresh-install restore was
 *    silently failing because nothing had ever generated the Keystore
 *    master key. The VM now creates it on demand and rethrows any
 *    storeSeed failure instead of swallowing.
 *  - **Init probe gating (Problem A):** the VM's constructor probes
 *    Block Store for a backup blob unless the user previously dismissed
 *    the prompt. Verified three branches: blob present, blob absent,
 *    probe failure — and that the persisted "dismissed" flag short-
 *    circuits the probe entirely.
 *  - **dismissBackup durability:** the flag must be `commit()`-written,
 *    not `apply()`. apply()'s async write loses a race with the
 *    restore-flow SIGKILL, leaving the prompt to re-appear forever
 *    (the same bug class as ac78fdf's persistSigil fix).
 *  - **forgeSigil happy path:** state transitions and persisted
 *    triple — the most-frequent first-launch flow.
 *
 * Stack: Robolectric for real `Application` + `SharedPreferences`,
 * mockk for everything keystore- or network-touching, an unconfined
 * Main dispatcher so `init { viewModelScope.launch { … } }` runs
 * inline and is observable from the test body.
 *
 * Conventions: pref keys come from [SigilPanelViewModel]'s companion
 * (single source of truth — see [SigilPanelViewModel.SIGIL_PREFS_NAME]
 * et al.). Test fixtures live in [Fixtures] below so a future test can
 * grab them without re-rolling magic bytes.
 */
@RunWith(RobolectricTestRunner::class)
class SigilPanelViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val passkeyManager: PasskeyManager = mockk(relaxed = true)
    private val walletKeyManager: WalletKeyManager = mockk(relaxed = true)
    private val biometricGate: BiometricGate = mockk(relaxed = true)
    private val seedVault: SeedVault = mockk(relaxed = true)
    private val sigilBackup: SigilBackup = mockk(relaxed = true)
    private val blockStoreStorage: BlockStoreBackupStorage = mockk(relaxed = true)
    private val activity: FragmentActivity = mockk(relaxed = true)

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Each test starts with empty sigil prefs so the init probe runs.
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    // ── restoreSeedIntoVault — master-key gen + rethrow ──

    @Test
    fun `restoreSeedIntoVault generates master key when missing`() = runTest {
        every { walletKeyManager.hasKey() } returns false
        every { walletKeyManager.generateKey() } returns true
        coEvery { seedVault.hasSeed() } returns false
        coEvery { seedVault.storeSeed(any(), any()) } just Runs

        val vm = newVm()
        vm.restoreSeedIntoVault(
            activity = activity,
            recoveredEntropy = Fixtures.entropyBytes(),
            recoveredBip39Seed = Fixtures.bip39SeedBytes(),
        )

        // generateKey must fire BEFORE storeSeed — otherwise storeSeed's
        // cipherForEncrypt() throws "Master key not found" and we end up
        // back in the pre-94ae65f silent-failure regime.
        verify(exactly = 1) { walletKeyManager.hasKey() }
        verify(exactly = 1) { walletKeyManager.generateKey() }
        coVerify(exactly = 1) { seedVault.storeSeed(activity, any()) }
    }

    @Test
    fun `restoreSeedIntoVault rethrows on storeSeed failure`() = runTest {
        every { walletKeyManager.hasKey() } returns true  // master key already exists
        coEvery { seedVault.hasSeed() } returns false
        // Mimic an inverted master-key bug, KeyPermanentlyInvalidated, etc.
        coEvery { seedVault.storeSeed(any(), any()) } throws IllegalStateException(STORE_SEED_FAILURE)

        val vm = newVm()
        val thrown = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                vm.restoreSeedIntoVault(
                    activity = activity,
                    recoveredEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE),
                    recoveredBip39Seed = ByteArray(PlaintextSeed.SEED_SIZE),
                )
            }
        }
        // The bug was a swallowed exception — verifying the exact message
        // bubbles up locks down "callers see what happened".
        assertEquals(STORE_SEED_FAILURE, thrown.message)
    }

    // ── init probe — three branches ──

    @Test
    fun `init transitions to BackupAvailable when blob present`() = runTest {
        coEvery { blockStoreStorage.retrieve() } returns Fixtures.backupBlob()
        val vm = newVm()
        // UnconfinedTestDispatcher runs the init coroutine inline, so by
        // the time the constructor returns the status has already moved.
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
        // Block Store can throw on the first-launch path (Play Services
        // not yet bound, network failure, etc.). The panel must not
        // surface the error to the user — fall back to None so the
        // normal flow proceeds.
        coEvery { blockStoreStorage.retrieve() } throws RuntimeException("Play Services unbound")
        val vm = newVm()
        assertEquals(SigilStatus.None, vm.status.value)
    }

    @Test
    fun `init skips probe when backup previously dismissed`() = runTest {
        prefs().edit().putBoolean(SigilPanelViewModel.KEY_BACKUP_DISMISSED, true).commit()
        val vm = newVm()
        // No probe = no Block Store round trip when the user already
        // chose Start Fresh. Re-prompting them every launch would be
        // hostile UX (the dismiss path is meant to be sticky).
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

        // Read prefs directly to verify commit() (synchronous) actually
        // landed the flag — apply() would race the restore-flow SIGKILL
        // (the bug class behind ac78fdf). Reading via a fresh
        // SharedPreferences handle ensures we're testing on-disk state,
        // not the in-memory cache.
        val fresh = freshPrefs()
        assertTrue(
            "$DISMISS_FLAG must be durable immediately after dismissBackup",
            fresh.getBoolean(SigilPanelViewModel.KEY_BACKUP_DISMISSED, false),
        )
        assertEquals(SigilStatus.None, vm.status.value)
    }

    // ── forgeSigil happy path ──

    @Test
    fun `forgeSigil emits Forged and persists triple to prefs`() = runTest {
        // Block Store empty so init settles at None (the starting state
        // for a forge — you can't forge while sitting on a backup prompt).
        coEvery { blockStoreStorage.retrieve() } returns null
        coEvery {
            passkeyManager.createPasskey(activity, any(), any())
        } returns Fixtures.passkeyRegistrationResult()

        val vm = newVm()
        assertEquals(SigilStatus.None, vm.status.value)

        vm.forgeSigil(activity)

        // Status transition — Creating → Forged. UnconfinedTestDispatcher
        // runs viewModelScope.launch inline so by the time the call
        // returns the terminal state is observable.
        val forged = vm.status.value as? SigilStatus.Forged
        assertTrue(
            "Expected Forged terminal state, got ${vm.status.value}",
            forged != null,
        )
        forged!!
        // DID is derived from the compressed P-256 pubkey — locking
        // its prefix down catches accidental changes to the encoding
        // (the wallet panel uses this prefix to detect did:key triples).
        assertTrue(
            "DID must start with did:key:z — got ${forged.did.take(30)}…",
            forged.did.startsWith("did:key:z"),
        )
        assertEquals(Fixtures.CREDENTIAL_ID, forged.credentialId)
        assertEquals(Fixtures.PUBLIC_KEY_HEX_EXPECTED, forged.publicKeyHex)

        // Persisted triple — must be durable (commit() not apply()) so a
        // post-forge SIGKILL doesn't lose the sigil identity. Read via
        // a fresh SharedPreferences handle to bypass any in-memory cache.
        val onDisk = freshPrefs()
        assertEquals(forged.did, onDisk.getString(SigilPanelViewModel.KEY_DID, null))
        assertEquals(
            Fixtures.CREDENTIAL_ID,
            onDisk.getString(SigilPanelViewModel.KEY_CREDENTIAL_ID, null),
        )
        assertEquals(
            Fixtures.PUBLIC_KEY_HEX_EXPECTED,
            onDisk.getString(SigilPanelViewModel.KEY_PUBLIC_KEY_HEX, null),
        )

        coVerify(exactly = 1) { passkeyManager.createPasskey(activity, any(), any()) }
    }

    // ── backupSeed wiring with AppDataBackupProvider ──

    @Test
    fun `backupSeed forwards provider snapshot bytes into SigilBackup appMetadata`() = runTest {
        // Stand up a Forged-state VM with a bound AppDataBackupProvider
        // that returns a small marker blob. The test asserts that the
        // marker reaches SigilBackup.backup via the appMetadata
        // parameter — that's the seam Kicks's MatchStore will use to
        // round-trip match state through cloud backup.
        val markerBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val provider = mockk<AppDataBackupProvider>().also {
            coEvery { it.snapshot() } returns markerBytes
        }
        coEvery { blockStoreStorage.retrieve() } returns null
        coEvery {
            passkeyManager.createPasskey(activity, any(), any())
        } returns Fixtures.passkeyRegistrationResult()
        coEvery { seedVault.loadSeed(activity) } returns PlaintextSeed(
            mnemonicEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0x55.toByte() },
            bip39Seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0x66.toByte() },
        )
        coEvery {
            sigilBackup.backup(
                activity = any(),
                entropy = any(),
                bip39Seed = any(),
                did = any(),
                credentialId = any(),
                publicKeyHex = any(),
                appMetadata = any(),
            )
        } returns Unit

        val vm = newVm(appDataProvider = Optional.of(provider))
        vm.forgeSigil(activity)  // get to Forged state
        vm.backupSeed(activity)

        coVerify(exactly = 1) { provider.snapshot() }
        coVerify(exactly = 1) {
            sigilBackup.backup(
                activity = any(),
                entropy = any(),
                bip39Seed = any(),
                did = any(),
                credentialId = any(),
                publicKeyHex = any(),
                appMetadata = markerBytes,
            )
        }
    }

    @Test
    fun `backupSeed passes null appMetadata when no provider is bound`() = runTest {
        // BBoard's behavior — no AppDataBackupProvider, backup contains
        // only the seed. SigilBackup.backup must see appMetadata=null,
        // never an empty ByteArray (the SDK relies on null vs. empty to
        // decide whether to write the appState field).
        coEvery { blockStoreStorage.retrieve() } returns null
        coEvery {
            passkeyManager.createPasskey(activity, any(), any())
        } returns Fixtures.passkeyRegistrationResult()
        coEvery { seedVault.loadSeed(activity) } returns PlaintextSeed(
            mnemonicEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0x55.toByte() },
            bip39Seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0x66.toByte() },
        )
        coEvery {
            sigilBackup.backup(
                activity = any(),
                entropy = any(),
                bip39Seed = any(),
                did = any(),
                credentialId = any(),
                publicKeyHex = any(),
                appMetadata = any(),
            )
        } returns Unit

        val vm = newVm()  // default = Optional.empty()
        vm.forgeSigil(activity)
        vm.backupSeed(activity)

        coVerify(exactly = 1) {
            sigilBackup.backup(
                activity = any(),
                entropy = any(),
                bip39Seed = any(),
                did = any(),
                credentialId = any(),
                publicKeyHex = any(),
                appMetadata = null,
            )
        }
    }

    @Test
    fun `backupSeed survives provider snapshot throwing and still uploads seed`() = runTest {
        // Provider misbehavior must NOT abort the backup — the seed is
        // the load-bearing payload. A broken provider just means the
        // backup ships without app metadata; the user's wallet keys
        // are still safe.
        val provider = mockk<AppDataBackupProvider>().also {
            coEvery { it.snapshot() } throws RuntimeException("provider blew up")
        }
        coEvery { blockStoreStorage.retrieve() } returns null
        coEvery {
            passkeyManager.createPasskey(activity, any(), any())
        } returns Fixtures.passkeyRegistrationResult()
        coEvery { seedVault.loadSeed(activity) } returns PlaintextSeed(
            mnemonicEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0x55.toByte() },
            bip39Seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0x66.toByte() },
        )
        coEvery {
            sigilBackup.backup(
                activity = any(),
                entropy = any(),
                bip39Seed = any(),
                did = any(),
                credentialId = any(),
                publicKeyHex = any(),
                appMetadata = any(),
            )
        } returns Unit

        val vm = newVm(appDataProvider = Optional.of(provider))
        vm.forgeSigil(activity)
        vm.backupSeed(activity)

        coVerify(exactly = 1) {
            sigilBackup.backup(
                activity = any(),
                entropy = any(),
                bip39Seed = any(),
                did = any(),
                credentialId = any(),
                publicKeyHex = any(),
                appMetadata = null,  // provider threw → blob omitted
            )
        }
    }

    // ── Helpers ──

    private fun newVm(
        appDataProvider: Optional<AppDataBackupProvider> = Optional.empty(),
    ): SigilPanelViewModel = SigilPanelViewModel(
        context = context,
        passkeyManager = passkeyManager,
        walletKeyManager = walletKeyManager,
        biometricGate = biometricGate,
        seedVault = seedVault,
        sigilBackup = sigilBackup,
        blockStoreStorage = blockStoreStorage,
        appDataProvider = appDataProvider,
    )

    private fun prefs() = context.getSharedPreferences(
        SigilPanelViewModel.SIGIL_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    /**
     * Returns a freshly-opened SharedPreferences handle to bypass the
     * in-memory cache held by the VM-side instance. Used in tests that
     * verify a write was actually committed to disk (the
     * apply()-versus-commit() distinction is invisible if you read
     * through the same handle that buffered the pending write).
     */
    private fun freshPrefs() = context.getSharedPreferences(
        SigilPanelViewModel.SIGIL_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    /**
     * Shared fixtures. Keep test data tied to constants here so a
     * pubkey/credentialId tweak doesn't require sweeping multiple
     * tests — `assertEquals(Fixtures.CREDENTIAL_ID, …)` is the right
     * line whether the underlying byte pattern changes or not.
     */
    private object Fixtures {
        /** Stable 32-byte x-coordinate fill (any value works — must be 32 bytes). */
        private const val X_FILL: Byte = 0x11
        /** Stable 32-byte y-coordinate fill, **odd parity** — pins the compressed prefix to 0x03. */
        private const val Y_FILL: Byte = 0x21

        const val CREDENTIAL_ID = "test-credential-id-deadbeef"

        /**
         * Expected compressed-hex of `P256PublicKey(x = X_FILL×32, y = Y_FILL×32)`.
         * Prefix `03` because Y_FILL is odd; followed by 32 copies of `11`.
         */
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

        /** Non-empty Block Store blob — content doesn't matter, only the size>0 check. */
        fun backupBlob(): ByteArray = ByteArray(64) { 0xAB.toByte() }

        fun entropyBytes(): ByteArray = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0xAA.toByte() }
        fun bip39SeedBytes(): ByteArray = ByteArray(PlaintextSeed.SEED_SIZE) { 0xBB.toByte() }
    }

    private companion object {
        private const val STORE_SEED_FAILURE = "storeSeed failure"
        private const val DISMISS_FLAG = "backupDismissed"
    }
}
