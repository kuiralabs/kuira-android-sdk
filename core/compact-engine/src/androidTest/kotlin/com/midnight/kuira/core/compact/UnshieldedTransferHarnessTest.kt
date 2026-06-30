package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of issue #3 (kuira-sdk-android): contracts using
 * `receiveUnshielded` / `sendUnshielded` emit an idx op whose path key is
 * `Key::Stack`. The FFI's old hand-written transcript parser rejected it at
 * transaction assembly with `transcript parse: ... unknown key tag: stack`. The
 * #15 serde refactor deserializes the transcript via upstream serde, which
 * accepts `Key::Stack` — so these circuits assemble.
 *
 * Drives the real QuickJS -> transcript -> native assemble path offline (no
 * proving, no network) — the exact seam Tushar reported. The contract is the
 * canonical midnight-libraries unshielded test contract, compiled with
 * compactc 0.31.0 / runtime 0.16.0.
 */
@RunWith(AndroidJUnit4::class)
class UnshieldedTransferHarnessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = CircuitExecutor(context)

    /** Run a circuit through execute -> transcript -> assemble; return the error message ("" on success). */
    private fun runCircuit(name: String, args: List<String>): String {
        val err = runCatching {
            runBlocking {
                executor.executeCircuit(
                    contractJs = MidnightContract.normalizeContractJs(
                        context.assets.open("contracts/unshielded-contract.js").bufferedReader().readText(),
                    ),
                    contractAddress = "0".repeat(64),
                    circuitName = name,
                    circuitArgs = args,
                    witnesses = emptyMap(),
                    initialPrivateState = "{}",
                    coinPublicKey = ByteArray(32),
                )
            }
        }.exceptionOrNull()?.message.orEmpty()
        Log.i("UnshieldedTest", "circuit=$name assembled=${err.isEmpty()} err=<$err>")
        return err
    }

    // receiveUnshielded(color: Bytes<32>, amount: Uint<128>)
    @Test
    fun receiveUnshielded_assemblesPastStackKey() {
        val err = runCircuit("receiveUnshieldedTest", listOf("new Uint8Array(32)", "1000000n"))
        assertFalse(
            "receiveUnshielded must no longer fail at the #3 stack-key parse; got: <$err>",
            err.contains("unknown key tag: stack"),
        )
    }

    // sendUnshielded(color: Bytes<32>, amount: Uint<128>, recipient) — self variant
    @Test
    fun sendUnshielded_assemblesPastStackKey() {
        val err = runCircuit("sendUnshieldedToSelfTest", listOf("new Uint8Array(32)", "1000000n"))
        assertFalse(
            "sendUnshielded must no longer fail at the #3 stack-key parse; got: <$err>",
            err.contains("unknown key tag: stack"),
        )
    }
}
