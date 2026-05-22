package com.midnight.kuira.sdk.walletseed

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.passkey.PrfAssertionResult
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.testing.MainDispatcherRule
import io.mockk.coEvery
import kotlinx.coroutines.async
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
 * Tests for [WalletSeedSource] — the canonical seed-bootstrap contract
 * every Kuira dApp depends on.
 *
 * Two test surfaces:
 *  1. **State-machine** — four cases covering the bootstrap flow:
 *      - no sigil forged → throws [SigilRequiredException]
 *      - sigil + PRF-flagged vault → cache hit, no PRF call
 *      - sigil + empty vault → PRF derive, storeSeed, flag set
 *      - sigil + legacy unflagged vault → deleteSeed, PRF derive,
 *        storeSeed, flag set (decision B: legacy seeds abandoned)
 *  2. **Dev-seed gate** — five cases covering the
 *     [WalletSeedSource.devSeedBytes] release-mode safety net + hex
 *     decoder.
 *
 * The PRF call is exercised by mocking
 * [PasskeyManager.authenticateWithPrf]; the entropy → seed chain
 * (BIP-39 PBKDF2) runs for real so we're also pinning that a wired-in
 * PRF output produces a valid BIP-39 seed downstream.
 */
@RunWith(RobolectricTestRunner::class)
class WalletSeedSourceTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val seedVault: SeedVault = mockk(relaxed = true)
    private val walletKeyManager: WalletKeyManager = mockk(relaxed = true)
    private val passkeyManager: PasskeyManager = mockk(relaxed = true)
    private val activity: FragmentActivity = mockk(relaxed = true)

    private lateinit var context: Context
    private lateinit var sigilStateStore: SigilStateStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric reuses SharedPreferences state across tests in
        // the same VM. Clear both files so each test starts clean.
        context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(WalletSeedSource.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        sigilStateStore = SigilStateStore(context)
    }

    // ── State-machine cases ──

    @Test
    fun `ensureSeedReady throws SigilRequiredException when no passkey forged`() = runTest {
        // No sigil pref set — sigilStateStore.hasSigil() returns false.
        val source = newSource()
        try {
            source.ensureSeedReady(activity)
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

        val source = newSource()
        val seed = source.ensureSeedReady(activity)

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

        var producedEntropy: ByteArray? = null
        var producedBip39Seed: ByteArray? = null
        coEvery { seedVault.storeSeed(any(), any()) } answers {
            val producer = secondArg<() -> PlaintextSeed>()
            val ps = producer()
            producedEntropy = ps.mnemonicEntropy.copyOf()
            producedBip39Seed = ps.bip39Seed.copyOf()
            ps.wipe()
        }

        val source = newSource()
        val seed = source.ensureSeedReady(activity)

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
        val flagSet = context.getSharedPreferences(
            WalletSeedSource.PREFS_NAME, Context.MODE_PRIVATE,
        ).getBoolean(WalletSeedSource.KEY_SEED_IS_PRF_DERIVED, false)
        assertEquals(true, flagSet)
    }

    @Test
    fun `ensureSeedReady on legacy unflagged vault wipes + re-derives via PRF`() = runTest {
        markSigilForged()
        // Vault has a seed but no PRF flag → legacy random seed.
        // Decision B says abandon, not migrate. After deleteSeed,
        // hasSeed must report false so storeSeed's internal check
        // doesn't trip.
        var deleted = false
        coEvery { seedVault.hasSeed() } answers { !deleted }
        coEvery { seedVault.deleteSeed() } answers { deleted = true }
        every { walletKeyManager.hasKey() } returns true
        stubPrfReturning(prfOutput = ByteArray(32) { 0x77 })
        coEvery { seedVault.storeSeed(any(), any()) } answers {
            val producer = secondArg<() -> PlaintextSeed>()
            producer().wipe()
        }

        val source = newSource()
        source.ensureSeedReady(activity)

        coVerify(exactly = 1) { seedVault.deleteSeed() }
        coVerify(exactly = 1) { passkeyManager.authenticateWithPrf(any(), any(), any()) }
        coVerify(exactly = 1) { seedVault.storeSeed(activity, any()) }
        val flagSet = context.getSharedPreferences(
            WalletSeedSource.PREFS_NAME, Context.MODE_PRIVATE,
        ).getBoolean(WalletSeedSource.KEY_SEED_IS_PRF_DERIVED, false)
        assertEquals(true, flagSet)
    }

    @Test
    fun `concurrent ensureSeedReady on cache miss runs PRF + storeSeed exactly once`() = runTest {
        // The race we're guarding against: two consumers (wallet panel +
        // BBoard) call ensureSeedReady in the same tick on a fresh
        // install. Without serialization both would (1) prompt the user
        // for biometric, (2) call PRF authentication, and (3) try
        // storeSeed — the second would fail because storeSeed requires
        // an empty vault.
        markSigilForged()
        // After the first storeSeed, the vault is "populated" — second
        // caller must see hasSeed=true and the PRF flag set, taking the
        // cache-hit branch instead of double-deriving.
        var stored = false
        coEvery { seedVault.hasSeed() } answers { stored }
        every { walletKeyManager.hasKey() } returns true
        stubPrfReturning(prfOutput = ByteArray(32) { 0x33 })
        coEvery { seedVault.storeSeed(any(), any()) } answers {
            val producer = secondArg<() -> PlaintextSeed>()
            producer().wipe()
            stored = true
        }
        coEvery { seedVault.loadSeed(any()) } returns PlaintextSeed(
            mnemonicEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE),
            bip39Seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0xAA.toByte() },
        )

        val source = newSource()
        // Launch both calls in parallel via coroutineScope — the
        // bootstrapMutex must keep them from double-deriving even when
        // racing.
        kotlinx.coroutines.coroutineScope {
            val deferredA = async { source.ensureSeedReady(activity) }
            val deferredB = async { source.ensureSeedReady(activity) }
            deferredA.await()
            deferredB.await()
        }

        // Exactly one PRF authentication + one storeSeed across both
        // calls. The second call must hit the cache-hit branch.
        coVerify(exactly = 1) { passkeyManager.authenticateWithPrf(any(), any(), any()) }
        coVerify(exactly = 1) { seedVault.storeSeed(activity, any()) }
        coVerify(exactly = 1) { seedVault.loadSeed(activity) }
    }

    // ── Dev-seed override gate ──

    @Test
    fun `devSeedBytes returns null in release mode even with valid hex`() {
        // Release-build safety net. Even if DEV_SEED_HEX somehow
        // shipped non-empty to production, the isDebug=false gate must
        // refuse to honor it. R8 inlines the constant and removes the
        // dead branch in real release builds — this test pins the
        // behavior pre-DCE.
        val validHex = "00".repeat(PlaintextSeed.SEED_SIZE)
        assertNull(WalletSeedSource.devSeedBytes(isDebug = false, hex = validHex))
    }

    @Test
    fun `devSeedBytes returns null on empty hex in debug mode`() {
        assertNull(WalletSeedSource.devSeedBytes(isDebug = true, hex = ""))
    }

    @Test
    fun `devSeedBytes decodes a 128-char hex into 64 bytes`() {
        val alice = "7dc468f62278cd0c14b6674f31531a90b64599d657d3c7ab2adb63395d647f7a" +
            "505de6428fcf8b0d208873f4d5e2a1340c14688067477542f53c48dfea817da4"
        val expected = ByteArray(PlaintextSeed.SEED_SIZE) { i ->
            alice.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val actual = WalletSeedSource.devSeedBytes(isDebug = true, hex = alice)
        assertArrayEquals("dev seed should round-trip the configured hex", expected, actual)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `devSeedBytes rejects wrong-size hex`() {
        WalletSeedSource.devSeedBytes(isDebug = true, hex = "00".repeat(32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `devSeedBytes rejects non-hex characters`() {
        WalletSeedSource.devSeedBytes(
            isDebug = true,
            hex = "zz".repeat(PlaintextSeed.SEED_SIZE),
        )
    }

    // ── Helpers ──

    /** Persist a credential id so SigilStateStore.hasSigil returns true. */
    private fun markSigilForged() {
        context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SigilStateStore.KEY_CREDENTIAL_ID, "test-credential-id")
            .commit()
    }

    /** Set the PRF-derived flag so the cache-hit path is taken. */
    private fun markVaultPrfDerived() {
        context.getSharedPreferences(WalletSeedSource.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WalletSeedSource.KEY_SEED_IS_PRF_DERIVED, true)
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

    private fun newSource(): WalletSeedSource = WalletSeedSource(
        context = context,
        seedVault = seedVault,
        walletKeyManager = walletKeyManager,
        passkeyManager = passkeyManager,
        sigilStateStore = sigilStateStore,
    )
}
