package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A constructor that reads a witness: the deployer becomes the owner without a separate claim
 * circuit (kuiralabs/kuira-android-sdk#4, constraint 4). The contract is
 * `src/androidTest/compact/constructor-owner.compact`, compiled with compact 0.31.1; its
 * constructor stores the hash of the `localSecretKey` witness, and `isOwner` compares against it.
 *
 * Runs against localnet through `mn serve` (the `bboardSetup` gate provisions both).
 */
@RunWith(AndroidJUnit4::class)
class ConstructorWitnessOnChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var config: MidnightConfig

    @Before
    fun setup() {
        val keys = ProvingKeyManager(context)
        keys.installCircuitKeysFromAssets(KEYS_ASSET_DIR)
        keys.installFromLocalTmp()
        assertTrue(
            "Circuit keys or BLS parameters missing — the Gradle bboardSetup task stages the BLS " +
                "parameters in $CONFIG_DIR; it needs a running localnet (scripts/e2e/prepare-localnet-e2e.sh).",
            keys.hasProvableCircuitKeys(CIRCUITS),
        )
        config = MidnightConfig.Builder(context)
            .indexerGraphqlUrl(readConfig("indexer_url.txt") ?: DEFAULT_INDEXER_URL)
            .walletUrl(DAPP_CONNECTOR_URL)
            .networkId(readConfig("network_id.txt") ?: MidnightNetwork.UNDEPLOYED.rustNetworkId)
            .build()
    }

    @After
    fun teardown() {
        if (::config.isInitialized) config.close()
    }

    @Test
    fun theDeployerBecomesTheOwnerThroughAWitnessReadByTheConstructor(): Unit = runBlocking {
        // Given: a contract deployed by the holder of OWNER_KEY
        val deployed = ownerContract(address = null, secretKey = OWNER_KEY).deploy()
        assertEquals("deploy must finalize: $deployed", TransactionStatus.FINALIZED, deployed.status)

        // When: the owner and someone else each ask whether they own it
        val asOwner = ownerContract(deployed.contractAddress, OWNER_KEY).read("isOwner")
        val asStranger = ownerContract(deployed.contractAddress, STRANGER_KEY).read("isOwner")

        // Then: only the key the constructor read is the owner
        assertEquals("the constructor stored the deployer's tag", "true", asOwner.trim())
        assertEquals("another key is not the owner", "false", asStranger.trim())
    }

    private fun ownerContract(address: String?, secretKey: ByteArray): MidnightContract =
        MidnightContract.create(config) {
            contractJs = context.assets.open(CONTRACT_ASSET)
            this.address = address
            witness("localSecretKey") { WitnessResult(null, secretKey) }
            coinPublicKey = ByteArray(32)
            circuitVerifierKeys = CIRCUITS.associateWith { circuit ->
                context.assets.open("$KEYS_ASSET_DIR/$circuit.verifier").use { it.readBytes() }
            }
        }

    private fun readConfig(filename: String): String? {
        val file = File(CONFIG_DIR, filename)
        return if (file.exists()) file.readText().trim().ifEmpty { null } else null
    }

    private companion object {
        const val CONTRACT_ASSET = "contracts/constructor-owner-contract.js"
        const val KEYS_ASSET_DIR = "constructor-owner-keys"
        val CIRCUITS = listOf("isOwner")

        const val CONFIG_DIR = "/data/local/tmp/bboard_keys"
        val DEFAULT_INDEXER_URL = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED).indexerGraphqlUrl
        const val DAPP_CONNECTOR_URL = "ws://127.0.0.1:9932"

        val OWNER_KEY = ByteArray(32) { 0x0a }
        val STRANGER_KEY = ByteArray(32) { 0x0b }
    }
}
