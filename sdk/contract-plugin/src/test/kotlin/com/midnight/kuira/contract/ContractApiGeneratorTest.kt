package com.midnight.kuira.contract

import java.math.BigInteger
import org.junit.Assert.assertEquals
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
            "castVote must delegate to handle.call with a range-guarded optionIdx: $src",
            src.contains("""handle.call("castVote",""") &&
                src.contains("""requireUintInRange(optionIdx, BigInteger("18446744073709551615"))"""),
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
            "withdraw must marshal the struct arg via toCallArg() and range-guard amount: $src",
            src.contains("""handle.call("withdraw",""") &&
                src.contains("""requireUintInRange(amount, BigInteger("340282366920938463463374607431768211455"))""") &&
                src.contains("recipient.toCallArg()"),
        )
        // A Uint arg is range-guarded, not struct-marshalled (no toCallArg on a scalar).
        assertFalse(
            "scalar arg must NOT be struct-marshalled: $src",
            src.contains("amount.toCallArg()"),
        )

        // The Struct must materialise as a generated data class with its typed field.
        assertTrue(
            "UserAddress data class missing: $src",
            Regex("""data class UserAddress\([\s\S]*?val bytes: ByteArray""").containsMatchIn(src),
        )

        // …and a marshalling extension that builds the JS-object Map keyed by the
        // ABI field name. This is the form ArgConverter turns into `{ bytes:... }`.
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

        // The Vector<scalar> sibling: signature is List<BigInteger>, and each Uint element is
        // range-guarded element-wise (a shape-preserving map — the guard composes to any depth).
        assertTrue(
            "vector-arg circuit signature wrong: $src",
            Regex("""fun setShoots\(\s*shoots: List<(java\.math\.)?BigInteger>""").containsMatchIn(src),
        )
        assertTrue(
            "vector-of-scalar Uint elements must be range-guarded element-wise: $src",
            src.contains("""handle.call("setShoots", shoots.map { requireUintInRange(it, BigInteger("255")) }"""),
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
        // A container entry generates into a per-alias SUB-PACKAGE so two contracts that share
        // circuit/struct names don't emit colliding top-level decode.../toCallArg functions.
        assertTrue(
            "container entry must use a per-alias package: $src",
            src.contains("package com.midnight.kuira.contract.generated.privatevault"),
        )
    }

    @Test
    fun `shorthand keeps the flat generated package`() {
        // Backward compat: a single-contract shorthand (namespaced = false) stays in the flat
        // generated package so existing consumers' imports don't break.
        val src = render("vault", VOTING_ABI)
        assertTrue("shorthand must stay in the flat package: $src", src.contains("package com.midnight.kuira.contract.generated\n"))
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
    fun `struct ARG with an un-decodable field generates a call but no read-decoder, no crash`() {
        // A struct used only as an ARGUMENT whose field can't be decoded (a non-string Opaque) is a
        // valid argument (toCallArg passes the Opaque through) but has no valid read-decoder. The
        // generator must still emit the call() method and the data class — and must NOT try to emit a
        // decode<Struct> (which would throw at generation time on the un-decodable field).
        val src = render("bag", STRUCT_ARG_UNDECODABLE_FIELD_ABI)

        assertTrue("circuit must be generated: $src", Regex("""fun setItems\(\s*bag: Bag""").containsMatchIn(src))
        assertTrue("struct data class must be emitted: $src", src.contains("data class Bag"))
        assertTrue("struct arg must marshal via toCallArg: $src", src.contains("setItems\", bag.toCallArg()"))
        // The struct isn't decodable (non-string Opaque field), so NO read-decoder is generated.
        assertFalse("un-decodable struct must NOT get a read-decoder: $src", src.contains("decodeBag"))
    }

    @Test
    fun `nested Vector of Enum argument is skipped, not emitted un-marshalled`() {
        // marshalExpression can only map a single-level Vector<Enum>; a Vector<Vector<Enum>> would
        // need a nested map, so the circuit must be GRACEFULLY SKIPPED rather than emit a bare
        // pass-through that reaches ArgConverter as raw enum objects (the latent crash class).
        val src = render("grid", NESTED_VECTOR_ENUM_ABI)

        assertFalse("nested-vector-of-enum circuit must NOT be generated: $src", src.contains("fun setGrid("))
        assertTrue("must be documented as skipped: $src", src.contains("setGrid: not generated"))
    }

    @Test
    fun `a pure circuit gets a local() readLocal view, not a call or on-chain read`() {
        // A `pure: true` circuit (proof: false) is computed locally against initialState() with no
        // deployed instance — the PrivateVault commitment circuits. It must emit ONLY
        // local<Name>() = handle.readLocal + decode; never call() (not a tx) nor read<Name>()
        // (handle.read needs deployed state). A non-pure sibling keeps read<Name>() + call().
        val src = render("commit", PURE_AND_ONCHAIN_ABI)

        assertTrue(
            "pure circuit must emit local<Name> via readLocal: $src",
            src.contains("localComputeHash(x: ByteArray): ByteArray = decodeComputeHashResult(handle.readLocal(\"computeHash\", x))"),
        )
        assertFalse("pure circuit must NOT get a read<Name>: $src", src.contains("readComputeHash"))
        assertFalse("pure circuit must NOT get a call(): $src", src.contains("handle.call(\"computeHash\""))

        // The non-pure sibling is unaffected: on-chain read<Name> + call().
        assertTrue("non-pure read<Name> missing: $src", src.contains("readGetCount(): BigInteger = decodeGetCountResult(handle.read(\"getCount\"))"))
        assertTrue("non-pure call() missing: $src", src.contains("suspend fun getCount("))
    }

    @Test
    fun `an empty struct is a plain class, not an illegal data class`() {
        // Kotlin rejects `data class Foo()` (needs >=1 primary-ctor param). A field-less struct must
        // emit a plain `class` so the generated file still compiles.
        val src = render("ping", EMPTY_STRUCT_ABI)

        assertTrue("empty struct must be a plain class: $src", src.contains("public class Empty"))
        assertFalse("empty struct must NOT be a data class: $src", src.contains("data class Empty"))
    }

    @Test
    fun `struct return decodes nested struct, enum, bytes and uint fields`() {
        // The real Vault getProposal shape: Proposal{ to: Recipient{ kind: enum, address: Bytes<32> },
        // color: Bytes<32>, amount: Uint<128>, status: enum }. Exercises every field-decode branch.
        val src = render("vault", PROPOSAL_RETURN_ABI)

        assertTrue(
            "read method must delegate to the result decoder with a range-guarded id: $src",
            src.contains("readGetProposal(id: BigInteger): Proposal = decodeGetProposalResult(") &&
                src.contains("""handle.read("getProposal", requireUintInRange(id, BigInteger("255")))"""),
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
    fun `each Uint arg is range-guarded by its own width, Field is not, and the helper is emitted once`() {
        //  class: a value that fits Uint<128> was silently decoded as u64. The typed API now
        // range-checks every bounded Uint at the boundary — with the CORRECT per-width bound.
        val src = render("widths", UINT_WIDTHS_ABI)

        // u64 vs u128 discrimination: each Uint guard carries ITS OWN maxval, not a shared one.
        assertTrue(
            "u64 arg must be guarded to 2^64-1: $src",
            src.contains("""requireUintInRange(small, BigInteger("18446744073709551615"))"""),
        )
        assertTrue(
            "u128 arg must be guarded to 2^128-1 (distinct bound): $src",
            src.contains("""requireUintInRange(big, BigInteger("340282366920938463463374607431768211455"))"""),
        )
        // A Field is a prime-field element with no fixed width — it must NOT be range-guarded.
        assertFalse("Field arg must NOT be range-guarded: $src", src.contains("requireUintInRange(f,"))

        // The guard composes through a struct: the Wrapper's Uint<8> field is checked inside toCallArg.
        assertTrue(
            "struct Uint field must be guarded inside toCallArg: $src",
            src.contains("""requireUintInRange(n, BigInteger("255"))"""),
        )

        // The shared helper is emitted EXACTLY once, and validates [0, max].
        assertEquals(
            "requireUintInRange helper must be emitted exactly once: $src",
            1,
            Regex("""internal fun requireUintInRange\(""").findAll(src).count(),
        )
        assertTrue(
            "helper must reject negative or over-max values: $src",
            src.contains("require(value.signum() >= 0 && value <= max)"),
        )
    }

    @Test
    fun `Vector and Tuple returns decode into List and positional tuple types (#48A)`() {
        val src = render("scores", VECTOR_TUPLE_RETURN_ABI)

        // Vector<Uint> -> List<BigInteger>, decoded element-wise off the JSON array.
        assertTrue("Vector<Uint> read signature wrong: $src", src.contains("readGetScores(): List<BigInteger>"))
        assertTrue(
            "Vector<Uint> decoder must map the JSON array element-wise: $src",
            src.contains("(JSONTokener(json).nextValue() as JSONArray).let { a0 -> List(a0.length()) { i0 -> BigInteger(a0.get(i0).toString()) } }"),
        )

        // Vector<Struct> -> List<Player>, each element via the struct's own decoder.
        assertTrue("Vector<Struct> read signature wrong: $src", src.contains("readGetPlayers(): List<Player>"))
        assertTrue(
            "Vector<Struct> element must decode via decodePlayer: $src",
            src.contains("List(a0.length()) { i0 -> decodePlayer(a0.get(i0) as JSONObject) }"),
        )
        assertTrue("nested struct decoder must be emitted: $src", src.contains("internal fun decodePlayer(o: JSONObject): Player"))

        // Tuple<Uint,Bytes> -> Tuple2, read positionally.
        assertTrue("Tuple read signature wrong: $src", src.contains("readGetPair(): Tuple2"))
        assertTrue(
            "Tuple must decode positionally into its data class: $src",
            src.contains("Tuple2(BigInteger(a0.get(0).toString()), decodeBytes(a0.get(1)))"),
        )

        // Two DISTINCT Tuple2 shapes must NOT collide onto one type.
        assertTrue("first Tuple2 shape must be emitted: $src", src.contains("data class Tuple2("))
        assertTrue("distinct second Tuple2 shape must get its own type: $src", src.contains("data class Tuple2_2("))
        assertTrue("second tuple return must use the disambiguated type: $src", src.contains("readGetFlagCount(): Tuple2_2"))
        assertTrue(
            "the two tuple types must differ in field types (no silent collapse): $src",
            src.contains("public val field1: ByteArray") && src.contains("public val field0: Boolean"),
        )
    }

    @Test
    fun `typed ledger accessors — Counter + Cell types, Map & Set gracefully skipped (#64)`() {
        val src = render("bank", LEDGER_ABI)

        // Facade gets a snapshot accessor + a reactive one over the typed ledger class.
        assertTrue("ledger() snapshot: $src", src.contains("suspend fun ledger(): BankLedger = BankLedger(handle.ledger())"))
        assertTrue("observeLedger() reactive: $src", src.contains("fun observeLedger(): Flow<BankLedger> = handle.observeLedger().map { BankLedger(it) }"))

        // Each supported storage type maps to the right MidnightLedger getter.
        assertTrue("Counter -> getUintBig: $src", src.contains("""get() = raw.getUintBig("nextId")"""))
        assertTrue("Cell<Uint> -> getUintBig: $src", src.contains("""get() = raw.getUintBig("threshold")"""))
        assertTrue("Cell<Boolean> -> getBoolean: $src", src.contains("""get() = raw.getBoolean("active")"""))
        assertTrue("Cell<Bytes<32>> -> getBytes(name,len): $src", src.contains("""get() = raw.getBytes("owner", 32)"""))
        assertTrue("Cell<Enum> -> entries[ordinal]: $src", src.contains("""get() = Phase.entries[raw.getUintBig("phase").toInt()]"""))
        assertTrue("Cell<Opaque string> -> getString: $src", src.contains("""get() = raw.getString("label")"""))
        // Vector elements decode robustly (not blind casts): Uint via BigInteger(it.toString()) so a
        // Long/Int shape is handled; Bytes via decodeBytes which accepts ByteArray OR a number List.
        assertTrue("Cell<Vector<Uint>> -> List<BigInteger>: $src", src.contains("""get() = (raw.getRaw("scores") as List<*>).map { BigInteger(it.toString()) }"""))
        assertTrue("Cell<Vector<Bytes>> -> List<ByteArray>: $src", src.contains("""get() = (raw.getRaw("roster") as List<*>).map { decodeBytes(it) }"""))
        assertTrue("Cell<Maybe<string>> -> getMaybeString (String?): $src", src.contains("""get() = raw.getMaybeString("note")"""))
        assertTrue("Maybe accessor is nullable String: $src", src.contains("public val note: String?"))

        // The enum used only by a ledger field is still emitted.
        assertTrue("ledger-only enum emitted: $src", src.contains("public enum class Phase"))

        // Map/Set can't be read without native ADT queries — skipped, and documented, never emitted broken.
        assertFalse("Map field must NOT get a typed accessor: $src", src.contains("public val balances"))
        assertFalse("Set field must NOT get a typed accessor: $src", src.contains("public val members"))
        assertTrue("skipped Map noted: $src", src.contains(" - balances (Map)"))
        assertTrue("skipped Set noted: $src", src.contains(" - members (Set)"))

        // A NON-exported field isn't in the contract's ledger() reader → no accessor and NOT in the
        // skip note (it's private internal state, not an unsupported type).
        assertFalse("non-exported field must NOT get an accessor: $src", src.contains("public val secret"))
        assertFalse("non-exported field must NOT be listed as skipped: $src", src.contains("secret ("))
    }

    @Test
    fun `conformance — the full type surface composes in one contract (interactions)`() {
        // One contract exercising every supported type combined the way real contracts do: a struct
        // reused as arg + return + tuple-element + vector-element, a Vector<Uint> arg, a Tuple<Uint,
        // Struct> return, an enum field, and a pure Bytes->Bytes circuit. The single recursive walker
        // must keep all of these consistent in ONE file.
        val src = render("bank", CONFORMANCE_ABI)

        // Struct arg marshalled; Vector<Uint> arg guarded element-wise.
        assertTrue("struct arg via toCallArg: $src", src.contains("""handle.call("openAccount", acct.toCallArg()"""))
        assertTrue(
            "Vector<Uint> arg guarded element-wise: $src",
            src.contains("""amounts.map { requireUintInRange(it, BigInteger("18446744073709551615")) }"""),
        )

        // Tuple<Uint, Struct> return: read positionally, the struct element via its own decoder.
        assertTrue("Tuple<Uint,Struct> read signature: $src", src.contains("readSettle(amounts: List<BigInteger>): Tuple2"))
        assertTrue(
            "Tuple element decodes the nested struct: $src",
            src.contains("Tuple2(BigInteger(a0.get(0).toString()), decodeAccount(a0.get(1) as JSONObject))"),
        )

        // Vector<Struct> return decodes each element via decodeAccount.
        assertTrue("Vector<Struct> read: $src", src.contains("readListAccounts(): List<Account>"))
        assertTrue("Vector<Struct> element decode: $src", src.contains("List(a0.length()) { i0 -> decodeAccount(a0.get(i0) as JSONObject) }"))

        // Pure circuit -> local() via readLocal; Bytes<32> both directions.
        assertTrue("pure Bytes->Bytes local(): $src", src.contains("""localDigest(x: ByteArray): ByteArray = decodeDigestResult(handle.readLocal("digest", x))"""))

        // The reused struct emits BOTH a marshaller (arg) and a decoder (return), consistently typed.
        assertTrue("struct data class with every field kind: $src",
            src.contains("data class Account(") && src.contains("public val balance: BigInteger") &&
                src.contains("public val id: ByteArray") && src.contains("public val active: Boolean") &&
                src.contains("public val tier: Tier"))
        assertTrue("enum class emitted: $src", src.contains("enum class Tier"))
        assertTrue("struct marshaller emitted: $src", src.contains("internal fun Account.toCallArg()"))
        assertTrue("struct decoder emitted: $src", src.contains("internal fun decodeAccount(o: JSONObject): Account"))
        assertTrue("tuple element type is the struct: $src", src.contains("public val field1: Account"))

        // Enum field marshals as CompactEnum(ordinal), decodes back via entries[].
        assertTrue("enum field marshals as CompactEnum: $src", src.contains("\"tier\" to CompactEnum(tier.ordinal)"))
        assertTrue("enum field decodes via entries: $src", src.contains("tier = Tier.entries[o.get(\"tier\").toString().toInt()]"))

        // Shared helpers each emitted exactly once.
        assertEquals("decodeBytes helper once: $src", 1, Regex("""internal fun decodeBytes\(""").findAll(src).count())
        assertEquals("requireUintInRange helper once: $src", 1, Regex("""internal fun requireUintInRange\(""").findAll(src).count())
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

        // A circuit mixing Uint widths (u64, u128, u8-in-a-struct) with an unbounded Field, to pin the
        // range-guard: each Uint gets ITS OWN maxval bound (u64 vs u128 discrimination), Field none.
        val UINT_WIDTHS_ABI = """
        {
          "compiler-version": "0.31.0",
          "language-version": "0.23.0",
          "runtime-version": "0.16.0",
          "circuits": [
            {
              "name": "widths",
              "pure": false,
              "proof": true,
              "arguments": [
                { "name": "small", "type": { "type-name": "Uint", "maxval": 18446744073709551615 } },
                { "name": "big", "type": { "type-name": "Uint", "maxval": 340282366920938463463374607431768211455 } },
                { "name": "f", "type": { "type-name": "Field" } },
                {
                  "name": "wrapped",
                  "type": {
                    "type-name": "Struct",
                    "name": "Wrapper",
                    "elements": [
                      { "name": "n", "type": { "type-name": "Uint", "maxval": 255 } }
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

        // Vector/Tuple RETURN types (#48A): a Vector<Uint> list, a Vector<Struct>, a Tuple<Uint,Bytes>,
        // and a SECOND distinct Tuple2<Boolean,Uint> — the two Tuple2 shapes must NOT collide onto one
        // generated type.
        val VECTOR_TUPLE_RETURN_ABI = """
        {
          "compiler-version": "0.31.0", "language-version": "0.23.0", "runtime-version": "0.16.0",
          "circuits": [
            { "name": "getScores", "pure": false, "proof": false, "arguments": [],
              "result-type": { "type-name": "Vector", "length": 3, "type": { "type-name": "Uint", "maxval": 255 } } },
            { "name": "getPlayers", "pure": false, "proof": false, "arguments": [],
              "result-type": { "type-name": "Vector", "length": 2, "type": {
                "type-name": "Struct", "name": "Player",
                "elements": [ { "name": "id", "type": { "type-name": "Uint", "maxval": 255 } } ] } } },
            { "name": "getPair", "pure": false, "proof": false, "arguments": [],
              "result-type": { "type-name": "Tuple", "types": [
                { "type-name": "Uint", "maxval": 18446744073709551615 },
                { "type-name": "Bytes", "length": 32 } ] } },
            { "name": "getFlagCount", "pure": false, "proof": false, "arguments": [],
              "result-type": { "type-name": "Tuple", "types": [
                { "type-name": "Boolean" },
                { "type-name": "Uint", "maxval": 255 } ] } }
          ],
          "witnesses": [], "contracts": [], "ledger": []
        }
        """.trimIndent()

        // Kitchen-sink: the full supported type surface in ONE contract, combined the way real
        // contracts do (a struct reused as arg AND return, a Vector<Struct>, a mixed Tuple return, an
        // enum field, a pure Bytes->Bytes circuit). Guards cross-type INTERACTIONS the per-feature
        // tests don't — one recursive walker must handle them all in a single file.
        val CONFORMANCE_ABI = """
        {
          "compiler-version": "0.31.0", "language-version": "0.23.0", "runtime-version": "0.16.0",
          "circuits": [
            { "name": "openAccount", "pure": false, "proof": true,
              "arguments": [ { "name": "acct", "type": {
                "type-name": "Struct", "name": "Account", "elements": [
                  { "name": "balance", "type": { "type-name": "Uint", "maxval": 340282366920938463463374607431768211455 } },
                  { "name": "id", "type": { "type-name": "Bytes", "length": 32 } },
                  { "name": "active", "type": { "type-name": "Boolean" } },
                  { "name": "tier", "type": { "type-name": "Enum", "name": "Tier", "elements": ["Bronze", "Silver", "Gold"] } } ] } } ],
              "result-type": { "type-name": "Tuple", "types": [] } },
            { "name": "settle", "pure": false, "proof": true,
              "arguments": [ { "name": "amounts", "type": {
                "type-name": "Vector", "length": 3, "type": { "type-name": "Uint", "maxval": 18446744073709551615 } } } ],
              "result-type": { "type-name": "Tuple", "types": [
                { "type-name": "Uint", "maxval": 18446744073709551615 },
                { "type-name": "Struct", "name": "Account", "elements": [
                  { "name": "balance", "type": { "type-name": "Uint", "maxval": 340282366920938463463374607431768211455 } },
                  { "name": "id", "type": { "type-name": "Bytes", "length": 32 } },
                  { "name": "active", "type": { "type-name": "Boolean" } },
                  { "name": "tier", "type": { "type-name": "Enum", "name": "Tier", "elements": ["Bronze", "Silver", "Gold"] } } ] } ] } },
            { "name": "listAccounts", "pure": false, "proof": false, "arguments": [],
              "result-type": { "type-name": "Vector", "length": 4, "type": {
                "type-name": "Struct", "name": "Account", "elements": [
                  { "name": "balance", "type": { "type-name": "Uint", "maxval": 340282366920938463463374607431768211455 } },
                  { "name": "id", "type": { "type-name": "Bytes", "length": 32 } },
                  { "name": "active", "type": { "type-name": "Boolean" } },
                  { "name": "tier", "type": { "type-name": "Enum", "name": "Tier", "elements": ["Bronze", "Silver", "Gold"] } } ] } } },
            { "name": "digest", "pure": true, "proof": false,
              "arguments": [ { "name": "x", "type": { "type-name": "Bytes", "length": 32 } } ],
              "result-type": { "type-name": "Bytes", "length": 32 } }
          ],
          "witnesses": [], "contracts": [], "ledger": []
        }
        """.trimIndent()

        // The full ledger-section storage surface (#64 Phase 1): Counter + every supported Cell type,
        // plus a Map and a Set that must be gracefully SKIPPED (Phase 2 needs native ADT queries).
        val LEDGER_ABI = """
        {
          "compiler-version": "0.31.0", "language-version": "0.23.0", "runtime-version": "0.16.0",
          "circuits": [
            { "name": "touch", "pure": false, "proof": true, "arguments": [],
              "result-type": { "type-name": "Tuple", "types": [] } }
          ],
          "witnesses": [], "contracts": [],
          "ledger": [
            { "name": "nextId", "index": 0, "exported": true, "storage": "Counter" },
            { "name": "threshold", "index": 1, "exported": true, "storage": "Cell", "type": { "type-name": "Uint", "maxval": 255 } },
            { "name": "active", "index": 2, "exported": true, "storage": "Cell", "type": { "type-name": "Boolean" } },
            { "name": "owner", "index": 3, "exported": true, "storage": "Cell", "type": { "type-name": "Bytes", "length": 32 } },
            { "name": "phase", "index": 4, "exported": true, "storage": "Cell", "type": { "type-name": "Enum", "name": "Phase", "elements": ["A", "B"] } },
            { "name": "label", "index": 5, "exported": true, "storage": "Cell", "type": { "type-name": "Opaque", "tsType": "string" } },
            { "name": "scores", "index": 6, "exported": true, "storage": "Cell", "type": { "type-name": "Vector", "length": 3, "type": { "type-name": "Uint", "maxval": 255 } } },
            { "name": "roster", "index": 7, "exported": true, "storage": "Cell", "type": { "type-name": "Vector", "length": 2, "type": { "type-name": "Bytes", "length": 32 } } },
            { "name": "note", "index": 8, "exported": true, "storage": "Cell", "type": { "type-name": "Struct", "name": "Maybe", "elements": [ { "name": "is_some", "type": { "type-name": "Boolean" } }, { "name": "value", "type": { "type-name": "Opaque", "tsType": "string" } } ] } },
            { "name": "balances", "index": 9, "exported": true, "storage": "Map", "key": { "type-name": "Bytes", "length": 32 }, "value": { "type-name": "Uint", "maxval": 255 } },
            { "name": "members", "index": 10, "exported": true, "storage": "Set", "type": { "type-name": "Bytes", "length": 32 } },
            { "name": "secret", "index": 11, "exported": false, "storage": "Cell", "type": { "type-name": "Uint", "maxval": 255 } }
          ]
        }
        """.trimIndent()

        // Synthetic: Enum + Vector circuit ARGS (drawn from the penalty Phase enum
        // + a Vector<Uint> shoot list) to cover the recursive mapper's branches.
        // A pure circuit (computed via readLocal, no deploy) + a non-pure on-chain read sibling.
        val PURE_AND_ONCHAIN_ABI = """
        {
          "compiler-version": "0.31.0", "language-version": "0.23.0", "runtime-version": "0.16.0",
          "circuits": [
            { "name": "computeHash", "pure": true, "proof": false,
              "arguments": [ { "name": "x", "type": { "type-name": "Bytes", "length": 32 } } ],
              "result-type": { "type-name": "Bytes", "length": 32 } },
            { "name": "getCount", "pure": false, "proof": true, "arguments": [],
              "result-type": { "type-name": "Uint", "maxval": 255 } }
          ],
          "witnesses": [], "contracts": [], "ledger": []
        }
        """.trimIndent()

        // A struct ARG whose field is a Vector — marshallable as an arg (Vector<scalar> passes
        // through toCallArg) but NOT decodable, so no read-decoder should be generated for it.
        // A struct used only as an ARG whose field can't be decoded — a non-string Opaque (marshals as
        // a passthrough, but has no read-decoder). Guards that such a struct still emits call() + the
        // data class without trying (and crashing) to emit a decode<Struct>.
        val STRUCT_ARG_UNDECODABLE_FIELD_ABI = """
        {
          "compiler-version": "0.31.0", "language-version": "0.23.0", "runtime-version": "0.16.0",
          "circuits": [
            { "name": "setItems", "pure": false, "proof": true,
              "arguments": [ { "name": "bag", "type": {
                "type-name": "Struct", "name": "Bag",
                "elements": [ { "name": "blob", "type": { "type-name": "Opaque", "tsType": "Uint8Array" } } ] } } ],
              "result-type": { "type-name": "Tuple", "types": [] } }
          ],
          "witnesses": [], "contracts": [], "ledger": []
        }
        """.trimIndent()

        // A Vector<Vector<Enum>> arg — the single-level element map can't marshal it, so the circuit
        // must be skipped rather than emitted as a bare (un-marshalled) pass-through.
        val NESTED_VECTOR_ENUM_ABI = """
        {
          "compiler-version": "0.31.0", "language-version": "0.23.0", "runtime-version": "0.16.0",
          "circuits": [
            { "name": "setGrid", "pure": false, "proof": true,
              "arguments": [ { "name": "grid", "type": {
                "type-name": "Vector", "length": 2, "type": {
                  "type-name": "Vector", "length": 2, "type": {
                    "type-name": "Enum", "name": "Phase", "elements": ["A", "B"] } } } } ],
              "result-type": { "type-name": "Tuple", "types": [] } }
          ],
          "witnesses": [], "contracts": [], "ledger": []
        }
        """.trimIndent()

        // A field-less struct — must generate a plain class, not an (illegal) data class.
        val EMPTY_STRUCT_ABI = """
        {
          "compiler-version": "0.31.0", "language-version": "0.23.0", "runtime-version": "0.16.0",
          "circuits": [
            { "name": "ping", "pure": false, "proof": true,
              "arguments": [ { "name": "e", "type": {
                "type-name": "Struct", "name": "Empty", "elements": [] } } ],
              "result-type": { "type-name": "Tuple", "types": [] } }
          ],
          "witnesses": [], "contracts": [], "ledger": []
        }
        """.trimIndent()

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
