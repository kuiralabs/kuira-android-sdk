package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A contract with a `HistoricMerkleTree` ledger field has to deploy.
 *
 * It could not before: the JS runtime's `StateValue` had no `newBoundedMerkleTree`, and
 * `StateBoundedMerkleTree` was an empty class, so the generated `initialState` called an
 * undefined function and the contract died before any circuit or witness ran
 * (kuiralabs/kuira-android-sdk#4). The second half of the failure was quieter: `leafHash`
 * returned 32 zero bytes, so every leaf in a tree hashed alike and no root could be right.
 *
 * The contract under test is the ledger's own `simple-merkle-tree.compact`, compiled with
 * compactc 0.31.1 — `ledger tree: HistoricMerkleTree<10, Uint<32>>` with an `insert` and a
 * `checkRoot`. It is the minimal shape the reporter isolated, and the only distinguishing
 * feature of a contract that failed against one that worked.
 *
 * Instrumented because the thing being tested is JS running in QuickJS against the native
 * FFI: the host-side unit tests cover each half, and only a device runs them together.
 */
@RunWith(AndroidJUnit4::class)
class MerkleTreeContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    private fun merkleContractJs(): String = loadAsset("contracts/merkle-tree-contract.js")

    /**
     * The contract declares `witness find(content: Uint<32>): MerkleTreePath<10, Uint<32>>`,
     * and the generated constructor resolves every declared witness when it is built — so
     * it must be supplied even though deploying never invokes it. Returning zeroes is
     * honest here precisely because it is never called; a circuit that did call it would
     * need a real path, which is #203's follow-on work, not the deploy being tested.
     */
    private fun witnesses(): Map<String, WitnessProvider> = mapOf(
        "find" to WitnessProvider { _ -> WitnessResult(null, ByteArray(32)) },
    )

    /**
     * The regression itself. A deploy transaction and a contract address mean
     * `initialState` ran to completion, which is precisely what it could not do before.
     */
    @Test
    fun aContractWithAMerkleTreeFieldDeploys() = runBlocking {
        val result = executor.executeConstructor(
            contractJs = merkleContractJs(),
            witnesses = witnesses(),
            initialPrivateState = "{}",
            coinPublicKey = ByteArray(32),
            networkId = "undeployed",
        )

        assertNotNull("a tree-bearing contract must produce a deploy tx", result.unprovenTxHex)
        assertTrue("deploy tx must not be empty", result.unprovenTxHex.isNotEmpty())
        assertEquals("contract address should be 64 hex chars", 64, result.contractAddress.length)
        assertTrue(
            "contract address should be hex",
            result.contractAddress.matches(Regex("^[0-9a-fA-F]+$")),
        )
    }

    /**
     * Deploying twice must behave like any other contract — the tree is blank each time,
     * and nothing about it is shared between deployments.
     */
    @Test
    fun twoDeploysOfTheTreeContractDifferByAddressOnly() = runBlocking {
        val first = executor.executeConstructor(
            contractJs = merkleContractJs(),
            witnesses = witnesses(),
            initialPrivateState = "{}",
            coinPublicKey = ByteArray(32),
            networkId = "undeployed",
        )
        val second = executor.executeConstructor(
            contractJs = merkleContractJs(),
            witnesses = witnesses(),
            initialPrivateState = "{}",
            coinPublicKey = ByteArray(32) { 9 },
            networkId = "undeployed",
        )

        assertNotEquals(
            "a different coin public key must give a different address",
            first.contractAddress,
            second.contractAddress,
        )
        assertTrue(first.unprovenTxHex.isNotEmpty())
        assertTrue(second.unprovenTxHex.isNotEmpty())
    }

    /**
     * The quiet half of the bug, checked at the boundary the contract reaches it through.
     *
     * A leaf hash is `persistent_hash("mdn:lh" || binary_repr(v))`. The two properties that
     * matter are that it is not the zero stub, and that two leaves do not collide — a tree
     * whose every leaf hashed alike would deploy happily and then never match a root.
     */
    @Test
    fun leafHashIsRealAndDistinguishesLeaves() {
        ContractRuntime.ensureLoaded()

        val a = ContractRuntime.leafHashAligned(alignedUint32(1u))
        val b = ContractRuntime.leafHashAligned(alignedUint32(2u))
        assertNotNull("leafHash must return a value", a)
        assertNotNull("leafHash must return a value", b)

        val bytesA = firstAtom(a!!)
        val bytesB = firstAtom(b!!)

        assertEquals("a persistent hash is 32 bytes", 32, bytesA.size)
        assertFalse("must not be the 32 zero bytes of the old stub", bytesA.all { it == 0 })
        assertFalse(
            "two different leaves must not share a hash",
            bytesA.contentEquals(bytesB),
        )
    }

    /**
     * The leaf hash is domain-separated, so it must differ from a plain persistent hash of
     * the same value. Dropping the separator would not produce a different number so much
     * as one the chain disagrees with, and nothing else here would notice.
     */
    @Test
    fun aLeafHashIsNotAPlainPersistentHash() {
        ContractRuntime.ensureLoaded()

        val value = alignedUint32(7u)
        val leaf = firstAtom(ContractRuntime.leafHashAligned(value)!!)
        val plain = firstAtom(ContractRuntime.persistentHashAligned(value)!!)

        assertFalse(
            "the domain separator must change the hash, or it is doing nothing",
            leaf.contentEquals(plain),
        )
    }

    /** A `Uint<32>` AlignedValue in the JSON the runtime exchanges with native code. */
    private fun alignedUint32(n: UInt): String {
        val bytes = (0 until 4).map { (n shr (8 * it)).toInt() and 0xFF }
        return """{"alignment":[{"tag":"atom","value":{"tag":"bytes","length":4}}],""" +
            """"value":[[${bytes.joinToString(",")}]]}"""
    }

    /**
     * The first value atom of a hash FFI's JSON, whichever wrapper it arrives in.
     *
     * The two are deliberately different: `leafHashAligned` returns a full AlignedValue
     * (`{"value":[[...]],"alignment":[...]}`) because the generated contract feeds it
     * straight to `StateValue.newCell`, while `persistentHashAligned` returns a bare Value
     * (`[[...]]`). Reading just the atom keeps a comparison about the bytes rather than
     * the shape around them.
     */
    private fun firstAtom(json: String): IntArray {
        assertFalse("FFI reported an error: $json", json.contains("\"error\""))
        val trimmed = json.trimStart()
        val value: JSONArray = if (trimmed.startsWith("{")) {
            org.json.JSONObject(json).getJSONArray("value")
        } else {
            JSONArray(json)
        }
        val atom: JSONArray = value.getJSONArray(0)
        return IntArray(atom.length()) { atom.getInt(it) }
    }
}
