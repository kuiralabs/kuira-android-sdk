package com.midnight.kuira.contract

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT

/**
 * Build-time codegen of a typed Kotlin facade per Compact contract, derived
 * from the compiler's `contract-info.json` ABI.
 *
 * The generated class is a THIN delegate over [MidnightContract.call] — it does
 * not reimplement the runtime pipeline (state fetch -> execute -> prove ->
 * balance -> submit). It only gives a typed surface so a wrong-typed circuit
 * argument is a compile error instead of a runtime `ArgConverter` surprise.
 *
 * For the tifosi `voting` contract this emits:
 *
 * ```kotlin
 * class VotingContract(private val handle: MidnightContract) {
 *     suspend fun createPoll(question: String, options: String, onProgress: ...) =
 *         handle.call("createPoll", question, options, onProgress = onProgress)
 *     suspend fun castVote(optionIdx: java.math.BigInteger, onProgress: ...) =
 *         handle.call("castVote", optionIdx, onProgress = onProgress)
 *     suspend fun closePoll(onProgress: ...) =
 *         handle.call("closePoll", onProgress = onProgress)
 * }
 * ```
 *
 * Structs referenced by circuit arguments are emitted as generated `data class`
 * declarations in the same file, so the typed surface is self-contained. Each
 * generated struct also gets an `internal fun <Struct>.toCallArg(): Map<String, Any?>`
 * extension that marshals it into the [ArgConverter]-safe shape the runtime
 * expects: a JS object keyed by the ABI field names. The facade passes the
 * MARSHALLED form into `handle.call`, never the bare data class — passing the
 * data class itself would compile but throw `IllegalArgumentException` from
 * `ArgConverter.toJsExpression` at the first call (the latent crash this codegen
 * exists to prevent). A `Vector<Struct>` arg marshals as `arg.map { it.toCallArg() }`;
 * scalar / bytes / string / boolean / `Vector<scalar>` args are already
 * `ArgConverter`-safe and pass through unchanged.
 *
 * A Compact `Enum` argument (C-style: variant names, no payload — Maybe/Either are
 * Structs, not Enums) is emitted as a generated Kotlin `enum class` and marshalled
 * as its ordinal via `CompactEnum(value.ordinal)`, the shape the runtime
 * [ArgConverter] expects; this works nested inside a Struct field or a `Vector<Enum>`.
 *
 * A non-empty `Tuple` argument type still has no verified wire encoding for the typed
 * path (no in-repo contract exercises it as an arg). A circuit whose argument
 * type-tree contains one is GRACEFULLY SKIPPED — instead of a typed method that would
 * throw, the facade emits a comment pointing the caller at the raw
 * [MidnightContract.call]. The contract's other circuits still get typed methods. Such
 * tuples are therefore NOT emitted as supporting types.
 *
 * Constructors are NOT in the ABI, so no typed `deploy` is generated — deploy
 * stays on the raw [MidnightContract.deploy].
 *
 * Value-returning circuits ALSO get a typed VIEW method `read<Name>(args): <decoded>` alongside
 * their `call()` (which submits a tx and returns a receipt). The read method runs
 * [MidnightContract.read] — which executes the circuit locally against fetched state and returns
 * JSON — and decodes the ABI `result-type`. The ABI can't say which circuits are read-only (a
 * mutation like `proposeWithdrawal` also returns a value), so BOTH are emitted and the caller picks:
 * `read<Name>()` for a local view, `<name>()` to submit. Only scalar result-types (Uint/Field/
 * Boolean/Enum/string Opaque) decode today; Bytes/Struct/Vector/Tuple returns are a follow-up.
 *
 * Pure (no Gradle types) so it is unit-testable directly: feed an ABI string,
 * assert on the produced source.
 */
internal object ContractApiGenerator {

    /** Package the generated facade + supporting types live in. */
    const val GENERATED_PACKAGE = "com.midnight.kuira.contract.generated"

    private val MIDNIGHT_CONTRACT = ClassName("com.midnight.kuira.core.compact", "MidnightContract")
    private val TRANSACTION_RECEIPT = ClassName("com.midnight.kuira.core.compact", "TransactionReceipt")
    private val CONTRACT_CALL_STAGE = ClassName("com.midnight.kuira.core.compact", "ContractCallStage")

    /** Runtime wrapper the ArgConverter recognizes for a Compact enum: `CompactEnum(ordinal)`. */
    private val COMPACT_ENUM = ClassName("com.midnight.kuira.core.compact", "CompactEnum")

    /** `org.json.JSONTokener` — parses the bare-scalar JSON `MidnightContract.read` returns. */
    private val JSON_TOKENER = ClassName("org.json", "JSONTokener")

    /** `org.json.JSONObject` — a Struct result decodes from one of these. */
    private val JSON_OBJECT = ClassName("org.json", "JSONObject")

    /** `org.json.JSONArray` — a struct-nested Bytes field arrives as one (a JSON array of bytes). */
    private val JSON_ARRAY = ClassName("org.json", "JSONArray")

    /** Name of the generated Bytes decoder helper: `fun decodeBytes(Any?): ByteArray`. */
    const val DECODE_BYTES = "decodeBytes"

    /**
     * The generated symbols a facade references (`toCallArg`, `decode<Struct>`, `decode<Circuit>Result`,
     * `decodeBytes`) all live in the SAME generated file, so they're referenced by SIMPLE NAME (no
     * MemberName / import). That's what lets a container entry use a per-alias package ([packageFor])
     * without threading the package through every reference.
     *
     * `decode<Struct>(o: JSONObject): <Struct>` — the per-struct read decoder.
     */
    private fun structDecoderName(structName: String) = "decode$structName"

    /**
     * `decode<Circuit>Result(json: String): <ReturnType>` — the per-circuit result decoder. Reusable so
     * a batched read path (a host running its own `readMany`) decodes each result JSON with the SAME
     * typed decoder the `read<Name>()` method uses — no hand-written parsing.
     */
    private fun resultDecoderName(circuit: String) = "decode${circuit.replaceFirstChar { it.uppercaseChar() }}Result"

    private val BIG_INTEGER = ClassName("java.math", "BigInteger")
    private val LIST = ClassName("kotlin.collections", "List")

    /** Name of the generated struct marshalling extension: `fun X.toCallArg()`. */
    const val TO_CALL_ARG = "toCallArg"

    /**
     * Package the generated facade + supporting symbols go in. A `contracts { }` container entry
     * ([namespaced]) gets a per-alias sub-package so two contracts that share circuit / struct names
     * don't emit colliding top-level `decode…` / `toCallArg` functions into one package; the shorthand
     * keeps the flat [GENERATED_PACKAGE] (existing consumers import from there).
     */
    private fun packageFor(alias: String, namespaced: Boolean): String =
        if (namespaced) "$GENERATED_PACKAGE.${alias.lowercase().filter(Char::isLetterOrDigit)}" else GENERATED_PACKAGE

    /**
     * The `onProgress` parameter type, matching [MidnightContract.call]:
     * `(suspend (ContractCallStage) -> Unit)?`.
     */
    private val ON_PROGRESS_TYPE: TypeName =
        LambdaTypeName.get(
            parameters = listOf(ParameterSpec.unnamed(CONTRACT_CALL_STAGE)),
            returnType = UNIT,
        )
            .copy(suspending = true)
            .copy(nullable = true)

    /**
     * Generate the [FileSpec] for one contract.
     *
     * @param alias the managed-dir name (e.g. `voting`) — drives the class name.
     * @param contractInfoJson the raw `contract-info.json` text.
     * @param namespaced true for a `contracts { }` container entry — its circuit keys sync to a
     *   per-alias asset dir (`<alias>-keys`), reflected in the generated `KEYS_ASSET_DIR` constant.
     */
    fun generate(alias: String, contractInfoJson: String, namespaced: Boolean = false): FileSpec {
        val root = JsonValue.parse(contractInfoJson) as? JsonObject
            ?: error("contract-info.json is not a JSON object")
        val circuits = (root["circuits"] as? JsonArray)?.items.orEmpty()

        val pkg = packageFor(alias, namespaced)

        // Collect generated supporting types (Structs -> data class, Enums ->
        // enum class), de-duplicated by name across all circuit arguments.
        val supporting = SupportingTypes(pkg)

        val className = facadeClassName(alias)
        val facade = TypeSpec.classBuilder(className)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("handle", MIDNIGHT_CONTRACT)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("handle", MIDNIGHT_CONTRACT, KModifier.PRIVATE)
                    .initializer("handle")
                    .build(),
            )
            // Asset-path constants: ONE source of truth for where the plugin syncs this contract's
            // runtime JS + circuit keys, so the app loads them via these instead of magic strings.
            .addType(assetConstants(alias, namespaced))

        val skipNotices = mutableListOf<String>()
        for (circuit in circuits) {
            val obj = circuit as? JsonObject ?: continue
            when (val built = buildCircuitFun(obj, supporting)) {
                is CircuitFun.Generated -> {
                    facade.addFunction(built.spec)
                    // Emit-both: a value-returning circuit ALSO gets a typed view-path method
                    // (handle.read + decode) alongside its call() (which returns a receipt). The ABI
                    // can't say which circuits are read-only, so the caller picks: read<Name>() for a
                    // local view, <name>() to submit a tx. Only emitted when the result-type decodes.
                    buildReadFun(obj, supporting)?.let { facade.addFunction(it) }
                }
                is CircuitFun.Skipped -> skipNotices.add(built.notice)
            }
        }

        // Skipped circuits (Enum / non-empty Tuple args) are recorded as a comment
        // on the facade rather than as a crashing method — the caller invokes them
        // via MidnightContract.call(...) directly until their typed marshalling is
        // verified against a real contract.
        if (skipNotices.isNotEmpty()) {
            facade.addKdoc(
                "Circuits not generated by the typed API (call them via %T.call(...) directly):\n%L",
                MIDNIGHT_CONTRACT,
                skipNotices.joinToString("\n") { " - $it" },
            )
        }

        val fileBuilder = FileSpec.builder(pkg, className)
            .addFileComment(
                "Generated by the Kuira contract plugin from contract-info.json — do not edit.\n" +
                    "Typed facade over MidnightContract.call(...) for the \"%L\" contract.",
                alias,
            )
            .addType(facade.build())

        // Emit supporting types after the facade, in a stable (insertion) order.
        for (type in supporting.all()) {
            fileBuilder.addType(type)
        }

        // Then the struct marshalling extensions (`fun Struct.toCallArg()`), which
        // turn a generated data class into the ArgConverter-safe Map the runtime
        // expects. Without these the facade would pass a bare data class into
        // ArgConverter and throw at the first call.
        for (ext in supporting.callArgExtensions()) {
            fileBuilder.addFunction(ext)
        }

        // Per-circuit result decoders: decode<Circuit>Result(json) -> typed value. Reusable by a
        // host's own batched read path; read<Name>() delegates to them.
        for (decoder in supporting.resultDecoders()) {
            fileBuilder.addFunction(decoder)
        }

        // Read-decoders (the inverse of toCallArg): decode<Struct>(JSONObject) -> data class, plus
        // the shared decodeBytes helper when any decoded value contains a Bytes field.
        for (decoder in supporting.decoders()) {
            fileBuilder.addFunction(decoder)
        }
        if (supporting.usesBytesDecoder()) {
            fileBuilder.addFunction(decodeBytesHelper())
        }

        return fileBuilder.build()
    }

    /**
     * The shared `internal fun decodeBytes(v: Any?): ByteArray`. `read` emits a top-level Bytes as a
     * hex string but a struct-nested Bytes as a JSON array of byte values — accept both (and a raw
     * ByteArray, defensively).
     */
    private fun decodeBytesHelper(): FunSpec =
        FunSpec.builder(DECODE_BYTES)
            .addModifiers(KModifier.INTERNAL)
            .addParameter("v", ANY.copy(nullable = true))
            .returns(BYTE_ARRAY)
            .addCode(
                CodeBlock.builder()
                    .beginControlFlow("return when (v)")
                    .addStatement("is %T -> v", BYTE_ARRAY)
                    .addStatement("is %T -> v.chunked(2).map·{ it.toInt(16).toByte() }.toByteArray()", STRING)
                    .addStatement("is %T -> %T(v.length())·{ (v.getInt(it) and 0xFF).toByte() }", JSON_ARRAY, BYTE_ARRAY)
                    .addStatement("else -> error(%S)", "cannot decode Bytes from a non-string/array JSON value")
                    .endControlFlow()
                    .build(),
            )
            .build()

    /**
     * The `companion object` of asset-path constants for the facade. These mirror where
     * [KuiraContractPlugin] syncs the contract's artifacts, so a consumer wires
     * `MidnightContract.create { contractJs = assets.open(RUNTIME_ASSET) }` and
     * `ProvingKeyManager.installCircuitKeysFromAssets(KEYS_ASSET_DIR)` off ONE source that can't drift
     * from the sync. A container entry ([namespaced]) keys under `<alias>-keys`; the shorthand keeps
     * the plain `keys` dir.
     */
    private fun assetConstants(alias: String, namespaced: Boolean): TypeSpec {
        val keysDir = if (namespaced) "$alias-keys" else "keys"
        fun const(name: String, value: String, kdoc: String) =
            PropertySpec.builder(name, STRING, KModifier.CONST).addKdoc(kdoc).initializer("%S", value).build()
        return TypeSpec.companionObjectBuilder()
            .addProperty(const("CONTRACT_ALIAS", alias, "Managed-dir alias — the identity the plugin syncs this contract's assets under."))
            .addProperty(const("RUNTIME_ASSET", "runtime/$alias-contract.js", "APK asset path of the contract runtime JS (MidnightContract.create { contractJs })."))
            .addProperty(const("KEYS_ASSET_DIR", keysDir, "APK asset dir of this contract's circuit keys (ProvingKeyManager.installCircuitKeysFromAssets)."))
            .build()
    }

    /** `voting` -> `VotingContract`, `penalty` -> `PenaltyContract`. */
    private fun facadeClassName(alias: String): String =
        alias.split('-', '_', '.')
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } } + "Contract"

    /** Outcome of trying to generate a typed method for one circuit. */
    private sealed interface CircuitFun {
        /** A typed method was produced. */
        data class Generated(val spec: FunSpec) : CircuitFun

        /** No method was produced; [notice] explains why (recorded as a comment). */
        data class Skipped(val notice: String) : CircuitFun
    }

    private fun buildCircuitFun(circuit: JsonObject, supporting: SupportingTypes): CircuitFun {
        val name = (circuit["name"] as? JsonString)?.value
            ?: error("circuit missing name")
        val args = (circuit["arguments"] as? JsonArray)?.items.orEmpty()
            .map { it as? JsonObject ?: error("circuit '$name' has a non-object argument") }

        // Gate FIRST, before registering any supporting type: a circuit with an
        // Enum / non-empty-Tuple anywhere in an argument type-tree has no verified
        // typed marshalling, so we neither emit a method nor register its types.
        for (arg in args) {
            val argName = (arg["name"] as? JsonString)?.value ?: error("argument missing name")
            val argType = arg["type"] as? JsonObject ?: error("argument '$argName' missing type")
            val unmarshallable = firstUnmarshallable(argType)
            if (unmarshallable != null) {
                return CircuitFun.Skipped(
                    "$name: not generated — argument '$argName' of type $unmarshallable is not yet " +
                        "marshalled by the typed API; call it via MidnightContract.call(...) directly",
                )
            }
        }

        val fn = FunSpec.builder(name)
            .addModifiers(KModifier.SUSPEND)
            .returns(TRANSACTION_RECEIPT)

        // Delegated call: handle.call("name", <marshalled a>, <marshalled b>, onProgress = onProgress)
        val callArgs = StringBuilder("%S")
        val callFormatArgs = mutableListOf<Any>(name)

        for (arg in args) {
            val argName = (arg["name"] as? JsonString)?.value ?: error("argument missing name")
            val argType = arg["type"] as? JsonObject ?: error("argument '$argName' missing type")
            val kotlinType = mapType(argType, supporting)
            fn.addParameter(argName, kotlinType)
            // Marshal the typed param into an ArgConverter-safe form (struct ->
            // Map via toCallArg(), Vector<struct> -> List<Map>, scalars pass through).
            callArgs.append(", %L")
            callFormatArgs.add(marshalExpression(CodeBlock.of("%N", argName), argType))
        }

        val onProgress = ParameterSpec.builder("onProgress", ON_PROGRESS_TYPE)
            .defaultValue("null")
            .build()
        fn.addParameter(onProgress)
        callArgs.append(", onProgress = %N")
        callFormatArgs.add(onProgress)

        // A circuit result is not modelled as a typed return: call() always yields
        // a TransactionReceipt (an empty Tuple is the circuit's unit return today).
        fn.addStatement("return handle.call($callArgs)", *callFormatArgs.toTypedArray())
        return CircuitFun.Generated(fn.build())
    }

    /**
     * Build the typed VIEW method for a value-returning circuit: `read<Name>(args): <decoded>`
     * running `handle.read(...)` (which yields JSON) and decoding it per the ABI `result-type`.
     *
     * Returns null (no read method — the caller uses the `call()` method) when:
     *  - the result-type is Unit (empty Tuple) — nothing to read;
     *  - the result-type isn't a scalar the decoder handles yet (Struct/Vector/Bytes returns are a
     *    follow-up — they need a JSON-object/array decoder);
     *  - an argument isn't marshallable (same gate as the call method).
     */
    private fun buildReadFun(circuit: JsonObject, supporting: SupportingTypes): FunSpec? {
        val name = (circuit["name"] as? JsonString)?.value ?: error("circuit missing name")
        val resultType = (circuit["result-type"] as? JsonObject)?.let(::resolveAlias) ?: return null
        if (!isDecodable(resultType)) return null

        val args = (circuit["arguments"] as? JsonArray)?.items.orEmpty()
            .map { it as? JsonObject ?: error("circuit '$name' has a non-object argument") }
        // The read method takes and marshals the same args as the call method; if any is
        // unmarshallable the call method was skipped too, so skip the read for symmetry.
        for (arg in args) {
            val argType = arg["type"] as? JsonObject ?: error("argument missing type")
            if (firstUnmarshallable(argType) != null) return null
        }

        val returnType = mapType(resultType, supporting) // registers the enum class for an Enum result

        // The reusable result decoder: decode<Circuit>Result(json: String): <ReturnType>. Its `json`
        // parameter is the variable decodeTopLevel's expression references, so read<Name>() and any
        // batched host read path decode identically. Registered as a standalone file function.
        supporting.registerResultDecoder(
            FunSpec.builder(resultDecoderName(name))
                .addModifiers(KModifier.INTERNAL)
                .addParameter("json", STRING)
                .returns(returnType)
                .addStatement("return %L", decodeTopLevel(resultType, returnType, supporting))
                .build(),
        )

        val fn = FunSpec.builder(readMethodName(name))
            .addModifiers(KModifier.SUSPEND)
            .returns(returnType)

        val readArgs = StringBuilder("%S")
        val readFormat = mutableListOf<Any>(name)
        for (arg in args) {
            val argName = (arg["name"] as? JsonString)?.value ?: error("argument missing name")
            val argType = arg["type"] as? JsonObject ?: error("argument '$argName' missing type")
            fn.addParameter(argName, mapType(argType, supporting))
            readArgs.append(", %L")
            readFormat.add(marshalExpression(CodeBlock.of("%N", argName), argType))
        }

        // read<Name>() delegates to the reusable decoder: decode<Name>Result(handle.read(...)).
        val readCall = CodeBlock.of("handle.read($readArgs)", *readFormat.toTypedArray())
        fn.addStatement("return %L(%L)", resultDecoderName(name), readCall)
        return fn.build()
    }

    /**
     * Decode the whole read-result JSON string (the `json` variable in scope) into [returnType].
     *  - Struct -> `decode<Struct>(JSONObject(json))`
     *  - Bytes  -> `decodeBytes(JSONTokener(json).nextValue())` (top-level Bytes is a hex string)
     *  - scalar -> `decodeScalarValue(JSONTokener(json).nextValue())`
     */
    private fun decodeTopLevel(type: JsonObject, returnType: TypeName, supporting: SupportingTypes): CodeBlock {
        val resolved = resolveAlias(type)
        val name = (resolved["type-name"] as? JsonString)?.value ?: error("type node missing type-name")
        return when (name) {
            "Struct" -> {
                // Nested-field bytes flags are set while the struct decoder is registered (mapType,
                // above), via decodeFieldFromObject's Bytes branch — no extra scan needed here.
                val structName = (resolved["name"] as? JsonString)?.value ?: error("Struct missing name")
                CodeBlock.of("%L(%T(json))", structDecoderName(structName), JSON_OBJECT)
            }
            "Bytes" -> {
                supporting.markUsesBytesDecoderIf(true)
                CodeBlock.of("%L(%T(json).nextValue())", DECODE_BYTES, JSON_TOKENER)
            }
            else -> decodeScalarValue(CodeBlock.of("%T(json).nextValue()", JSON_TOKENER), resolved, returnType)
        }
    }

    /** `getThreshold` -> `readGetThreshold`. */
    private fun readMethodName(circuit: String): String =
        "read" + circuit.replaceFirstChar { it.uppercaseChar() }

    /**
     * Can a circuit result of type [type] be decoded from the JSON `read` returns?
     *  - Uint/Field/Boolean/Enum, string Opaque, Bytes -> yes (scalar / hex-or-array bytes);
     *  - Struct -> yes iff every field is itself decodable (recursively);
     *  - Vector, non-empty Tuple, non-string Opaque -> not yet (need an array/positional decoder).
     */
    private fun isDecodable(type: JsonObject): Boolean {
        val name = (type["type-name"] as? JsonString)?.value ?: error("type node missing type-name")
        return when (name) {
            "Uint", "Field", "Boolean", "Enum", "Bytes" -> true
            "Opaque" -> (type["tsType"] as? JsonString)?.value == "string"
            "Struct" -> {
                val elements = (type["elements"] as? JsonArray)?.items.orEmpty()
                elements.all { el ->
                    val elType = (el as JsonObject)["type"] as? JsonObject ?: error("struct element missing type")
                    isDecodable(elType)
                }
            }
            "Alias" -> isDecodable(resolveAlias(type))
            else -> false // Vector, Tuple, non-string Opaque
        }
    }

    /**
     * Decode a single struct field named [fieldName] from a `JSONObject` [objVar] in scope, per its
     * ABI [type]. Nested Struct -> its generated `decode<Struct>`; Bytes -> `decodeBytes`; scalar ->
     * [decodeScalarValue] over `objVar.get("field")`.
     */
    private fun decodeFieldFromObject(
        objVar: CodeBlock,
        fieldName: String,
        type: JsonObject,
        supporting: SupportingTypes,
    ): CodeBlock {
        val resolved = resolveAlias(type)
        val name = (resolved["type-name"] as? JsonString)?.value ?: error("type node missing type-name")
        return when (name) {
            "Struct" -> {
                val structName = (resolved["name"] as? JsonString)?.value ?: error("Struct missing name")
                CodeBlock.of("%L(%L.getJSONObject(%S))", structDecoderName(structName), objVar, fieldName)
            }
            "Bytes" -> {
                supporting.markUsesBytesDecoderIf(true)
                CodeBlock.of("%L(%L.get(%S))", DECODE_BYTES, objVar, fieldName)
            }
            else -> decodeScalarValue(
                CodeBlock.of("%L.get(%S)", objVar, fieldName),
                resolved,
                mapType(resolved, supporting),
            )
        }
    }

    /**
     * Decode a parsed JSON scalar [value] (the result of `JSONTokener(json).nextValue()`) into its
     * typed Kotlin value. All forms go through `.toString()` so an Integer/Long/String JSON encoding
     * of the same number decodes identically. [returnType] is the enum ClassName for an Enum result.
     */
    private fun decodeScalarValue(value: CodeBlock, type: JsonObject, returnType: TypeName): CodeBlock {
        val name = (type["type-name"] as? JsonString)?.value ?: error("type node missing type-name")
        return when (name) {
            "Uint", "Field" -> CodeBlock.of("%T(%L.toString())", BIG_INTEGER, value)
            "Boolean" -> CodeBlock.of("%L.toString().toBooleanStrict()", value)
            // A Compact enum is returned as its ordinal; map it back through the generated enum.
            "Enum" -> CodeBlock.of("%T.entries[%L.toString().toInt()]", returnType, value)
            "Opaque" -> CodeBlock.of("%L.toString()", value)
            "Alias" -> decodeScalarValue(value, resolveAlias(type), returnType)
            else -> error("decodeScalarValue called on non-scalar $name (guarded by isScalarDecodable)")
        }
    }

    /** Resolve through any chain of `Alias` nodes to the underlying (non-Alias) type. */
    private fun resolveAlias(type: JsonObject): JsonObject {
        var t = type
        while ((t["type-name"] as? JsonString)?.value == "Alias") {
            t = t["type"] as? JsonObject ?: error("Alias missing inner type")
        }
        return t
    }

    /**
     * Walk an argument type-tree; return the first ABI type-name that the typed
     * API cannot yet marshal (`Enum`, or a non-empty `Tuple`), or `null` if the
     * whole tree is marshallable. Vectors/Aliases/Structs recurse into their
     * inner types; scalars/bytes/strings are always marshallable.
     */
    private fun firstUnmarshallable(type: JsonObject): String? {
        val name = (type["type-name"] as? JsonString)?.value
            ?: error("type node missing type-name")
        return when (name) {
            "Uint", "Field", "Boolean", "Bytes", "Opaque" -> null
            // A Compact enum is a C-style enumeration (variant names, no payload); it marshals as its
            // ordinal via CompactEnum(ordinal). Algebraic types (Maybe/Either) are Structs, not Enums.
            "Enum" -> null
            "Tuple" -> {
                val types = (type["types"] as? JsonArray)?.items.orEmpty()
                if (types.isEmpty()) null else "Tuple"
            }
            "Vector" -> {
                val inner = type["type"] as? JsonObject ?: error("Vector missing inner type")
                // Resolve through any Alias FIRST: a Vector<Alias<Vector<Struct>>> otherwise reads as
                // innerName == "Alias", slips past this guard, and gets emitted as unmarshalled
                // pass-through — the exact latent ArgConverter crash the guard exists to prevent.
                val resolvedInner = resolveAlias(inner)
                val innerName = (resolvedInner["type-name"] as? JsonString)?.value
                    ?: error("type node missing type-name")
                // A Vector marshals element-wise only for the single-level case (Vector<Struct> ->
                // `map { it.toCallArg() }`, Vector<Enum> -> `map { CompactEnum(it.ordinal) }`). A
                // NESTED vector whose elements need marshalling (Struct or Enum) would require a
                // deeper map; that shape is unverified, so flag it rather than emit a bare pass-through
                // that reaches ArgConverter as raw data classes / enum objects. Vector<scalar> passes.
                if (innerName == "Vector" && firstUnmarshallable(resolvedInner) == null &&
                    containsMarshalledElement(resolvedInner)
                ) {
                    "Vector<Vector<marshalled>>"
                } else {
                    firstUnmarshallable(inner)
                }
            }
            "Alias" -> {
                val inner = type["type"] as? JsonObject ?: error("Alias missing inner type")
                firstUnmarshallable(inner)
            }
            "Struct" -> {
                val elements = (type["elements"] as? JsonArray)?.items.orEmpty()
                elements.firstNotNullOfOrNull { el ->
                    val elObj = el as JsonObject
                    val elType = elObj["type"] as? JsonObject ?: error("struct element missing type")
                    firstUnmarshallable(elType)
                }
            }
            else -> error("unsupported ABI type-name: $name")
        }
    }

    /**
     * Build the expression that converts the value [receiver] (whose ABI type is
     * [type]) into an [ArgConverter]-safe value. Used both for a circuit's typed
     * parameter (receiver = the param name) and for a struct field inside a
     * generated `toCallArg()` (receiver = the field property).
     *
     * Caller guarantees [type] is marshallable (see [firstUnmarshallable]).
     *  - Struct      -> `receiver.toCallArg()` (a `Map<String, Any?>`)
     *  - Vector<S>   -> `receiver.map { it.toCallArg() }` when S is a Struct; else `receiver`
     *  - scalar/etc. -> `receiver` (ArgConverter already accepts these)
     */
    private fun marshalExpression(receiver: CodeBlock, type: JsonObject): CodeBlock {
        val name = (type["type-name"] as? JsonString)?.value
            ?: error("type node missing type-name")
        return when (name) {
            "Struct" -> CodeBlock.of("%L.%L()", receiver, TO_CALL_ARG)
            // A Compact enum marshals as its ordinal, wrapped so the ArgConverter treats it as an
            // enum rather than a bare Uint: CompactEnum(value.ordinal).
            "Enum" -> CodeBlock.of("%T(%L.ordinal)", COMPACT_ENUM, receiver)
            "Vector" -> {
                val inner = type["type"] as? JsonObject ?: error("Vector missing inner type")
                if (innerNeedsMarshalling(inner)) {
                    // Marshal each element by its own rule (Struct -> toCallArg(), Enum -> CompactEnum).
                    CodeBlock.of("%L.map·{ %L }", receiver, marshalExpression(CodeBlock.of("it"), inner))
                } else {
                    receiver
                }
            }
            "Alias" -> {
                val inner = type["type"] as? JsonObject ?: error("Alias missing inner type")
                marshalExpression(receiver, inner)
            }
            // Scalars, Bytes, Opaque, Boolean, empty Tuple (Unit) are ArgConverter-safe.
            else -> receiver
        }
    }

    /**
     * Does a Vector's inner type require per-element marshalling? Only the
     * single-level `Vector<Struct>` case does (`map { it.toCallArg() }`).
     * Alias-transparent. Deeper nestings are rejected by [firstUnmarshallable],
     * so this need only see through an Alias to a Struct.
     */
    private fun innerNeedsMarshalling(inner: JsonObject): Boolean {
        val name = (inner["type-name"] as? JsonString)?.value
            ?: error("type node missing type-name")
        return when (name) {
            "Struct" -> true
            "Enum" -> true
            "Alias" -> {
                val deeper = inner["type"] as? JsonObject ?: error("Alias missing inner type")
                innerNeedsMarshalling(deeper)
            }
            else -> false
        }
    }

    /**
     * True if [type]'s tree contains an element that needs per-element marshalling — a Struct
     * (`toCallArg()`) or an Enum (`CompactEnum(ordinal)`) — anywhere (Vector/Alias-transparent). Used
     * to reject nested `Vector<Vector<…>>` of such elements, which the single-level `map { … }` can't
     * marshal.
     */
    private fun containsMarshalledElement(type: JsonObject): Boolean {
        val name = (type["type-name"] as? JsonString)?.value
            ?: error("type node missing type-name")
        return when (name) {
            "Struct", "Enum" -> true
            "Vector", "Alias" -> {
                val inner = type["type"] as? JsonObject ?: error("$name missing inner type")
                containsMarshalledElement(inner)
            }
            else -> false
        }
    }

    /**
     * Map one ABI type node to its Kotlin [TypeName], registering any Struct/Enum
     * it transitively references with [supporting].
     *
     * The closed ABI vocabulary (10 forms):
     *  - `Uint` / `Field`        -> BigInteger
     *  - `Boolean`               -> Boolean
     *  - `Bytes`                 -> ByteArray
     *  - `Vector`                -> List<inner>
     *  - `Tuple` (empty)         -> Unit ; (non-empty) -> generated data class
     *  - `Struct`                -> generated data class
     *  - `Enum`                  -> generated enum class
     *  - `Opaque` tsType=string  -> String ; else -> ByteArray (passthrough)
     *  - `Alias`                 -> transparent (its inner type)
     */
    private fun mapType(type: JsonObject, supporting: SupportingTypes): TypeName {
        val name = (type["type-name"] as? JsonString)?.value
            ?: error("type node missing type-name")
        return when (name) {
            "Uint", "Field" -> BIG_INTEGER
            "Boolean" -> BOOLEAN
            "Bytes" -> BYTE_ARRAY
            "Vector" -> {
                val inner = type["type"] as? JsonObject
                    ?: error("Vector missing inner type")
                LIST.parameterizedBy(mapType(inner, supporting))
            }
            "Tuple" -> {
                val types = (type["types"] as? JsonArray)?.items.orEmpty()
                if (types.isEmpty()) {
                    UNIT
                } else {
                    supporting.registerTuple(types.map { it as JsonObject }) { t -> mapType(t, supporting) }
                }
            }
            "Struct" -> registerStruct(type, supporting)
            "Enum" -> registerEnum(type, supporting)
            "Opaque" -> {
                val ts = (type["tsType"] as? JsonString)?.value
                if (ts == "string") STRING else BYTE_ARRAY
            }
            "Alias" -> {
                val inner = type["type"] as? JsonObject
                    ?: error("Alias missing inner type")
                mapType(inner, supporting)
            }
            else -> error("unsupported ABI type-name: $name")
        }
    }

    private fun registerStruct(type: JsonObject, supporting: SupportingTypes): TypeName {
        val structName = (type["name"] as? JsonString)?.value
            ?: error("Struct missing name")
        val elements = (type["elements"] as? JsonArray)?.items.orEmpty().map { it as JsonObject }
        // A read-decoder is generated ONLY for a struct every field of which decodes. An arg-only
        // struct with an un-decodable field (a Vector, a non-string Opaque, …) is still a valid
        // ARGUMENT (toCallArg passes those through), but has no valid decoder — and an un-decodable
        // struct can never appear in a RESULT position (the read gate rejects it), so no decoder is
        // ever referenced. Emitting one anyway would throw at generation time (decodeFieldFromObject
        // on a Vector field) or produce a non-compiling decoder. So gate it on decodability.
        val decodeField: ((String, JsonObject) -> CodeBlock)? =
            if (isDecodable(type)) {
                { fieldName, elemType ->
                    // Inside the generated `fun decode<Struct>(o: JSONObject)`, read each field off `o`.
                    decodeFieldFromObject(CodeBlock.of("o"), fieldName, elemType, supporting)
                }
            } else {
                null
            }
        return supporting.registerStruct(
            name = structName,
            elements = elements,
            mapElement = { elemType -> mapType(elemType, supporting) },
            marshalField = { fieldName, elemType ->
                // Inside the generated `fun Struct.toCallArg()`, the field is in
                // scope as a property — reference it bare and marshal recursively.
                marshalExpression(CodeBlock.of("%N", fieldName), elemType)
            },
            decodeField = decodeField,
        )
    }

    private fun registerEnum(type: JsonObject, supporting: SupportingTypes): TypeName {
        val enumName = (type["name"] as? JsonString)?.value
            ?: error("Enum missing name")
        val elements = (type["elements"] as? JsonArray)?.items.orEmpty()
            .map { (it as JsonString).value }
        return supporting.registerEnum(enumName, elements)
    }
}

/**
 * Accumulates generated supporting types (Structs -> data classes, Enums -> enum
 * classes, non-empty Tuples -> positional data classes), de-duplicated by name,
 * in insertion order. Returns a [ClassName] in the generated package for each.
 */
private class SupportingTypes(
    /** Package the generated data classes / enums live in (per-alias for a container entry). */
    private val pkg: String,
) {
    private val types = LinkedHashMap<String, TypeSpec>()

    /** `Struct.toCallArg()` extensions, keyed by struct name, in insertion order. */
    private val callArgFunctions = LinkedHashMap<String, FunSpec>()

    /** `decode<Struct>(o: JSONObject)` read-decoders, keyed by struct name, in insertion order. */
    private val decoderFunctions = LinkedHashMap<String, FunSpec>()

    /** `decode<Circuit>Result(json: String)` per-circuit result decoders, in registration order. */
    private val resultDecoderFunctions = LinkedHashMap<String, FunSpec>()

    /** Set when any generated decoder (or top-level read) needs the shared `decodeBytes` helper. */
    private var usesBytesDecoder = false

    fun registerStruct(
        name: String,
        elements: List<JsonObject>,
        mapElement: (JsonObject) -> TypeName,
        marshalField: (fieldName: String, fieldType: JsonObject) -> CodeBlock,
        /** Null when the struct isn't decodable (arg-only, un-decodable field) — no decoder emitted. */
        decodeField: ((fieldName: String, fieldType: JsonObject) -> CodeBlock)?,
    ): ClassName {
        if (!types.containsKey(name)) {
            // Reserve the slot before recursing so a self/mutually-referential
            // struct doesn't infinitely recurse (compactc nests but is acyclic
            // in practice; this is belt-and-braces).
            types[name] = PLACEHOLDER
            val ctor = FunSpec.constructorBuilder()
            // A field-less struct can't be a `data class` (Kotlin requires >=1 primary-ctor param);
            // emit a plain class so a degenerate empty struct still compiles.
            val builder = TypeSpec.classBuilder(name)
                .apply { if (elements.isNotEmpty()) addModifiers(KModifier.DATA) }

            // Body of the marshalling extension: mapOf("field" to <marshalled>, ...) and, when the
            // struct decodes, the read-decoder's constructor args: field = <decoded from JSONObject>.
            val mapEntries = mutableListOf<CodeBlock>()
            val decodeArgs = mutableListOf<CodeBlock>()
            for (element in elements) {
                val elemName = (element["name"] as? JsonString)?.value
                    ?: error("struct '$name' element missing name")
                val elemType = element["type"] as? JsonObject
                    ?: error("struct '$name' element '$elemName' missing type")
                val kt = mapElement(elemType)
                ctor.addParameter(elemName, kt)
                builder.addProperty(
                    PropertySpec.builder(elemName, kt).initializer(elemName).build(),
                )
                mapEntries.add(CodeBlock.of("%S to %L", elemName, marshalField(elemName, elemType)))
                if (decodeField != null) {
                    decodeArgs.add(CodeBlock.of("%N = %L", elemName, decodeField(elemName, elemType)))
                }
            }
            types[name] = builder.primaryConstructor(ctor.build()).build()

            // internal fun <Struct>.toCallArg(): Map<String, Any?> =
            //     mapOf("bytes" to bytes, ...)  — the ArgConverter-safe (JS object) shape.
            val structClass = ClassName(pkg, name)
            val body = if (mapEntries.isEmpty()) {
                CodeBlock.of("%M()", MAP_OF)
            } else {
                CodeBlock.builder()
                    .add("%M(\n", MAP_OF)
                    .indent()
                    .apply { mapEntries.forEachIndexed { i, e -> add("%L,\n", e) } }
                    .unindent()
                    .add(")")
                    .build()
            }
            callArgFunctions[name] = FunSpec.builder(ContractApiGenerator.TO_CALL_ARG)
                .addModifiers(KModifier.INTERNAL)
                .receiver(structClass)
                .returns(CALL_ARG_MAP_TYPE)
                .addStatement("return %L", body)
                .build()

            // internal fun decode<Struct>(o: JSONObject): <Struct> = <Struct>(field = <decoded>, ...)
            // The inverse of toCallArg — reads each field off the JSON `read` returns. Emitted ONLY
            // when the struct decodes (decodeField != null); an arg-only struct with an un-decodable
            // field gets no decoder (it can never appear in a result position anyway).
            if (decodeField != null) {
                val decodeBody = if (decodeArgs.isEmpty()) {
                    CodeBlock.of("%T()", structClass)
                } else {
                    CodeBlock.builder()
                        .add("%T(\n", structClass)
                        .indent()
                        .apply { decodeArgs.forEach { add("%L,\n", it) } }
                        .unindent()
                        .add(")")
                        .build()
                }
                decoderFunctions[name] = FunSpec.builder("decode$name")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("o", JSON_OBJECT_CN)
                    .returns(structClass)
                    .addStatement("return %L", decodeBody)
                    .build()
            }
        }
        return ClassName(pkg, name)
    }

    /** Flip the shared-`decodeBytes`-needed flag when [condition] holds. */
    fun markUsesBytesDecoderIf(condition: Boolean) {
        if (condition) usesBytesDecoder = true
    }

    fun usesBytesDecoder(): Boolean = usesBytesDecoder

    fun registerEnum(name: String, constants: List<String>): ClassName {
        if (!types.containsKey(name)) {
            val builder = TypeSpec.enumBuilder(name)
            for (c in constants) builder.addEnumConstant(c)
            types[name] = builder.build()
        }
        return ClassName(pkg, name)
    }

    fun registerTuple(
        elementTypes: List<JsonObject>,
        mapElement: (JsonObject) -> TypeName,
    ): ClassName {
        // Deterministic name from arity so identical-arity tuples collapse to one
        // type. compactc rarely emits non-empty tuple ARGS, but the vocabulary
        // allows them, so this stays exhaustive.
        val name = "Tuple${elementTypes.size}"
        if (!types.containsKey(name)) {
            types[name] = PLACEHOLDER
            val ctor = FunSpec.constructorBuilder()
            val builder = TypeSpec.classBuilder(name).addModifiers(KModifier.DATA)
            elementTypes.forEachIndexed { i, t ->
                val field = "field$i"
                val kt = mapElement(t)
                ctor.addParameter(field, kt)
                builder.addProperty(PropertySpec.builder(field, kt).initializer(field).build())
            }
            types[name] = builder.primaryConstructor(ctor.build()).build()
        }
        return ClassName(pkg, name)
    }

    fun all(): List<TypeSpec> = types.values.filter { it !== PLACEHOLDER }

    /** The generated `Struct.toCallArg()` extensions, in insertion order. */
    fun callArgExtensions(): List<FunSpec> = callArgFunctions.values.toList()

    /** The generated `decode<Struct>(o: JSONObject)` read-decoders, in insertion order. */
    fun decoders(): List<FunSpec> = decoderFunctions.values.toList()

    /** Register a per-circuit `decode<Circuit>Result(json)` decoder (idempotent by name). */
    fun registerResultDecoder(fn: FunSpec) {
        resultDecoderFunctions.putIfAbsent(fn.name, fn)
    }

    /** The generated per-circuit `decode<Circuit>Result(json)` decoders, in registration order. */
    fun resultDecoders(): List<FunSpec> = resultDecoderFunctions.values.toList()

    companion object {
        // Sentinel held while a struct is being built so a cyclic reference is a
        // no-op re-entry rather than infinite recursion.
        private val PLACEHOLDER: TypeSpec = TypeSpec.classBuilder("__placeholder__").build()

        private val MAP_OF = MemberName("kotlin.collections", "mapOf")
        private val CALL_ARG_MAP_TYPE: TypeName =
            MAP.parameterizedBy(STRING, ANY.copy(nullable = true))
        private val JSON_OBJECT_CN = ClassName("org.json", "JSONObject")
    }
}
