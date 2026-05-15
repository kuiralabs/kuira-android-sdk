package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
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
 * It checks localnet, airdrops, registers dust, deploys a fresh contract,
 * and pushes the config to the device. No manual steps needed.
 *
 * **Manual override**: Push config files via adb to test against preview/preprod:
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
    private lateinit var bboard: MidnightContract

    @Before
    fun setup() {
        installProvingKeys()

        val indexerUrl = readConfig("indexer_url.txt") ?: DEFAULT_INDEXER_URL
        val networkId = readConfig("network_id.txt") ?: "undeployed"
        val contractAddress = readConfig("contract_address.txt")

        assumeTrue(
            "No contract address — Gradle bboardSetup task should deploy automatically",
            contractAddress != null && contractAddress.length == 64,
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

        bboard = MidnightContract.create(config) {
            contractJs = context.assets.open("runtime/bboard-contract-iife.js")
            address = contractAddress!!
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
        val stages = mutableListOf<String>()
        val receipt = bboard.call("post", "Hello with progress!") { stage ->
            stages.add(stage::class.simpleName ?: "unknown")
        }

        assertEquals(TransactionStatus.SUBMITTED, receipt.status)
        assertEquals(
            listOf("FetchingState", "Executing", "Proving", "Balancing", "Submitting"),
            stages,
        )
    }

    @Test
    fun prepare_offlineMode(): Unit = runBlocking {
        val prepared = bboard.prepare("post", "Offline prepared!")

        assertTrue("Proven TX should exist", prepared.provenTxHex.isNotEmpty())
        assertTrue("Prove time should be positive", prepared.timings.proveMs > 0)
        assertEquals("post", prepared.circuitName)
    }

    // ── Setup helpers ──

    private fun installProvingKeys() {
        val tempKeysDir = File("/data/local/tmp/bboard_keys")
        if (!tempKeysDir.exists()) return

        val keysDir = provingKeyManager.keysDir
        keysDir.mkdirs()

        for (circuit in BBOARD_CIRCUITS) {
            for (ext in listOf("prover", "verifier", "bzkir")) {
                val src = File(tempKeysDir, "$circuit.$ext")
                val dst = File(keysDir, "$circuit.$ext")
                if (src.exists() && !dst.exists()) src.copyTo(dst)
            }
        }
        for (k in BLS_PARAM_POWERS) {
            val name = "bls_midnight_2p$k"
            val src = File(tempKeysDir, name)
            val dst = File(keysDir, name)
            if (src.exists() && !dst.exists()) src.copyTo(dst)
        }
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
        private val BLS_PARAM_POWERS = listOf(13, 14, 15)
    }
}
