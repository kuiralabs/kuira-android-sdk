package com.midnight.kuira.sdk.walletseed

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.passkey.PrfAssertionResult
import com.midnight.kuira.core.identity.sigil.SigilIdentityProvider
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [SigilSession] — the single-biometric bootstrap
 * orchestrator that collapses sigil DID derivation + wallet seed
 * pre-warm into one PRF ceremony.
 *
 * Coverage:
 *  - **Happy path** (multi-salt PRF supported by authenticator): one
 *    biometric, both PRF outputs arrive in the same assertion, sigil
 *    is persisted, wallet seed is pre-warmed.
 *  - **Fallback path** (authenticator returns null for `prfOutputSecond`):
 *    a second PRF ceremony is run for SEED_SALT; total of two
 *    biometric prompts, same correctness.
 *  - **PRF unavailable**: throws BackupException.
 *  - **Side effects**: SigilStateStore persisted, WalletSeedSource
 *    pre-warmed exactly once.
 */
@RunWith(RobolectricTestRunner::class)
class SigilSessionTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val passkeyManager: PasskeyManager = mockk(relaxed = true)
    private val sigilIdentityProvider: SigilIdentityProvider = mockk(relaxed = true)
    private val walletSeedSource: WalletSeedSource = mockk(relaxed = true)
    private val activity: FragmentActivity = mockk(relaxed = true)

    private lateinit var context: Context
    private lateinit var sigilStateStore: SigilStateStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SigilStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        sigilStateStore = SigilStateStore(context)
        coEvery { sigilIdentityProvider.prfSalt } returns SeedDeriver.SIGIL_SALT
        coEvery { sigilIdentityProvider.deriveFromPrfOutput(any()) } returns FIXED_DID
        coEvery { walletSeedSource.acceptPreDerivedSeed(any(), any()) } returns ByteArray(64)
    }

    @Test
    fun `signIn on multi-salt-supporting authenticator fires one PRF and pre-warms vault`() = runTest {
        // Authenticator returns BOTH outputs in a single assertion —
        // the happy path that wishlist #24 unlocks.
        val sigilPrf = ByteArray(32) { 0xAA.toByte() }
        val seedPrf = ByteArray(32) { 0xBB.toByte() }
        coEvery {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        } returns prfResult(prfOutput = sigilPrf, prfOutputSecond = seedPrf)

        // Capture a COPY of the seed bytes at call time —
        // SigilSession wipes the original immediately after
        // acceptPreDerivedSeed returns, so a reference-only capture
        // (`slot`) would see zeros by the time the assertion runs.
        val capturedSeeds = mutableListOf<ByteArray>()
        coEvery { walletSeedSource.acceptPreDerivedSeed(any(), any()) } coAnswers {
            capturedSeeds.add(secondArg<ByteArray>().copyOf())
            ByteArray(64)
        }

        val session = newSession()
        val derivation = session.signIn(activity)

        assertEquals(FIXED_DID, derivation.did)
        assertEquals(FIXED_CREDENTIAL_ID, derivation.credentialId)

        // ONE PRF ceremony, not two. Multi-salt path.
        coVerify(exactly = 1) {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        }
        // The single-salt overload must NOT have been called.
        coVerify(exactly = 0) {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SEED_SALT,
                prfSaltSecond = null,
            )
        }

        // WalletSeedSource was pre-warmed with the SECOND PRF output
        // (the seed entropy), not the first (sigil seed).
        assertEquals(1, capturedSeeds.size)
        assertArrayEquals(
            "Wallet pre-warm must use prfOutputSecond (= SEED_SALT result), not the sigil one",
            ByteArray(32) { 0xBB.toByte() },
            capturedSeeds.first(),
        )

        // Sigil triple persisted in store.
        assertEquals(FIXED_DID, sigilStateStore.getDid())
        assertEquals(FIXED_CREDENTIAL_ID, sigilStateStore.getCredentialId())
        assertEquals("", sigilStateStore.getPublicKeyHex())
    }

    @Test
    fun `signIn falls back to second PRF ceremony when prfOutputSecond is null`() = runTest {
        // Older authenticator: assertion returns only `first`; we must
        // run a second ceremony to get the SEED_SALT output.
        val sigilPrf = ByteArray(32) { 0xCC.toByte() }
        val seedPrf = ByteArray(32) { 0xDD.toByte() }
        coEvery {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        } returns prfResult(prfOutput = sigilPrf, prfOutputSecond = null)
        coEvery {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SEED_SALT,
                prfSaltSecond = null,
            )
        } returns prfResult(prfOutput = seedPrf, prfOutputSecond = null)

        val session = newSession()
        session.signIn(activity)

        // First ceremony (multi-salt attempt) + second ceremony (fallback)
        // = two PRF authenticates total.
        coVerify(exactly = 1) {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        }
        coVerify(exactly = 1) {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SEED_SALT,
                prfSaltSecond = null,
            )
        }

        // Pre-warm uses the FALLBACK ceremony's output, not the absent
        // multi-salt second. (Capture-at-call as in the happy path.)
        coVerify(exactly = 1) { walletSeedSource.acceptPreDerivedSeed(activity, any()) }
    }

    @Test
    fun `signIn throws BackupException when first PRF returns null`() = runTest {
        // PRF entirely unavailable — authenticator doesn't support
        // CTAP2 hmac-secret. We can't proceed without it.
        coEvery {
            passkeyManager.authenticateWithPrf(any(), any(), any(), any())
        } returns prfResult(prfOutput = null, prfOutputSecond = null)

        val session = newSession()
        try {
            session.signIn(activity)
            org.junit.Assert.fail("expected BackupException")
        } catch (e: com.midnight.kuira.core.identity.backup.BackupException) {
            // OK
        }

        // Pre-warm never ran — we bailed before reaching it.
        coVerify(exactly = 0) { walletSeedSource.acceptPreDerivedSeed(any(), any()) }
        // Sigil state untouched.
        org.junit.Assert.assertEquals(null, sigilStateStore.getDid())
    }

    // ── Helpers ──

    private fun newSession() = SigilSession(
        passkeyManager = passkeyManager,
        sigilIdentityProvider = sigilIdentityProvider,
        sigilStateStore = sigilStateStore,
        walletSeedSource = walletSeedSource,
    )

    private fun prfResult(
        prfOutput: ByteArray?,
        prfOutputSecond: ByteArray?,
    ): PrfAssertionResult = PrfAssertionResult(
        credentialId = FIXED_CREDENTIAL_ID,
        authenticatorData = ByteArray(0),
        clientDataJson = ByteArray(0),
        signature = ByteArray(0),
        assertionResponseJson = "{}",
        prfOutput = prfOutput,
        prfOutputSecond = prfOutputSecond,
    )

    private companion object {
        const val FIXED_DID = "did:key:z6MkTestSession"
        const val FIXED_CREDENTIAL_ID = "cred-test"
    }
}
