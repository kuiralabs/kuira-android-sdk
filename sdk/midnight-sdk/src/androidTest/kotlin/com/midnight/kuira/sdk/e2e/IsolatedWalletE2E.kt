package com.midnight.kuira.sdk.e2e

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.math.BigInteger

/**
 * Per-test isolated localnet wallet for SDK e2e tests.
 *
 * Each [start] generates a BRAND-NEW random 24-word wallet, so every test runs against a fresh
 * on-chain address. The SDK keys dust + UTXO state by address (`dust_state_<address>` in
 * `DustRepository`, `WHERE address = :address` in the UTXO/dust DAOs), so a fresh address has no
 * prior entries — which structurally eliminates the StaleUtxo(195)/DustDoubleSpend(196)
 * cross-contamination that came from every old e2e test sharing the one canonical wallet. The
 * isolation is a property of the unique address; no storage clearing is required for correctness.
 *
 * Funding crosses the device→host boundary via a logcat marker: [start] logs
 * `KUIRA_FUND_REQ addr=… night=… small=… dust=…`; the host harness (`run-sdk-e2e.sh`) tails for it
 * and runs `mn airdrop --wallet <addr>` × `small`; dust is registered ON-DEVICE by the SDK (a keyed
 * tx the host can't sign). REMOTE proving uses the localnet proof server (`10.0.2.2:6300`), so no
 * on-device proving-key bundle is needed.
 *
 * Tests SKIP (`assumeTrue`), never fail, when localnet/funding is absent — matching the convention
 * in [com.midnight.kuira.sdk.SdkRegistrationE2ETest].
 *
 * Instantiate ONE per `@Test` (in the test body or a `@Before`) so each test gets a distinct
 * wallet; always [close] in `@After`.
 */
class IsolatedWalletE2E(private val context: Context) {

    lateinit var sdk: MidnightSdk
        private set
    lateinit var address: String
        private set

    /**
     * Build a fresh-wallet REMOTE-proving SDK, request host funding of [nightWhole] NIGHT spread
     * across [smallUtxos] separate coins (+ dust if [registerDust]), and block until the coins
     * (and dust) are visible. Returns the ready SDK; SKIPs the test if localnet/funding never lands.
     *
     * @param nightWhole total NIGHT to make available (whole NIGHT; host airdrops it in [smallUtxos] chunks).
     * @param smallUtxos number of separate airdrops → number of small UTXOs (>1 forces multi-input/consolidation).
     */
    suspend fun start(
        nightWhole: Int,
        smallUtxos: Int,
        registerDust: Boolean = true,
        fundingTimeoutMs: Long = FUNDING_TIMEOUT_MS,
    ): MidnightSdk {
        val seed = BIP39.mnemonicToSeed(BIP39.generateMnemonic(24), "")
        sdk = MidnightSdk.Builder(context)
            .network(MidnightNetwork.UNDEPLOYED)
            .seed(seed)
            // REMOTE proving uses localnet's proof server; no on-device proving-key bundle needed.
            .provingMode(ProvingMode.REMOTE)
            .build()
        seed.fill(0) // Builder copies the seed; wipe our handle.
        address = sdk.walletAddress

        // device → host funding request (one line per fresh wallet; serviced by run-sdk-e2e.sh).
        Log.i(TAG, "$FUND_MARKER addr=$address night=$nightWhole small=$smallUtxos dust=$registerDust")

        // Confirm localnet reachability, then wait for the host to credit the coins.
        val reachable = withTimeoutOrNull(INITIAL_SETTLE_MS) { sdk.wallet.balance() }
        assumeTrue("Localnet unreachable (indexer http://10.0.2.2:8088 healthy?)", reachable != null)

        val minNight = BigInteger.valueOf(nightWhole.toLong()).multiply(NIGHT_UNIT)
        val funded = runCatching {
            sdk.wallet.waitForFunding(minNight = minNight, timeoutMs = fundingTimeoutMs)
        }.getOrNull()
        assumeTrue(
            "Host harness must fund $address (>= $nightWhole NIGHT across $smallUtxos coins) — " +
                "run the suite via sdk/midnight-sdk/run-sdk-e2e.sh.",
            funded != null,
        )

        if (registerDust && !funded!!.dustRegistered) {
            // Dust registration is a KEYED tx the DEVICE submits (the host can only airdrop NIGHT).
            // It self-pays from the funded coin's "generationless" dust, which starts ~0 on a
            // brand-new coin and grows as the coin AGES. The SDK now OWNS the maturity wait: its
            // native builder gates on the live ledger params and the SDK backs off + rebuilds until
            // the coin can cover the registration fee — so one call self-heals a fresh wallet
            // without a harness retry loop. We still poll `dustRegistered` for the synced signal.
            val result = runCatching { sdk.registerForDustGeneration() }.getOrNull()
            Log.i(TAG, "registerForDustGeneration result=$result")
            val deadline = System.currentTimeMillis() + DUST_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && !sdk.wallet.balance().dustRegistered) {
                delay(DUST_POLL_MS)
                runCatching { sdk.wallet.refresh() }
            }
            assumeTrue(
                "Dust never registered (SDK self-pay maturity wait exhausted) for $address",
                sdk.wallet.balance().dustRegistered,
            )
        }
        return sdk
    }

    /** Close the SDK (cancels subscriptions, closes its Room + clients). Safe to call once. */
    fun close() {
        if (::sdk.isInitialized) runCatching { sdk.close() }
    }

    companion object {
        private const val TAG = "KuiraE2EFund"

        /** Greppable funding-request prefix the host harness services. Keep in sync with run-sdk-e2e.sh. */
        const val FUND_MARKER = "KUIRA_FUND_REQ"

        private val NIGHT_UNIT = BigInteger.valueOf(1_000_000L) // 1 NIGHT = 1e6 base units

        private const val INITIAL_SETTLE_MS = 15_000L
        private const val FUNDING_TIMEOUT_MS = 5 * 60_000L
        private const val DUST_TIMEOUT_MS = 120_000L
        private const val DUST_POLL_MS = 3_000L
    }
}
