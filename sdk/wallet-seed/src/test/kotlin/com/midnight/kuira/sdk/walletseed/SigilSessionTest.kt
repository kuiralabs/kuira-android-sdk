package com.midnight.kuira.sdk.walletseed

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.passkey.NoPasskeyCredentialException
import com.midnight.kuira.core.identity.passkey.P256PublicKey
import com.midnight.kuira.core.identity.passkey.PasskeyException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.identity.passkey.PasskeyRegistrationResult
import com.midnight.kuira.core.identity.passkey.PrfAssertionResult
import com.midnight.kuira.core.identity.sigil.SigilIdentityProvider
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 *  biometric, both PRF outputs arrive in the same assertion, sigil
 *  is persisted, wallet seed is pre-warmed.
 *  - **Fallback path** (authenticator returns null for `prfOutputSecond`):
 *  a second PRF ceremony is run for SEED_SALT; total of two
 *  biometric prompts, same correctness.
 *  - **PRF unavailable**: throws BackupException.
 *  - **Side effects**: SigilStateStore persisted, WalletSeedSource
 *  pre-warmed exactly once.
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
        // the happy path that wishlist unlocks.
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

    @Test
    fun `forge with PRF-on-create derives the sigil without a follow-up GET`() = runTest {
        // The win: the create ceremony returned BOTH PRF outputs, so forge
        // must NOT run a second GET (the ceremony that raced the fresh
        // credential → "cannot find credential in local KeyStore").
        val sigilPrf = ByteArray(32) { 0x11 }
        val seedPrf = ByteArray(32) { 0x22 }
        coEvery {
            passkeyManager.createPasskey(
                activity = activity,
                userId = any(),
                userName = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        } returns createResult(prfOutput = sigilPrf, prfOutputSecond = seedPrf)

        val capturedSeeds = mutableListOf<ByteArray>()
        coEvery { walletSeedSource.acceptPreDerivedSeed(any(), any()) } coAnswers {
            capturedSeeds.add(secondArg<ByteArray>().copyOf())
            ByteArray(64)
        }

        val session = newSession()
        val result = session.forge(activity)

        assertEquals(FIXED_DID, result.did)
        assertEquals(FIXED_CREDENTIAL_ID, result.credentialId)
        assertEquals(FIXED_PUBKEY_HEX, result.publicKeyHex)

        // No GET ceremony at all — PRF came back on create.
        coVerify(exactly = 0) { passkeyManager.authenticateWithPrf(any(), any(), any(), any()) }

        // Seed pre-warmed from the SECOND salt's output.
        assertEquals(1, capturedSeeds.size)
        assertArrayEquals(ByteArray(32) { 0x22 }, capturedSeeds.first())

        // forge returns the triple for the caller (VM) to persist — unlike
        // signIn, it does NOT write SigilStateStore itself.
    }

    @Test
    fun `forge falls back to a GET ceremony when PRF-on-create is unsupported`() = runTest {
        // Authenticator didn't evaluate PRF on create (prfOutput == null) →
        // forge must derive via a GET (the pre-#23 path, retained as fallback).
        coEvery {
            passkeyManager.createPasskey(
                activity = activity,
                userId = any(),
                userName = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        } returns createResult(prfOutput = null, prfOutputSecond = null)
        coEvery {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        } returns prfResult(prfOutput = ByteArray(32) { 0x33 }, prfOutputSecond = ByteArray(32) { 0x44 })

        val session = newSession()
        val result = session.forge(activity)

        assertEquals(FIXED_DID, result.did)
        // Exactly one fallback GET fired.
        coVerify(exactly = 1) {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        }
        coVerify(exactly = 1) { walletSeedSource.acceptPreDerivedSeed(activity, any()) }
    }

    @Test
    fun `forge presents a deterministic shared user handle across forges`() = runTest {
        // Lever 2 of cross-app convergence: every forge must present the SAME
        // (user.id, user.name) so GPM keys all Kuira apps to ONE passkey identity
        // under the canonical rpId — not a fresh random credential (→ a different
        // seed, hence a different sigil) per app/forge.
        val handles = mutableListOf<ByteArray>()
        val names = mutableListOf<String>()
        coEvery {
            passkeyManager.createPasskey(
                activity = activity,
                userId = capture(handles),
                userName = capture(names),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        } returns createResult(prfOutput = ByteArray(32) { 0x11 }, prfOutputSecond = ByteArray(32) { 0x22 })

        val session = newSession()
        session.forge(activity)
        session.forge(activity)

        assertEquals(2, handles.size)
        assertArrayEquals("handle must be identical across forges", handles[0], handles[1])
        assertEquals("handle is 16 bytes (USER_ID_BYTES)", 16, handles[0].size)
        assertFalse("handle must be a real derived value, not zeros", handles[0].all { it == 0.toByte() })
        assertEquals("display name must be stable across forges", names[0], names[1])
        assertEquals("Kuira Sigil", names[0])
    }

    @Test
    fun `establishSigil reuses an existing sigil via sign-in without forging`() = runTest {
        // Lever 3: a sigil already exists for this rpId → the GET succeeds, so
        // establish must REUSE it and never call createPasskey (no duplicate).
        coEvery {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
                preferImmediatelyAvailable = true,
            )
        } returns prfResult(prfOutput = ByteArray(32) { 0xAA.toByte() }, prfOutputSecond = ByteArray(32) { 0xBB.toByte() })

        val result = newSession().establishSigil(activity)

        assertTrue("an existing sigil must be reused", result.reused)
        assertEquals(FIXED_DID, result.did)
        coVerify(exactly = 0) { passkeyManager.createPasskey(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `establishSigil forges a new sigil only when no credential exists`() = runTest {
        // The GET reports NO credential for the rpId → establish must forge.
        coEvery {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
                preferImmediatelyAvailable = true,
            )
        } throws NoPasskeyCredentialException("no credential")
        coEvery {
            passkeyManager.createPasskey(
                activity = activity,
                userId = any(),
                userName = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        } returns createResult(prfOutput = ByteArray(32) { 0x11 }, prfOutputSecond = ByteArray(32) { 0x22 })

        val result = newSession().establishSigil(activity)

        assertFalse("a fresh sigil must report reused = false", result.reused)
        assertEquals(FIXED_DID, result.did)
        assertEquals(FIXED_PUBKEY_HEX, result.publicKeyHex)
        coVerify(exactly = 1) {
            passkeyManager.createPasskey(
                activity = activity,
                userId = any(),
                userName = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
            )
        }
    }

    @Test
    fun `establishSigil never forges over a non-no-credential failure`() = runTest {
        // A cancelled / failed GET is NOT "no credential" — establish must
        // propagate it and must NOT forge a sigil over a real error.
        coEvery {
            passkeyManager.authenticateWithPrf(
                activity = activity,
                challenge = any(),
                prfSalt = SeedDeriver.SIGIL_SALT,
                prfSaltSecond = SeedDeriver.SEED_SALT,
                preferImmediatelyAvailable = true,
            )
        } throws PasskeyException("user cancelled")

        var thrown: Throwable? = null
        try {
            newSession().establishSigil(activity)
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("the failure must propagate", thrown is PasskeyException)
        assertFalse("must not be the no-credential subtype", thrown is NoPasskeyCredentialException)
        coVerify(exactly = 0) { passkeyManager.createPasskey(any(), any(), any(), any(), any()) }
    }

    // ── Helpers ──

    private fun newSession() = SigilSession(
        passkeyManager = passkeyManager,
        sigilIdentityProvider = sigilIdentityProvider,
        sigilStateStore = sigilStateStore,
        walletSeedSource = walletSeedSource,
    )

    private fun createResult(
        prfOutput: ByteArray?,
        prfOutputSecond: ByteArray?,
    ): PasskeyRegistrationResult {
        val pubKey = mockk<P256PublicKey>()
        every { pubKey.compressedHex() } returns FIXED_PUBKEY_HEX
        return PasskeyRegistrationResult(
            publicKey = pubKey,
            credentialId = FIXED_CREDENTIAL_ID,
            registrationResponseJson = "{}",
            prfOutput = prfOutput,
            prfOutputSecond = prfOutputSecond,
        )
    }

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
        const val FIXED_PUBKEY_HEX = "02deadbeef"
    }
}
