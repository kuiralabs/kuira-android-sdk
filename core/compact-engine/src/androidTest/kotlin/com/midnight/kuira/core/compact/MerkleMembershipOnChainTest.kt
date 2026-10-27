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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A Merkle membership proof, on chain, with the path read from the contract's own tree.
 *
 * A `HistoricMerkleTree` contract deployed after `002c2872`, but could not prove anything:
 * the witness that supplies a membership path calls `findPathForLeaf` on the ledger reader,
 * and the runtime answered "not available in the shim" (kuiralabs/kuira-android-sdk#4 — the
 * doorman's age proof). The contract here is `src/androidTest/compact/merkle-membership.compact`,
 * compiled with compact 0.31.1: `join` inserts a leaf, `proveMember` asks the `memberPath`
 * witness for the leaf's path and asserts `checkRoot` on the root it recomputes.
 *
 * Runs against localnet through `mn serve` (the `bboardSetup` gate provisions both).
 */
@RunWith(AndroidJUnit4::class)
class MerkleMembershipOnChainTest {

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
    fun aJoinedLeafProvesMembershipWithThePathFromTheLedgerTree(): Unit = runBlocking {
        // Given: a deployed tree holding two leaves
        val members = deployedMembership()
        members.call("join", LEAF_A).assertFinalized("join A")
        members.call("join", LEAF_B).assertFinalized("join B")

        // When: the second leaf proves it is a member
        val receipt = members.call("proveMember", LEAF_B)

        // Then: the chain accepted a proof whose checkRoot held against the on-chain tree
        receipt.assertFinalized("proveMember B")
    }

    @Test
    fun aLeafThatNeverJoinedIsRefusedForThatReason(): Unit = runBlocking {
        // Given: a deployed tree holding one leaf
        val members = deployedMembership()
        members.call("join", LEAF_A).assertFinalized("join A")

        // When: a leaf that never joined asks for its path
        val error = try {
            members.call("proveMember", LEAF_OUTSIDER)
            fail("a leaf outside the tree must not produce a membership proof")
            return@runBlocking
        } catch (e: ContractCallException.CircuitExecutionFailed) {
            e
        }

        // Then: the call fails before proving, naming the missing leaf and the tree
        assertTrue(
            "the failure must say the leaf is not in the tree: ${error.message}",
            error.message.orEmpty().contains("the leaf is not in the ledger tree 'members'"),
        )
        assertFalse(
            "the failure is for the app, so it must not carry the runtime's JS stack: ${error.message}",
            JS_STACK_FRAME.containsMatchIn(error.message.orEmpty()),
        )
    }

    @Test
    fun aPathWitnessPointedAtAFieldThatIsNotATreeIsRefusedNamingIt(): Unit = runBlocking {
        // Given: a deployed tree holding one leaf, and a handle whose witness names a misspelt field
        val address = deployedAddress()
        membership(address).call("join", LEAF_A).assertFinalized("join A")
        val misnamed = membership(address, ledgerTree = "memberz")

        // When: that handle proves the joined leaf
        val error = try {
            misnamed.call("proveMember", LEAF_A)
            fail("a witness reading a field that does not exist must not produce a proof")
            return@runBlocking
        } catch (e: ContractCallException.CircuitExecutionFailed) {
            e
        }

        // Then: the failure names the field, so the typo is found without reading the runtime
        assertTrue(
            "the failure must name the missing tree: ${error.message}",
            error.message.orEmpty().contains("has no exported Merkle tree named 'memberz'"),
        )
    }

    @Test
    fun aHandleWithoutThePathWitnessCanStillCallACircuitThatDoesNotUseIt(): Unit = runBlocking {
        // Given: a deployed tree, and a handle that supplies no memberPath witness
        val address = deployedAddress()
        val withoutPath = membership(address, supplyPath = false)

        // When: it calls join, which never asks for a path
        val receipt = withoutPath.call("join", LEAF_A)

        // Then
        receipt.assertFinalized("join without the path witness")
    }

    @Test
    fun aCircuitThatCallsAnUnsuppliedWitnessFailsNamingIt(): Unit = runBlocking {
        // Given: a joined leaf, and a handle that supplies no memberPath witness
        val address = deployedAddress()
        membership(address).call("join", LEAF_A).assertFinalized("join A")
        val withoutPath = membership(address, supplyPath = false)

        // When
        val error = try {
            withoutPath.call("proveMember", LEAF_A)
            fail("a circuit whose witness was not supplied must not produce a proof")
            return@runBlocking
        } catch (e: ContractCallException.CircuitExecutionFailed) {
            e
        }

        // Then
        assertTrue(
            "the failure must name the witness that was not supplied: ${error.message}",
            error.message.orEmpty().contains("the contract called witness 'memberPath', and this contract handle supplies none"),
        )
    }

    private suspend fun deployedMembership(): MidnightContract = membership(deployedAddress())

    private suspend fun deployedAddress(): String {
        val deployed = membership(address = null).deploy()
        assertEquals("deploy must finalize: $deployed", TransactionStatus.FINALIZED, deployed.status)
        return deployed.contractAddress
    }

    private fun membership(
        address: String?,
        ledgerTree: String = MEMBERS_TREE,
        supplyPath: Boolean = true,
    ): MidnightContract = MidnightContract.create(config) {
        contractJs = context.assets.open(CONTRACT_ASSET)
        this.address = address
        if (supplyPath) merklePathWitness("memberPath", ledgerTree = ledgerTree)
        coinPublicKey = ByteArray(32)
        circuitVerifierKeys = CIRCUITS.associateWith { circuit ->
            context.assets.open("$KEYS_ASSET_DIR/$circuit.verifier").use { it.readBytes() }
        }
    }

    private fun TransactionReceipt.assertFinalized(what: String) {
        assertEquals("$what must finalize: $this", TransactionStatus.FINALIZED, status)
        assertTrue("$what must carry its transaction hash: $this", TX_HASH.matches(txHash.orEmpty()))
    }

    private fun readConfig(filename: String): String? {
        val file = File(CONFIG_DIR, filename)
        return if (file.exists()) file.readText().trim().ifEmpty { null } else null
    }

    private companion object {
        const val CONTRACT_ASSET = "contracts/merkle-membership-contract.js"
        const val KEYS_ASSET_DIR = "merkle-membership-keys"
        val CIRCUITS = listOf("join", "proveMember")
        const val MEMBERS_TREE = "members"

        const val CONFIG_DIR = "/data/local/tmp/bboard_keys"
        val DEFAULT_INDEXER_URL = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED).indexerGraphqlUrl
        const val DAPP_CONNECTOR_URL = "ws://127.0.0.1:9932"
        val TX_HASH = Regex("^[0-9a-f]{64}$")
        val JS_STACK_FRAME = Regex("\\n\\s+at ")

        val LEAF_A = ByteArray(32) { 0xa1.toByte() }
        val LEAF_B = ByteArray(32) { 0xb2.toByte() }
        val LEAF_OUTSIDER = ByteArray(32) { 0xee.toByte() }
    }
}
