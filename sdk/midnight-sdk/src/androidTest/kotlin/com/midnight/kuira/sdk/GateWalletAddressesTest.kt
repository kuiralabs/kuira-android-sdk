package com.midnight.kuira.sdk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prints the gate wallet's addresses, one per network, so they can be funded.
 *
 * **Why this is not a job for `mn`.** `mn address` accepts only `--seed <hex>` as "32-byte seed
 * as 64 hex characters". The SDK's wallet seed is 64 bytes / 128 hex — `WalletSeedSource`
 * enforces exactly that (`DEV_SEED_HEX_LENGTH = 128`). The two are different layers: `mn` takes
 * entropy, the SDK takes a full BIP-39 seed. So `mn` cannot be asked for the address the apps
 * will actually use, and an address derived from it would be the wrong one to send test tokens
 * to. The only authority on where a given SDK seed lands is the SDK, which is what this runs.
 *
 * **Why instrumented.** Address derivation goes through the native crypto, so it needs a device.
 *
 * Takes the seed as an instrumentation argument rather than reading `BuildConfig.DEV_SEED_HEX`,
 * so it works for any wallet, not only the one baked into the current build:
 *
 * ```
 * ./gradlew :sdk:midnight-sdk:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.midnight.kuira.sdk.GateWalletAddressesTest \
 *   -Pandroid.testInstrumentationRunnerArguments.seedHex=<128 hex chars> \
 *   -Pandroid.testInstrumentationRunnerArguments.blockfrostProjectId=<nightpreprod…>
 * ```
 *
 * Then read them back with: `adb logcat -d -s GateWallet:I`
 *
 * The seed is NEVER logged — only the public addresses it derives.
 */
@RunWith(AndroidJUnit4::class)
class GateWalletAddressesTest {

    @Test
    fun printAddressesForEveryNetwork() {
        val args = InstrumentationRegistry.getArguments()
        val seedHex = args.getString("seedHex")?.trim().orEmpty()
        // Deriving an address needs no indexer, but building a PREPROD config needs its id.
        val projectId = args.getString("blockfrostProjectId")?.trim().orEmpty()

        // Fail loudly rather than skip. A skipped run here looks identical to a passing one in
        // the gate's counts, and the whole point of this test is to produce an answer.
        assertTrue(
            "pass the wallet seed as -Pandroid.testInstrumentationRunnerArguments.seedHex=<hex>; " +
                "it must be 128 hex characters (64 bytes), the size WalletSeedSource requires. " +
                "Note `mn` cannot supply this — it deals in 32-byte seeds.",
            seedHex.length == SEED_HEX_LENGTH,
        )
        assertTrue(
            "seedHex must be hexadecimal",
            seedHex.all { it in "0123456789abcdefABCDEF" },
        )
        assertTrue(
            "pass a PreProd Blockfrost project id as " +
                "-Pandroid.testInstrumentationRunnerArguments.blockfrostProjectId=<id>; " +
                "PREPROD is one of the networks below and its config cannot be built without one.",
            projectId.isNotBlank(),
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val derived = mutableMapOf<MidnightNetwork, Pair<String, String>>()

        Log.i(TAG, "════════ gate wallet addresses ════════")
        for (network in MidnightNetwork.entries) {
            // A fresh copy per network: Builder.seed() copies defensively, but the SDK wipes seed
            // material as it goes, so reusing one array across builds would hand the second
            // network a zeroed seed and silently derive the wrong address.
            val seed = seedHex.hexToBytes()
            val sdk = MidnightSdk.Builder(context)
                .network(network)
                .seed(seed)
                .provingMode(ProvingMode.REMOTE)
                .apply { if (NetworkConfig.requiresBlockfrostProjectId(network)) blockfrostProjectId(projectId) }
                .build()
            seed.fill(0)

            val unshielded = sdk.walletAddress
            val shielded = sdk.shieldedWalletAddress
            derived[network] = unshielded to shielded

            Log.i(TAG, "${network.name}:")
            Log.i(TAG, "  unshielded  $unshielded")
            Log.i(TAG, "  shielded    $shielded")
        }
        Log.i(TAG, "═══════════════════════════════════════")

        // Guards against the failure that would waste real test tokens: funding an address that
        // belongs to a different network, or to a seed that silently came through empty. Each
        // network stamps its own prefix, so identical addresses across networks would mean the
        // network was never applied.
        for ((network, pair) in derived) {
            val (unshielded, shielded) = pair
            assertTrue(
                "$network unshielded address must carry its network prefix " +
                    "'${network.addressPrefix}', got: $unshielded",
                unshielded.startsWith(network.addressPrefix),
            )
            assertTrue(
                "$network shielded address must carry its network prefix " +
                    "'${network.shieldedAddressPrefix}', got: $shielded",
                shielded.startsWith(network.shieldedAddressPrefix),
            )
        }
        assertEquals(
            "every network must derive a distinct unshielded address",
            MidnightNetwork.entries.size,
            derived.values.map { it.first }.toSet().size,
        )
    }

    private fun String.hexToBytes() = ByteArray(length / 2) {
        ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte()
    }

    private companion object {
        private const val TAG = "GateWallet"
        private const val SEED_HEX_LENGTH = 128
    }
}
