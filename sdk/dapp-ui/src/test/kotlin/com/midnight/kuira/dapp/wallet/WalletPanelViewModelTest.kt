package com.midnight.kuira.dapp.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.auth.BiometricGate
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.identity.passkey.PrfAssertionResult
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.testing.MainDispatcherRule
import com.midnight.kuira.dapp.sigil.SigilPanelViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [WalletPanelViewModel.ensureSeedReady] under the PRF-derived
 * seed contract.
 *
 * Four behaviors covered:
 *  - No sigil forged → throws [SigilRequiredException].
 *  - Sigil present + PRF-flagged vault → cache hit (loadSeed, no PRF).
 *  - Sigil present + empty vault → PRF derive, storeSeed, set flag.
 *  - Sigil present + legacy vault (no flag) → deleteSeed, PRF derive,
 *    storeSeed, set flag. Decision B: legacy random seeds are
 *    abandoned, not migrated.
 *
 * The PRF call is exercised by mocking [PasskeyManager.authenticateWithPrf];
 * the entropy → seed chain runs for real (BitcoinJ is on the test
 * classpath) so we're also pinning that the wired-in PRF output
 * actually produces a valid BIP-39 seed.
 */
@RunWith(RobolectricTestRunner::class)
class WalletPanelViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val walletKeyManager: WalletKeyManager = mockk(relaxed = true)
    private val biometricGate: BiometricGate = mockk(relaxed = true)
    private val seedVault: SeedVault = mockk(relaxed = true)
    private val passkeyManager: PasskeyManager = mockk(relaxed = true)
    private val activity: FragmentActivity = mockk(relaxed = true)

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric carries SharedPreferences state across tests
        // in the same VM. Clear both files so each test starts clean.
        context.getSharedPreferences(SigilPanelViewModel.SIGIL_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(WalletPanelViewModel.WALLET_PANEL_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `ensureSeedReady throws SigilRequiredException when no passkey forged`() = runTest {
        // No sigil pref set — sigilPrefs.getString(KEY_CREDENTIAL_ID, null) returns null.
        val vm = newVm()
        try {
            vm.ensureSeedReady(activity)
            fail("expected SigilRequiredException")
        } catch (e: SigilRequiredException) {
            // Defense-in-depth contract — must throw, never silently fall back.
        }

        // Confirm we didn't touch the vault. Reaching it would mean the
        // gate failed open and a non-sigil-gated wallet could exist.
        coVerify(exactly = 0) { seedVault.hasSeed() }
        coVerify(exactly = 0) { seedVault.loadSeed(any()) }
        coVerify(exactly = 0) { seedVault.storeSeed(any(), any()) }
    }

    @Test
    fun `ensureSeedReady on PRF-flagged vault loads from cache without PRF`() = runTest {
        markSigilForged()
        markVaultPrfDerived()
        val expectedSeed = ByteArray(PlaintextSeed.SEED_SIZE) { 0xCD.toByte() }
        coEvery { seedVault.hasSeed() } returns true
        coEvery { seedVault.loadSeed(activity) } returns PlaintextSeed(
            mnemonicEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0xEF.toByte() },
            bip39Seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0xCD.toByte() },
        )

        val vm = newVm()
        val seed = vm.ensureSeedReady(activity)

        // Cache hit — no PRF, no storeSeed, no master key generation.
        coVerify(exactly = 0) { passkeyManager.authenticateWithPrf(any(), any(), any()) }
        coVerify(exactly = 0) { seedVault.storeSeed(any(), any()) }
        verify(exactly = 0) { walletKeyManager.generateKey() }
        coVerify(exactly = 1) { seedVault.loadSeed(activity) }
        assertEquals(
            "should return a copy of the cached seed",
            expectedSeed.toList(),
            seed.toList(),
        )
    }

    @Test
    fun `ensureSeedReady on empty vault derives via PRF, stores, and sets the flag`() = runTest {
        markSigilForged()
        coEvery { seedVault.hasSeed() } returns false
        every { walletKeyManager.hasKey() } returns false
        every { walletKeyManager.generateKey() } returns true
        stubPrfReturning(prfOutput = ByteArray(32) { 0x42 })

        // Invoke producer so capturedSeed populates and storeSeed's
        // contract holds — mimics SeedVault wiping the PlaintextSeed
        // it receives.
        var producedEntropy: ByteArray? = null
        var producedBip39Seed: ByteArray? = null
        coEvery { seedVault.storeSeed(any(), any()) } answers {
            val producer = secondArg<() -> PlaintextSeed>()
            val ps = producer()
            producedEntropy = ps.mnemonicEntropy.copyOf()
            producedBip39Seed = ps.bip39Seed.copyOf()
            ps.wipe()
        }

        val vm = newVm()
        val seed = vm.ensureSeedReady(activity)

        verify(exactly = 1) { walletKeyManager.generateKey() }
        coVerify(exactly = 1) { passkeyManager.authenticateWithPrf(any(), any(), any()) }
        coVerify(exactly = 0) { seedVault.deleteSeed() }
        coVerify(exactly = 1) { seedVault.storeSeed(activity, any()) }
        val entropyOut = requireNotNull(producedEntropy) { "producer should have run" }
        val seedOut = requireNotNull(producedBip39Seed) { "producer should have run" }
        assertEquals(PlaintextSeed.ENTROPY_SIZE, entropyOut.size)
        assertEquals(PlaintextSeed.SEED_SIZE, seedOut.size)
        assertEquals(
            "entropy stored in vault must equal the raw PRF output",
            ByteArray(32) { 0x42 }.toList(),
            entropyOut.toList(),
        )
        assertEquals(
            "returned seed should be the BIP-39 seed handed to MidnightSdk",
            seedOut.toList(),
            seed.toList(),
        )
        // Flag is now set — next launch hits the cache path.
        val flagSet = context.getSharedPreferences(
            WalletPanelViewModel.WALLET_PANEL_PREFS_NAME, Context.MODE_PRIVATE
        ).getBoolean(WalletPanelViewModel.KEY_SEED_IS_PRF_DERIVED, false)
        assertEquals(true, flagSet)
    }

    @Test
    fun `ensureSeedReady on legacy unflagged vault wipes + re-derives via PRF`() = runTest {
        markSigilForged()
        // Vault has a seed but no PRF flag → legacy random seed. Decision B
        // says abandon, not migrate. After deleteSeed, hasSeed should
        // report false for the storeSeed call site's internal check.
        var deleted = false
        coEvery { seedVault.hasSeed() } answers { !deleted }
        coEvery { seedVault.deleteSeed() } answers { deleted = true }
        every { walletKeyManager.hasKey() } returns true
        stubPrfReturning(prfOutput = ByteArray(32) { 0x77 })

        coEvery { seedVault.storeSeed(any(), any()) } answers {
            val producer = secondArg<() -> PlaintextSeed>()
            producer().wipe()
        }

        val vm = newVm()
        vm.ensureSeedReady(activity)

        coVerify(exactly = 1) { seedVault.deleteSeed() }
        coVerify(exactly = 1) { passkeyManager.authenticateWithPrf(any(), any(), any()) }
        coVerify(exactly = 1) { seedVault.storeSeed(activity, any()) }
        val flagSet = context.getSharedPreferences(
            WalletPanelViewModel.WALLET_PANEL_PREFS_NAME, Context.MODE_PRIVATE
        ).getBoolean(WalletPanelViewModel.KEY_SEED_IS_PRF_DERIVED, false)
        assertEquals(true, flagSet)
    }

    // ── Action handler boundary (Step 2 user-visible contract) ──

    @Test
    fun `refreshBalance with no sigil emits SigilRequired status`() = runTest {
        // The user-visible end of the gate. ensureSeedReady throws
        // before MidnightSdk.Builder is even invoked, so this test
        // doesn't need to mock the SDK builder — the exception is
        // caught and translated by refreshBalance's outer catch.
        val vm = newVm()
        vm.refreshBalance(devConfig(), activity)
        // viewModelScope.launch + TestDispatcher: drain pending work.
        // UnconfinedTestDispatcher runs the viewModelScope.launch eagerly,
        // so by the time refreshBalance returns, the catch block has fired.

        assertEquals(
            "Action handler must translate the exception, not let it surface as a generic Error",
            WalletStatus.SigilRequired,
            vm.status.value,
        )
    }

    @Test
    fun `registerDust with no sigil emits SigilRequired status`() = runTest {
        val vm = newVm()
        vm.registerDust(devConfig(), activity)
        // UnconfinedTestDispatcher runs the viewModelScope.launch eagerly,
        // so by the time refreshBalance returns, the catch block has fired.

        assertEquals(
            WalletStatus.SigilRequired,
            vm.status.value,
        )
    }

    // ── Dev-seed override gate ──

    @Test
    fun `devSeedBytes returns null in release mode even with valid hex`() {
        // The release-build safety net. Even if DEV_SEED_HEX somehow
        // shipped non-empty to production, the isDebug=false gate must
        // refuse to honor it. R8 inlines the constant and removes the
        // dead branch in real release builds — this test pins the
        // behavior pre-DCE.
        val validHex = "00".repeat(PlaintextSeed.SEED_SIZE)
        assertNull(WalletPanelViewModel.devSeedBytes(isDebug = false, hex = validHex))
    }

    @Test
    fun `devSeedBytes returns null on empty hex in debug mode`() {
        // The "no override configured" path — local.properties has no
        // kuira.dev.seed entry, BuildConfig.DEV_SEED_HEX is "".
        assertNull(WalletPanelViewModel.devSeedBytes(isDebug = true, hex = ""))
    }

    @Test
    fun `devSeedBytes decodes a 128-char hex into 64 bytes`() {
        // Alice's seed — the value Kicks has historically used as
        // TEST_SEED. Pinning the decoder here so we know the dev
        // workflow produces the right bytes.
        val alice = "7dc468f62278cd0c14b6674f31531a90b64599d657d3c7ab2adb63395d647f7a" +
            "505de6428fcf8b0d208873f4d5e2a1340c14688067477542f53c48dfea817da4"
        val expected = ByteArray(PlaintextSeed.SEED_SIZE) { i ->
            alice.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val actual = WalletPanelViewModel.devSeedBytes(isDebug = true, hex = alice)
        assertArrayEquals("dev seed should round-trip the configured hex", expected, actual)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `devSeedBytes rejects wrong-size hex`() {
        // 32 bytes instead of 64 — would silently produce a wallet at
        // the wrong address downstream because BIP-32 derivation
        // would consume different material. Fail loud here.
        WalletPanelViewModel.devSeedBytes(isDebug = true, hex = "00".repeat(32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `devSeedBytes rejects non-hex characters`() {
        WalletPanelViewModel.devSeedBytes(
            isDebug = true,
            hex = "zz".repeat(PlaintextSeed.SEED_SIZE),
        )
    }

    // ── Helpers ──

    /** Persist a credential id so [WalletPanelViewModel.hasSigil] returns true. */
    private fun markSigilForged() {
        context.getSharedPreferences(SigilPanelViewModel.SIGIL_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SigilPanelViewModel.KEY_CREDENTIAL_ID, "test-credential-id")
            .commit()
    }

    /** Set the PRF-derived flag so the cache-hit path is taken. */
    private fun markVaultPrfDerived() {
        context.getSharedPreferences(WalletPanelViewModel.WALLET_PANEL_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WalletPanelViewModel.KEY_SEED_IS_PRF_DERIVED, true)
            .commit()
    }

    /**
     * Stub PasskeyManager.authenticateWithPrf to return a deterministic
     * 32-byte PRF output. The rest of PrfAssertionResult is unused by
     * the seed-derivation path, so dummy values suffice.
     */
    private fun stubPrfReturning(prfOutput: ByteArray) {
        coEvery { passkeyManager.authenticateWithPrf(any(), any(), any()) } returns PrfAssertionResult(
            credentialId = "test-credential-id",
            authenticatorData = ByteArray(0),
            clientDataJson = ByteArray(0),
            signature = ByteArray(0),
            assertionResponseJson = "{}",
            prfOutput = prfOutput,
        )
    }

    /**
     * Minimal WalletConfig for tests that only care about whether the
     * action handler emits the right status — the SDK never gets built
     * because ensureSeedReady throws first.
     */
    private fun devConfig(): WalletConfig = WalletConfig(
        network = MidnightNetwork.UNDEPLOYED,
        provingMode = ProvingMode.DEFAULT,
    )

    private fun newVm(): WalletPanelViewModel = WalletPanelViewModel(
        context = context,
        walletKeyManager = walletKeyManager,
        biometricGate = biometricGate,
        seedVault = seedVault,
        passkeyManager = passkeyManager,
    )
}
