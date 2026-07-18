package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof for two issues, offline (QuickJS -> transcript -> native assemble; no proving,
 * no network), against the canonical midnight-libraries unshielded test contract (compactc 0.31.0
 * / runtime 0.16.0):
 *
 * - **#3** — `receiveUnshielded` / `sendUnshielded` emit an idx op keyed `Key::Stack`; the old hand
 *   parser rejected it at assembly (`unknown key tag: stack`), the #15 serde refactor accepts it.
 *   These circuits must still parse past that point.
 * - **#4** — the unshielded money path is opt-in. An unfunded `receiveUnshielded` / `sendUnshielded`
 *   must now FAIL assembly with a clear, actionable error (naming the SDK builder) rather than
 *   build a transaction the node later rejects as "Invalid Transaction".
 *
 * The two guards compose: the unfunded clear error can only be reached if the transcript parsed
 * past the #3 stack-key bug (a parse regression would surface a different error, failing the
 * assertion), so asserting the #4 error also guards #3.
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

    // receiveUnshielded(color: Bytes<32>, amount: Uint<128>) — no funding supplied
    @Test
    fun receiveUnshielded_unfunded_failsWithClearError() {
        val err = runCircuit("receiveUnshieldedTest", listOf("new Uint8Array(32)", "1000000n"))
        // Parsed past the #3 stack-key bug (else this would be a transcript-parse error), and the
        // #4 money-path DX fix now fails the unfunded call with a clear error naming the builder.
        assertTrue(
            "unfunded receiveUnshielded must fail with the clear money-path error; got: <$err>",
            err.contains("UNSHIELDED_VALUE_UNFUNDED"),
        )
        assertTrue(
            "error must name the funding builder; got: <$err>",
            err.contains("buildUnshieldedFundingJson"),
        )
    }

    // sendUnshielded(color: Bytes<32>, amount: Uint<128>, recipient: UserAddress) — send to an
    // external user, a true unshielded-output effect (unlike send-to-self, which nets to a receive).
    @Test
    fun sendUnshielded_unfunded_failsWithClearError() {
        val err = runCircuit(
            "sendUnshieldedToUserTest",
            listOf("new Uint8Array(32)", "1000000n", "{ bytes: new Uint8Array(32) }"),
        )
        assertTrue(
            "unfunded sendUnshielded must fail with the clear money-path error; got: <$err>",
            err.contains("UNSHIELDED_VALUE_UNFUNDED"),
        )
        assertTrue(
            "error must name the withdrawal builder; got: <$err>",
            err.contains("buildUnshieldedWithdrawalJson"),
        )
    }
}
