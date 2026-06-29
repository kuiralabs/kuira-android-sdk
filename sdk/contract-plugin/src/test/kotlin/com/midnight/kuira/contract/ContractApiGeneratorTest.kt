package com.midnight.kuira.contract

import java.math.BigInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ContractApiGenerator] — pure, no Gradle / Android / device.
 *
 * Each test feeds a real compactc `contract-info.json` (verbatim from the
 * tifosi `voting` / `payment` contracts) and asserts on the generated Kotlin
 * source, so a regression in the type mapping or the delegated-call shape fails
 * here at build time rather than at a dApp's compile.
 */
class ContractApiGeneratorTest {

    private fun render(alias: String, json: String): String =
        ContractApiGenerator.generate(alias, json).toString()

    /**
     * Mirror of `ArgConverter.toJsExpression`'s type gate (core:compact-engine,
     * `internal`, not on this plugin's classpath): the closed set of Kotlin types
     * the runtime converts to JS. Anything else throws there. Kept in lock-step
     * with that converter — if it gains/loses a supported type, update both.
     */
    private fun argConverterAccepts(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Int, is Long, is BigInteger,
        is ByteArray, is Map<*, *>, is List<*>,
        -> true
        else -> false
    }

    @Test
    fun `voting facade has typed circuits delegating to handle call`() {
        val src = render("voting", VOTING_ABI)

        // Class name derives from the alias (managed-dir name).
        assertTrue("missing facade class", src.contains("class VotingContract"))
        assertTrue(
            "facade must wrap a MidnightContract handle",
            src.contains("private val handle: MidnightContract") ||
                src.contains("handle: MidnightContract"),
        )

        // createPoll(question: String, options: String) — Opaque tsType=string -> String.
        assertTrue(
            "createPoll signature wrong: $src",
            Regex("""fun createPoll\(\s*question: String,\s*options: String""").containsMatchIn(src),
        )
        assertTrue(
            "createPoll must delegate to handle.call",
            src.contains("""handle.call("createPoll", question, options"""),
        )

        // castVote(optionIdx: BigInteger) — Uint -> BigInteger.
        assertTrue(
            "castVote signature wrong: $src",
            Regex("""fun castVote\(\s*optionIdx: (java\.math\.)?BigInteger""").containsMatchIn(src),
        )
        assertTrue(
            "castVote must delegate to handle.call",
            src.contains("""handle.call("castVote", optionIdx"""),
        )

        // closePoll() — no args, just onProgress, delegates by name.
        assertTrue(
            "closePoll signature wrong: $src",
            Regex("""fun closePoll\(\s*onProgress:""").containsMatchIn(src),
        )
        assertTrue(
            "closePoll must delegate to handle.call",
            src.contains("""handle.call("closePoll", onProgress = onProgress)"""),
        )

        // Every circuit is suspend and carries the optional progress callback.
        assertTrue("circuits must be suspend", src.contains("suspend fun createPoll"))
        assertTrue(
            "onProgress param type must match MidnightContract.call",
            src.contains("onProgress: (suspend (ContractCallStage) -> Unit)? = null"),
        )

        // Constructors are NOT in the ABI -> no typed deploy.
        assertFalse("must not generate a typed deploy", src.contains("fun deploy"))
    }

    @Test
    fun `payment facade maps Uint128 to BigInteger and marshals the Struct arg`() {
        val src = render("payment", PAYMENT_ABI)

        assertTrue("missing facade class", src.contains("class PaymentContract"))

        // deposit(amount: BigInteger) — Uint<128> -> BigInteger.
        assertTrue(
            "deposit signature wrong: $src",
            Regex("""fun deposit\(\s*amount: (java\.math\.)?BigInteger""").containsMatchIn(src),
        )

        // withdraw(amount: BigInteger, recipient: UserAddress) — Struct -> generated data class.
        assertTrue(
            "withdraw signature wrong: $src",
            Regex("""fun withdraw\(\s*amount: (java\.math\.)?BigInteger,\s*recipient: UserAddress""")
                .containsMatchIn(src),
        )

        // THE FIX: the struct arg must be MARSHALLED into the ArgConverter-safe
        // shape (recipient.toCallArg()), NOT passed as the bare data class — which
        // would compile but throw IllegalArgumentException at the first call.
        assertTrue(
            "withdraw must marshal the struct arg via toCallArg(): $src",
            src.contains("""handle.call("withdraw", amount, recipient.toCallArg()"""),
        )
        // Scalar args still pass through unchanged (amount, not amount.toCallArg()).
        assertFalse(
            "scalar arg must NOT be marshalled: $src",
            src.contains("amount.toCallArg()"),
        )

        // The Struct must materialise as a generated data class with its typed field.
        assertTrue(
            "UserAddress data class missing: $src",
            Regex("""data class UserAddress\([\s\S]*?val bytes: ByteArray""").containsMatchIn(src),
        )

        // …and a marshalling extension that builds the JS-object Map keyed by the
        // ABI field name. This is the form ArgConverter turns into `{ bytes: ... }`.
        assertTrue(
            "UserAddress.toCallArg() extension missing: $src",
            Regex(
                """fun UserAddress\.toCallArg\(\):\s*Map<String,\s*Any\?>""",
            ).containsMatchIn(src),
        )
        assertTrue(
            "toCallArg() must map the field name to the field value: $src",
            src.contains("\"bytes\" to bytes"),
        )
    }

    @Test
    fun `enum argument circuit is skipped with a comment, not a crashing method`() {
        // An Enum arg has no verified typed marshalling, so the circuit must be
        // GRACEFULLY SKIPPED — no `fun setPhase`, no `Phase` enum class — while the
        // sibling Vector<scalar> circuit still gets a typed method.
        val src = render("sample", SAMPLE_ENUM_VECTOR_ABI)

        assertFalse(
            "enum-arg circuit must NOT be generated as a typed method: $src",
            Regex("""fun setPhase\(""").containsMatchIn(src),
        )
        assertFalse(
            "skipped enum type must NOT be emitted: $src",
            src.contains("enum class Phase"),
        )
        assertTrue(
            "skipped circuit must be documented with a comment naming the type: $src",
            src.contains("setPhase: not generated") && src.contains("Enum"),
        )

        // The Vector<scalar> sibling is unaffected: List<BigInteger>, passes through.
        assertTrue(
            "vector-arg circuit signature wrong: $src",
            Regex("""fun setShoots\(\s*shoots: List<(java\.math\.)?BigInteger>""").containsMatchIn(src),
        )
        assertTrue(
            "vector-of-scalar must pass through unmarshalled: $src",
            src.contains("""handle.call("setShoots", shoots, onProgress = onProgress)"""),
        )
    }

    @Test
    fun `marshalled struct is a Map that ArgConverter accepts, where the bare data class throws`() {
        // The generator's whole reason to exist is to keep a struct arg from
        // reaching ArgConverter as a non-(String/Int/Long/BigInteger/Boolean/
        // ByteArray/Map/List/null) value, which ArgConverter rejects. We can't pull
        // the internal ArgConverter onto this plugin's classpath, so we mirror its
        // accept/reject CONTRACT here and prove the marshalled shape (a Map) passes
        // the same gate that the bare data class fails.
        data class UserAddress(val bytes: ByteArray)

        // The bare data class — what the BUGGY generator passed straight through.
        assertFalse(
            "a bare generated data class is NOT an ArgConverter-safe type",
            argConverterAccepts(UserAddress(ByteArray(32))),
        )

        // The marshalled form — what the FIXED generator's toCallArg() produces.
        val marshalled: Map<String, Any?> = mapOf("bytes" to ByteArray(32))
        assertTrue(
            "the marshalled Map IS an ArgConverter-safe type",
            argConverterAccepts(marshalled),
        )
        assertTrue("marshalled value must key by ABI field name", marshalled.containsKey("bytes"))
        assertTrue("marshalled field must be the ByteArray", marshalled["bytes"] is ByteArray)
    }

    private companion object {
        // Verbatim from kuira-tifosi/contract/src/managed/voting/compiler/contract-info.json
        val VOTING_ABI = """
        {
          "compiler-version": "0.31.0",
          "language-version": "0.23.0",
          "runtime-version": "0.16.0",
          "circuits": [
            {
              "name": "createPoll",
              "pure": false,
              "proof": true,
              "arguments": [
                { "name": "question", "type": { "type-name": "Opaque", "tsType": "string" } },
                { "name": "options", "type": { "type-name": "Opaque", "tsType": "string" } }
              ],
              "result-type": { "type-name": "Tuple", "types": [] }
            },
            {
              "name": "castVote",
              "pure": false,
              "proof": true,
              "arguments": [
                { "name": "optionIdx", "type": { "type-name": "Uint", "maxval": 18446744073709551615 } }
              ],
              "result-type": { "type-name": "Tuple", "types": [] }
            },
            {
              "name": "closePoll",
              "pure": false,
              "proof": true,
              "arguments": [],
              "result-type": { "type-name": "Tuple", "types": [] }
            }
          ],
          "witnesses": [],
          "contracts": [],
          "ledger": []
        }
        """.trimIndent()

        // Verbatim from kuira-tifosi/contract/src/managed/payment/compiler/contract-info.json
        val PAYMENT_ABI = """
        {
          "compiler-version": "0.31.0",
          "language-version": "0.23.0",
          "runtime-version": "0.16.0",
          "circuits": [
            {
              "name": "deposit",
              "pure": false,
              "proof": true,
              "arguments": [
                { "name": "amount", "type": { "type-name": "Uint", "maxval": 340282366920938463463374607431768211455 } }
              ],
              "result-type": { "type-name": "Tuple", "types": [] }
            },
            {
              "name": "withdraw",
              "pure": false,
              "proof": true,
              "arguments": [
                { "name": "amount", "type": { "type-name": "Uint", "maxval": 340282366920938463463374607431768211455 } },
                {
                  "name": "recipient",
                  "type": {
                    "type-name": "Struct",
                    "name": "UserAddress",
                    "elements": [
                      { "name": "bytes", "type": { "type-name": "Bytes", "length": 32 } }
                    ]
                  }
                }
              ],
              "result-type": { "type-name": "Tuple", "types": [] }
            }
          ],
          "witnesses": [],
          "contracts": [],
          "ledger": []
        }
        """.trimIndent()

        // Synthetic: Enum + Vector circuit ARGS (drawn from the penalty Phase enum
        // + a Vector<Uint> shoot list) to cover the recursive mapper's branches.
        val SAMPLE_ENUM_VECTOR_ABI = """
        {
          "compiler-version": "0.31.0",
          "language-version": "0.23.0",
          "runtime-version": "0.16.0",
          "circuits": [
            {
              "name": "setPhase",
              "pure": false,
              "proof": true,
              "arguments": [
                {
                  "name": "phase",
                  "type": {
                    "type-name": "Enum",
                    "name": "Phase",
                    "elements": ["WAITING", "COMMITTING", "REVEALING", "COMPLETE"]
                  }
                }
              ],
              "result-type": { "type-name": "Tuple", "types": [] }
            },
            {
              "name": "setShoots",
              "pure": false,
              "proof": true,
              "arguments": [
                {
                  "name": "shoots",
                  "type": {
                    "type-name": "Vector",
                    "length": 5,
                    "type": { "type-name": "Uint", "maxval": 255 }
                  }
                }
              ],
              "result-type": { "type-name": "Tuple", "types": [] }
            }
          ],
          "witnesses": [],
          "contracts": [],
          "ledger": []
        }
        """.trimIndent()
    }
}
