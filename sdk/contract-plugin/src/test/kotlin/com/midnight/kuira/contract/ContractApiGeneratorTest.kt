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
    fun `enum argument circuit is generated as a typed enum marshalled by ordinal`() {
        // A C-style Enum arg marshals as its ordinal via CompactEnum(ordinal): the circuit gets a
        // typed method taking a generated `enum class Phase`, and the call wraps `phase.ordinal`.
        val src = render("sample", SAMPLE_ENUM_VECTOR_ABI)

        assertTrue(
            "enum-arg circuit must be generated with a typed enum param: $src",
            Regex("""fun setPhase\(\s*phase: Phase""").containsMatchIn(src),
        )
        assertTrue(
            "the enum type must be emitted: $src",
            src.contains("enum class Phase"),
        )
        assertTrue(
            "enum arg must marshal as CompactEnum(phase.ordinal): $src",
            src.contains("""handle.call("setPhase", CompactEnum(phase.ordinal), onProgress = onProgress)"""),
        )
        assertFalse(
            "enum-arg circuit must no longer be skipped: $src",
            src.contains("setPhase: not generated"),
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
    fun `struct arg with an enum field un-gates the circuit and marshals the field as CompactEnum`() {
        // The real Vault shape: proposeWithdrawal(to: Recipient{ kind: enum, address: Bytes<32> }).
        // Before enum support the whole circuit was skipped because a struct field was an Enum; now
        // it's generated and the struct's toCallArg() marshals the enum field by ordinal.
        val src = render("vault", STRUCT_WITH_ENUM_FIELD_ABI)

        assertTrue(
            "circuit with an enum-bearing struct arg must be generated: $src",
            Regex("""fun proposeWithdrawal\(\s*to: Recipient""").containsMatchIn(src),
        )
        assertTrue("enum field type must be emitted: $src", src.contains("enum class RecipientKind"))
        assertTrue("struct type must be emitted: $src", src.contains("data class Recipient"))
        // The struct's toCallArg() marshals the enum field by ordinal and the bytes field passes through.
        assertTrue(
            "struct toCallArg must marshal the enum field as CompactEnum(kind.ordinal): $src",
            src.contains("\"kind\" to CompactEnum(kind.ordinal)"),
        )
        assertTrue(
            "struct toCallArg must pass the Bytes field through: $src",
            src.contains("\"address\" to address"),
        )
        assertFalse(
            "circuit must no longer be skipped: $src",
            src.contains("proposeWithdrawal: not generated"),
        )
    }

    @Test
    fun `facade emits asset-path constants — shorthand keeps the plain keys dir`() {
        // The shorthand (namespaced = false) is backward-compatible: KEYS_ASSET_DIR stays "keys".
        val src = render("vault", VOTING_ABI)
        assertTrue("alias const wrong: $src", src.contains("const val CONTRACT_ALIAS: String = \"vault\""))
        assertTrue("runtime const wrong: $src", src.contains("const val RUNTIME_ASSET: String = \"runtime/vault-contract.js\""))
        assertTrue("shorthand keys dir must stay 'keys': $src", src.contains("const val KEYS_ASSET_DIR: String = \"keys\""))
    }

    @Test
    fun `container entry namespaces its keys dir per-alias`() {
        // A contracts{} container entry (namespaced = true) keys under <alias>-keys so contracts
        // sharing a circuit name don't collide in one assets/keys/.
        val src = ContractApiGenerator.generate("privateVault", VOTING_ABI, namespaced = true).toString()
        assertTrue("container keys dir must be per-alias: $src", src.contains("const val KEYS_ASSET_DIR: String = \"privateVault-keys\""))
        assertTrue("runtime const wrong: $src", src.contains("const val RUNTIME_ASSET: String = \"runtime/privateVault-contract.js\""))
    }

    @Test
    fun `value-returning circuits get a typed read method decoding the ABI result-type`() {
        // Emit-both: a value-returning circuit keeps its call() (receipt) AND gains a read<Name>()
        // that delegates to a reusable decode<Name>Result(json) decoding the result-type. Unit-
        // returning circuits get NO read method / no result decoder.
        val src = render("sample", SCALAR_RETURNS_ABI)

        // read<Name>() delegates to a reusable decode<Name>Result(json) so a batched host read path
        // can decode identically. Uint result -> BigInteger via JSONTokener scalar.
        assertTrue(
            "Uint read method must delegate: $src",
            src.contains("readGetCount(): BigInteger = decodeGetCountResult(handle.read(\"getCount\"))"),
        )
        assertTrue(
            "Uint result decoder wrong: $src",
            src.contains("internal fun decodeGetCountResult(json: String): BigInteger = BigInteger(JSONTokener(json).nextValue().toString())"),
        )
        // Boolean result -> toBooleanStrict.
        assertTrue(
            "Boolean read method must delegate: $src",
            src.contains("readIsReady(): Boolean = decodeIsReadyResult(handle.read(\"isReady\"))"),
        )
        assertTrue(
            "Boolean result decoder wrong: $src",
            src.contains("internal fun decodeIsReadyResult(json: String): Boolean = JSONTokener(json).nextValue().toString().toBooleanStrict()"),
        )
        // Enum result -> typed enum via ordinal.
        assertTrue(
            "Enum read method must delegate: $src",
            src.contains("readGetPhase(): Phase = decodeGetPhaseResult(handle.read(\"getPhase\"))"),
        )
        assertTrue(
            "Enum result decoder wrong: $src",
            src.contains("internal fun decodeGetPhaseResult(json: String): Phase = Phase.entries[JSONTokener(json).nextValue().toString().toInt()]"),
        )
        // The call() method is still emitted for every circuit (the tx path).
        assertTrue("call method must remain: $src", src.contains("suspend fun getCount("))

        // Unit-returning circuit: call() only, no read method / no result decoder (nothing to read).
        assertTrue("Unit circuit must keep call(): $src", src.contains("suspend fun doThing("))
        assertFalse("Unit circuit must NOT get a read method: $src", src.contains("readDoThing"))
        assertFalse("Unit circuit must NOT get a result decoder: $src", src.contains("decodeDoThingResult"))

        // Struct return: a typed read method + a decode<Struct>(JSONObject) function.
        assertTrue("Struct-return read method wrong: $src", src.contains("suspend fun readGetRecord(): Record"))
        assertTrue("struct decoder must be generated: $src", src.contains("internal fun decodeRecord(o: JSONObject): Record"))
    }

    @Test
    fun `struct return decodes nested struct, enum, bytes and uint fields`() {
        // The real Vault getProposal shape: Proposal{ to: Recipient{ kind: enum, address: Bytes<32> },
        // color: Bytes<32>, amount: Uint<128>, status: enum }. Exercises every field-decode branch.
        val src = render("vault", PROPOSAL_RETURN_ABI)

        assertTrue(
            "read method must delegate to the result decoder: $src",
            src.contains("readGetProposal(id: BigInteger): Proposal = decodeGetProposalResult(handle.read(\"getProposal\", id))"),
        )
        assertTrue(
            "top-level struct result decoder wrong: $src",
            src.contains("internal fun decodeGetProposalResult(json: String): Proposal = decodeProposal(JSONObject(json))"),
        )
        // Proposal decoder: nested struct via decodeRecipient(getJSONObject), Bytes via decodeBytes,
        // Uint via BigInteger, enum via ordinal.
        assertTrue("nested struct decode wrong: $src", src.contains("""to = decodeRecipient(o.getJSONObject("to"))"""))
        assertTrue("bytes field decode wrong: $src", src.contains("""color = decodeBytes(o.get("color"))"""))
        assertTrue("uint field decode wrong: $src", src.contains("""amount = BigInteger(o.get("amount").toString())"""))
        assertTrue(
            "enum field decode wrong: $src",
            src.contains("""status = ProposalStatus.entries[o.get("status").toString().toInt()]"""),
        )
        // Nested Recipient decoder + the shared decodeBytes helper are emitted.
        assertTrue("nested decoder missing: $src", src.contains("internal fun decodeRecipient(o: JSONObject): Recipient"))
        assertTrue("decodeBytes helper missing: $src", src.contains("internal fun decodeBytes(v: Any?): ByteArray"))
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
        // The real Vault proposeWithdrawal shape (verbatim structure from the Vault
        // contract-info.json): a struct arg whose first field is a C-style enum.
        val STRUCT_WITH_ENUM_FIELD_ABI = """
        {
          "compiler-version": "0.31.0",
          "language-version": "0.23.0",
          "runtime-version": "0.16.0",
          "circuits": [
            {
              "name": "proposeWithdrawal",
              "pure": false,
              "proof": true,
              "arguments": [
                {
                  "name": "to",
                  "type": {
                    "type-name": "Struct",
                    "name": "Recipient",
                    "elements": [
                      {
                        "name": "kind",
                        "type": {
                          "type-name": "Enum",
                          "name": "RecipientKind",
                          "elements": ["ShieldedUser", "UnshieldedUser", "Contract"]
                        }
                      },
                      { "name": "address", "type": { "type-name": "Bytes", "length": 32 } }
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

        // The real Vault getProposal return: a struct with a nested struct, two Bytes fields, a
        // Uint<128>, and an enum — exercises every struct-field decode branch.
        val PROPOSAL_RETURN_ABI = """
        {
          "compiler-version": "0.31.0",
          "language-version": "0.23.0",
          "runtime-version": "0.16.0",
          "circuits": [
            {
              "name": "getProposal",
              "pure": false,
              "proof": true,
              "arguments": [ { "name": "id", "type": { "type-name": "Uint", "maxval": 255 } } ],
              "result-type": {
                "type-name": "Struct",
                "name": "Proposal",
                "elements": [
                  {
                    "name": "to",
                    "type": {
                      "type-name": "Struct",
                      "name": "Recipient",
                      "elements": [
                        { "name": "kind", "type": { "type-name": "Enum", "name": "RecipientKind",
                          "elements": ["ShieldedUser", "UnshieldedUser", "Contract"] } },
                        { "name": "address", "type": { "type-name": "Bytes", "length": 32 } }
                      ]
                    }
                  },
                  { "name": "color", "type": { "type-name": "Bytes", "length": 32 } },
                  { "name": "amount", "type": { "type-name": "Uint", "maxval": 340282366920938463463374607431768211455 } },
                  { "name": "status", "type": { "type-name": "Enum", "name": "ProposalStatus",
                    "elements": ["Inactive", "Active", "Executed", "Cancelled"] } }
                ]
              }
            }
          ],
          "witnesses": [],
          "contracts": [],
          "ledger": []
        }
        """.trimIndent()

        // Mixed result-types to exercise the typed read (view) path: Uint / Boolean / Enum reads,
        // a Unit-returning circuit (no read method), and a Struct return (deferred — no read yet).
        val SCALAR_RETURNS_ABI = """
        {
          "compiler-version": "0.31.0",
          "language-version": "0.23.0",
          "runtime-version": "0.16.0",
          "circuits": [
            { "name": "getCount", "pure": false, "proof": true, "arguments": [],
              "result-type": { "type-name": "Uint", "maxval": 255 } },
            { "name": "isReady", "pure": false, "proof": true, "arguments": [],
              "result-type": { "type-name": "Boolean" } },
            { "name": "getPhase", "pure": false, "proof": true, "arguments": [],
              "result-type": { "type-name": "Enum", "name": "Phase", "elements": ["A", "B", "C"] } },
            { "name": "doThing", "pure": false, "proof": true, "arguments": [],
              "result-type": { "type-name": "Tuple", "types": [] } },
            { "name": "getRecord", "pure": false, "proof": true, "arguments": [],
              "result-type": { "type-name": "Struct", "name": "Record",
                "elements": [ { "name": "n", "type": { "type-name": "Uint", "maxval": 255 } } ] } }
          ],
          "witnesses": [],
          "contracts": [],
          "ledger": []
        }
        """.trimIndent()

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
