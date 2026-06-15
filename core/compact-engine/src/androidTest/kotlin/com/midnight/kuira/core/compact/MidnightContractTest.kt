package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration test for the developer-facing MidnightContract API.
 *
 * **Fully automated setup**: The Gradle `bboardSetup` task runs before these tests.
 * It checks localnet, airdrops, registers dust, deploys a fresh **vacant** contract
 * per board-touching test (each `post` needs a vacant board), and pushes the config
 * to the device. No manual steps needed.
 *
 * **Manual override**: Push config files via adb to test against preview/preprod. A
 * single `contract_address.txt` is enough to run any ONE test; `contractFor` falls
 * back to it when the per-test files are absent:
 * ```
 * echo -n "<address>"     | adb shell "cat > /data/local/tmp/bboard_keys/contract_address.txt"
 * echo -n "<indexer_url>" | adb shell "cat > /data/local/tmp/bboard_keys/indexer_url.txt"
 * echo -n "<network_id>"  | adb shell "cat > /data/local/tmp/bboard_keys/network_id.txt"
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MidnightContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provingKeyManager = ProvingKeyManager(context)
    private val testSecretKey = ByteArray(32) { (it + 1).toByte() }

    private lateinit var config: MidnightConfig

    @Before
    fun setup() {
        installProvingKeys()

        val indexerUrl = readConfig("indexer_url.txt") ?: DEFAULT_INDEXER_URL
        val networkId = readConfig("network_id.txt") ?: "undeployed"
        val baseAddress = readConfig("contract_address.txt")

        assumeTrue(
            "No contract address — Gradle bboardSetup task should deploy automatically",
            baseAddress != null && baseAddress.length == 64,
        )
        assumeTrue(
            "Proving keys not installed — run ./scripts/install-bboard-keys.sh",
            provingKeyManager.hasProvableCircuitKeys(BBOARD_CIRCUITS),
        )

        config = MidnightConfig.Builder(context)
            .indexerUrl(indexerUrl)
            .walletUrl(DAPP_CONNECTOR_URL)
            .networkId(networkId)
            .build()
    }

    /**
     * Build a contract bound to a per-test deployed address. Each board-touching
     * test gets its OWN freshly-deployed (vacant) contract so the `post` circuit
     * never hits an occupied board and the test performs a single connector submit
     * (see the `bboardSetup` Gradle task). Falls back to the shared
     * `contract_address.txt` for the manual single-test override documented above.
     */
    private fun contractFor(addressFile: String): MidnightContract {
        val addr = readConfig(addressFile) ?: readConfig("contract_address.txt")
        requireNotNull(addr) { "No contract address ($addressFile) — bboardSetup should deploy one" }
        return MidnightContract.create(config) {
            contractJs = context.assets.open("runtime/bboard-contract-iife.js")
            address = addr
            witness("localSecretKey") { WitnessResult(null, testSecretKey) }
            initialPrivateState = mapOf("secretKey" to ByteArray(32))
            coinPublicKey = ByteArray(32)
        }
    }

    @After
    fun teardown() {
        if (::config.isInitialized) config.close()
    }

    @Test
    fun callPost_oneLiner(): Unit = runBlocking {
        val bboard = contractFor("contract_address_post1.txt")
        val receipt = bboard.call("post", "Hello from MidnightContract!")

        assertEquals(TransactionStatus.SUBMITTED, receipt.status)
        assertTrue("Proven TX should exist", receipt.provenTxHex.isNotEmpty())
        assertTrue("Total time should be positive", receipt.timings.totalMs > 0)
        Log.i(TAG, "Submitted! total=${receipt.timings.totalMs}ms " +
            "(fetch=${receipt.timings.fetchStateMs} execute=${receipt.timings.executeMs} " +
            "prove=${receipt.timings.proveMs} balance=${receipt.timings.balanceMs} " +
            "submit=${receipt.timings.submitMs})")
    }

    @Test
    fun callPost_withProgress(): Unit = runBlocking {
        val bboard = contractFor("contract_address_post2.txt")
        val stages = mutableListOf<ContractCallStage>()
        val receipt = bboard.call("post", "Hello with progress!") { stage ->
            stages.add(stage)
        }

        assertEquals(TransactionStatus.SUBMITTED, receipt.status)
        // Deterministic prefix, then a variable number of granular BalancingDetail
        // updates (their count depends on dust state), so assert the prefix only.
        assertEquals(
            listOf(
                ContractCallStage.FetchingState,
                ContractCallStage.Executing,
                ContractCallStage.Proving,
                ContractCallStage.Balancing,
            ),
            stages.takeWhile { it !is ContractCallStage.BalancingDetail },
        )
        // Submission is reported through the granular detail channel, not as a
        // top-level stage.
        assertTrue(
            "Balance+submit must report BalancingDetail(Submitting): $stages",
            stages.any { it is ContractCallStage.BalancingDetail && it.progress is BalanceProgress.Submitting },
        )
    }

    @Test
    fun prepare_offlineMode(): Unit = runBlocking {
        val bboard = contractFor("contract_address_prepare.txt")
        val prepared = bboard.prepare("post", "Offline prepared!")

        assertTrue("Proven TX should exist", prepared.provenTxHex.isNotEmpty())
        assertTrue("Prove time should be positive", prepared.timings.proveMs > 0)
        assertEquals("post", prepared.circuitName)
    }

    // ── Setup helpers ──

    /**
     * Provision bboard proving keys from the adb-pushed staging dir using the
     * SDK's own (content-aware) provisioners: circuit keys (post/takeDown) plus
     * the **full** BLS parameter set that [ProvingKeyManager.hasBLSParams]
     * requires (2p5–2p15) — copying only a subset makes every test skip.
     */
    private fun installProvingKeys() {
        val tempKeysDir = File("/data/local/tmp/bboard_keys")
        if (!tempKeysDir.exists()) return

        provingKeyManager.installCircuitKeysForProving(
            circuitNames = BBOARD_CIRCUITS,
            keysSourceDir = tempKeysDir,
            zkirSourceDir = tempKeysDir,
        )
        provingKeyManager.installFromLocalTmp()
    }

    private fun readConfig(filename: String): String? {
        val file = File("/data/local/tmp/bboard_keys/$filename")
        return if (file.exists()) file.readText().trim().ifEmpty { null } else null
    }

    companion object {
        private const val TAG = "MidnightContractTest"
        private val DEFAULT_INDEXER_URL = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED).indexerBaseUrl
        private const val DAPP_CONNECTOR_URL = "ws://10.0.2.2:9932"

        private val BBOARD_CIRCUITS = listOf("post", "takeDown")
    }
}
