package com.midnight.kuira.contract

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
    fun `payment facade maps Uint128 to BigInteger and Struct arg to a generated data class`() {
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
        assertTrue(
            "withdraw must delegate to handle.call",
            src.contains("""handle.call("withdraw", amount, recipient"""),
        )

        // The Struct must materialise as a generated data class with its typed field.
        assertTrue(
            "UserAddress data class missing: $src",
            Regex("""data class UserAddress\([\s\S]*?val bytes: ByteArray""").containsMatchIn(src),
        )
    }

    @Test
    fun `enum and vector argument types map to generated enum class and List`() {
        // Synthetic ABI exercising the Enum + Vector branches (no production
        // contract takes these as ARGS today, but the vocabulary allows it and
        // the recursive mapper must handle them).
        val src = render("sample", SAMPLE_ENUM_VECTOR_ABI)

        assertTrue(
            "enum-arg circuit signature wrong: $src",
            Regex("""fun setPhase\(\s*phase: Phase""").containsMatchIn(src),
        )
        assertTrue(
            "Phase enum class missing: $src",
            src.contains("enum class Phase") &&
                src.contains("WAITING") && src.contains("COMPLETE"),
        )
        assertTrue(
            "vector-arg circuit signature wrong: $src",
            Regex("""fun setShoots\(\s*shoots: List<(java\.math\.)?BigInteger>""").containsMatchIn(src),
        )
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
