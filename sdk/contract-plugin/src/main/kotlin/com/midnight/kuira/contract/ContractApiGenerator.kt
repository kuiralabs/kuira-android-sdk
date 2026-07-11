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
 * `Enum` and non-empty `Tuple` argument types have no verified wire encoding for
 * the typed path (no in-repo contract exercises them as args). A circuit whose
 * argument type-tree contains either is GRACEFULLY SKIPPED — instead of a typed
 * method that would throw, the facade emits a comment pointing the caller at the
 * raw [MidnightContract.call]. The contract's other circuits still get typed
 * methods. Such enums/tuples are therefore NOT emitted as supporting types.
 *
 * Constructors are NOT in the ABI, so no typed `deploy` is generated — deploy
 * stays on the raw [MidnightContract.deploy].
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
    private val BIG_INTEGER = ClassName("java.math", "BigInteger")
    private val LIST = ClassName("kotlin.collections", "List")

    /** Name of the generated struct marshalling extension: `fun X.toCallArg()`. */
    const val TO_CALL_ARG = "toCallArg"

    private val TO_CALL_ARG_MEMBER = MemberName(GENERATED_PACKAGE, TO_CALL_ARG)

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
     */
    fun generate(alias: String, contractInfoJson: String): FileSpec {
        val root = JsonValue.parse(contractInfoJson) as? JsonObject
            ?: error("contract-info.json is not a JSON object")
        val circuits = (root["circuits"] as? JsonArray)?.items.orEmpty()

        // Collect generated supporting types (Structs -> data class, Enums ->
        // enum class), de-duplicated by name across all circuit arguments.
        val supporting = SupportingTypes()

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

        val skipNotices = mutableListOf<String>()
        for (circuit in circuits) {
            val obj = circuit as? JsonObject ?: continue
            when (val built = buildCircuitFun(obj, supporting)) {
                is CircuitFun.Generated -> facade.addFunction(built.spec)
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

        val fileBuilder = FileSpec.builder(GENERATED_PACKAGE, className)
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

        return fileBuilder.build()
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
            "Enum" -> "Enum"
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
                // A Vector marshals element-wise only for the single-level
                // Vector<Struct> case (`map { it.toCallArg() }`). Nested vectors of
                // structs would need a deeper map; that shape is unverified, so flag
                // it rather than emit subtly-wrong code. Vector<scalar> passes through.
                if (innerName == "Vector" && firstUnmarshallable(resolvedInner) == null &&
                    containsStruct(resolvedInner)
                ) {
                    "Vector<Vector<Struct>>"
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
            "Struct" -> CodeBlock.of("%L.%M()", receiver, TO_CALL_ARG_MEMBER)
            "Vector" -> {
                val inner = type["type"] as? JsonObject ?: error("Vector missing inner type")
                if (innerNeedsMarshalling(inner)) {
                    CodeBlock.of("%L.map·{ it.%M() }", receiver, TO_CALL_ARG_MEMBER)
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
            "Alias" -> {
                val deeper = inner["type"] as? JsonObject ?: error("Alias missing inner type")
                innerNeedsMarshalling(deeper)
            }
            else -> false
        }
    }

    /** True if [type]'s tree contains a Struct anywhere (Vector/Alias-transparent). */
    private fun containsStruct(type: JsonObject): Boolean {
        val name = (type["type-name"] as? JsonString)?.value
            ?: error("type node missing type-name")
        return when (name) {
            "Struct" -> true
            "Vector", "Alias" -> {
                val inner = type["type"] as? JsonObject ?: error("$name missing inner type")
                containsStruct(inner)
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
        return supporting.registerStruct(
            name = structName,
            elements = elements,
            mapElement = { elemType -> mapType(elemType, supporting) },
            marshalField = { fieldName, elemType ->
                // Inside the generated `fun Struct.toCallArg()`, the field is in
                // scope as a property — reference it bare and marshal recursively.
                marshalExpression(CodeBlock.of("%N", fieldName), elemType)
            },
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
private class SupportingTypes {
    private val types = LinkedHashMap<String, TypeSpec>()

    /** `Struct.toCallArg()` extensions, keyed by struct name, in insertion order. */
    private val callArgFunctions = LinkedHashMap<String, FunSpec>()
    private val pkg = ContractApiGenerator.GENERATED_PACKAGE

    fun registerStruct(
        name: String,
        elements: List<JsonObject>,
        mapElement: (JsonObject) -> TypeName,
        marshalField: (fieldName: String, fieldType: JsonObject) -> CodeBlock,
    ): ClassName {
        if (!types.containsKey(name)) {
            // Reserve the slot before recursing so a self/mutually-referential
            // struct doesn't infinitely recurse (compactc nests but is acyclic
            // in practice; this is belt-and-braces).
            types[name] = PLACEHOLDER
            val ctor = FunSpec.constructorBuilder()
            val builder = TypeSpec.classBuilder(name).addModifiers(KModifier.DATA)

            // Body of the marshalling extension: mapOf("field" to <marshalled>, ...).
            val mapEntries = mutableListOf<CodeBlock>()
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
        }
        return ClassName(pkg, name)
    }

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

    companion object {
        // Sentinel held while a struct is being built so a cyclic reference is a
        // no-op re-entry rather than infinite recursion.
        private val PLACEHOLDER: TypeSpec = TypeSpec.classBuilder("__placeholder__").build()

        private val MAP_OF = MemberName("kotlin.collections", "mapOf")
        private val CALL_ARG_MAP_TYPE: TypeName =
            MAP.parameterizedBy(STRING, ANY.copy(nullable = true))
    }
}
