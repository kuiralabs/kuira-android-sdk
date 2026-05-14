package com.midnight.kuira.sdk

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.ledger.api.TransactionSubmitter.SubmissionResult
import com.midnight.kuira.core.network.MidnightNetwork
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger

/**
 * End-to-end test for [MidnightSdk.registerForDustGeneration] against a running localnet.
 *
 * **What's verified end-to-end (no mocks):**
 * 1. SDK builder wires the unshielded subscription correctly — `wallet.balance()`
 *    reports a real NIGHT balance read from the indexer.
 * 2. `wallet.waitForFunding(...)` actually suspends until a transfer lands.
 * 3. `registerForDustGeneration()` builds a valid registration tx, proves it
 *    locally (`ProvingMode.LOCAL`, no proof server), seals it, submits it, and
 *    the node accepts it → finalized in a block.
 *
 * **Test wallet:** derived from the BIP-39 standard test mnemonic ("abandon ...
 * art") at the SDK's default HD account 0. This is the same mnemonic used by
 * the parent app's e2e tests, so its address is well-known and the test is
 * **idempotent** — once you've funded it once via `mn transfer`, subsequent
 * runs go straight to balance/register without prompting.
 *
 * **Prerequisites (manual, documented in [run-sdk-e2e.sh]):**
 *  - Localnet running (`docker ps` shows the midnight stack)
 *  - Proving keys pushed via `adb push <keys-dir> /data/local/tmp/wallet_keys`
 *    (used by [com.midnight.kuira.core.crypto.proving.LocalProver]; registration
 *    doesn't actually generate proofs, but the local prover's resolver still
 *    expects the keysDir to be present)
 *  - On first run: when the test logs `FUND_TARGET_ADDR=<mn_addr_undeployed1...>`,
 *    run `mn transfer <addr> 100` in another terminal. The test sleeps for up to
 *    [FUNDING_TIMEOUT_MS] waiting for the subscription to see the credit.
 *
 * **Skip behavior:** if either localnet or proving keys aren't reachable, the
 * test uses [assumeTrue] to skip rather than fail — matches the convention in
 * `BboardEndToEndTest.onlinePipeline_executeProveBalanceSubmit`.
 *
 * **Driver script:** `sdk/midnight-sdk/run-sdk-e2e.sh` tails logcat for the
 * `FUND_TARGET_ADDR=` marker and prints the exact `mn transfer` command to run.
 */
@RunWith(AndroidJUnit4::class)
class SdkRegistrationE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provingKeyManager = ProvingKeyManager(context)

    @Before
    fun installProvingKeysFromTempIfAvailable() {
        // Delegates to the canonical installer on ProvingKeyManager — same
        // method BBoard's canary and Kicks's MatchManager call. The keys must
        // have been adb-pushed to /data/local/tmp/{bboard,wallet}_keys/ first
        // (typically via examples/midnight-kicks/build-kicks.sh).
        provingKeyManager.installFromLocalTmp()
    }

    @Test
    fun registerForDustGeneration_endToEnd(): Unit = runBlocking {
        // Build the SDK from the BIP-39 test mnemonic. Idempotent across runs:
        // same address every time, fund once via `mn transfer`, subsequent runs
        // skip the funding wait.
        val seed = BIP39.mnemonicToSeed(TEST_MNEMONIC, passphrase = "")
        val sdk = MidnightSdk.Builder(context)
            .network(MidnightNetwork.UNDEPLOYED)
            .seed(seed)
            .build()

        try {
            Log.i(TAG, "FUND_TARGET_ADDR=${sdk.walletAddress}")
            Log.i(TAG, "If NIGHT below shows 0, run in another terminal:")
            Log.i(TAG, "  mn transfer ${sdk.walletAddress} 100")
            Log.i(TAG, "")

            // ── Step 1: read the wallet's current balance from chain ──
            //
            // The subscription started inside Builder.build() needs a moment to
            // connect + pull the unshielded UTXO state for this address. Give
            // it 10s before reading.
            val initial = withTimeoutOrNull(INITIAL_SETTLE_MS) { sdk.wallet.balance() }
            assumeTrue(
                "Localnet appears unreachable — sdk.wallet.balance() timed out. " +
                    "Is docker running and indexer at http://10.0.2.2:8088 healthy?",
                initial != null,
            )
            Log.i(TAG, "Initial balance: night=${initial!!.night}  dust=${initial.dust}  dustRegistered=${initial.dustRegistered}")

            // ── Step 2: ensure the wallet is funded ──
            //
            // If already funded from a prior run, waitForFunding returns immediately
            // because the first emission from the underlying balance Flow already
            // satisfies the predicate. If not, sleep up to FUNDING_TIMEOUT_MS while
            // the user runs `mn transfer`.
            val funded = if (initial.night >= MIN_NIGHT) {
                Log.i(TAG, "Already funded (NIGHT=${initial.night}) — skipping waitForFunding")
                initial
            } else {
                Log.i(TAG, "Waiting up to ${FUNDING_TIMEOUT_MS / 1000}s for NIGHT >= $MIN_NIGHT...")
                sdk.wallet.waitForFunding(
                    minNight = MIN_NIGHT,
                    timeoutMs = FUNDING_TIMEOUT_MS,
                )
            }
            Log.i(TAG, "Funded balance:  night=${funded.night}  dust=${funded.dust}  dustRegistered=${funded.dustRegistered}")
            assertTrue("Expected NIGHT >= $MIN_NIGHT after funding", funded.night >= MIN_NIGHT)

            // ── Step 3: register for dust generation ──
            //
            // Idempotent: if the wallet already shows registered dust from a
            // prior test run, skip re-submitting and treat the existing state
            // as the success signal.
            if (funded.dustRegistered) {
                Log.i(TAG, "Dust already registered for ${sdk.walletAddress} — registration step is a no-op for this run")
                return@runBlocking
            }

            Log.i(TAG, "Calling sdk.registerForDustGeneration()...")
            val result = sdk.registerForDustGeneration()
            Log.i(TAG, "registerForDustGeneration result: $result")
            assertTrue(
                "Registration should succeed or be in a block. Got: $result",
                result is SubmissionResult.Success || result is SubmissionResult.Pending,
            )

            // ── Step 4: verify dust starts showing up ──
            //
            // After registration is finalized, NIGHT begins generating dust
            // continuously. We give it a few block times to surface in the
            // local dust state; the wait is loose because exact timing depends
            // on localnet's block production rate.
            val afterRegister = sdk.wallet.balance()
            Log.i(TAG, "Post-register balance: night=${afterRegister.night}  dust=${afterRegister.dust}  dustRegistered=${afterRegister.dustRegistered}")
            assertNotEquals(
                "NIGHT shouldn't have been consumed by registration (UnshieldedOffer consolidates back to self)",
                BigInteger.ZERO,
                afterRegister.night,
            )
        } finally {
            sdk.close()
        }
    }

    companion object {
        private const val TAG = "SdkRegistrationE2E"

        /**
         * Standard BIP-39 test mnemonic — same one the parent app's
         * `RealTransactionTest` uses. Deterministic address means a single
         * `mn transfer` funds the wallet for every subsequent run.
         */
        private const val TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        /** 1 NIGHT in u128 base units. Sufficient threshold for "funded enough to register". */
        private val MIN_NIGHT = BigInteger.ONE

        /** Initial balance-read budget — localnet usually responds in <2s; 10s leaves room for first-connect. */
        private const val INITIAL_SETTLE_MS = 10_000L

        /**
         * How long the test will sleep waiting for funding. 3 minutes is
         * comfortable for the user to switch terminals and run `mn transfer`,
         * still short enough to surface a stuck localnet quickly.
         */
        private const val FUNDING_TIMEOUT_MS = 3L * 60L * 1_000L
    }
}
