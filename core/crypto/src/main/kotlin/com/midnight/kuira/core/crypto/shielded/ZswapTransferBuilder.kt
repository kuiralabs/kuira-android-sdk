package com.midnight.kuira.core.crypto.shielded

import java.math.BigInteger

/**
 * Selected coin from ZswapLocalState, ready for spending.
 * Produced by [ZswapTransferBuilder.parseSelectedCoins].
 */
data class SelectedCoin(
    val typeHex: String,
    val value: BigInteger,
    val nonceHex: String,
    val mtIndex: Long,
    val nullifierHex: String,
) {
    /** Serialize back to JSON for passing to spend_coin FFI. */
    fun toJson(): String =
        """{"type_hex":"$typeHex","value":"$value","nonce_hex":"$nonceHex","mt_index":$mtIndex,"nullifier_hex":"$nullifierHex"}"""
}

/** Result of spending a coin via FFI. */
data class SpendResult(
    val newStatePtr: Long,
    val inputHex: String,
    val bindingRandomnessHex: String,
)

/** Result of creating an output via FFI. */
data class OutputResult(
    val outputHex: String,
    val bindingRandomnessHex: String,
)

/** Result of building an offer via FFI. */
data class OfferResult(
    val offerHex: String,
    val bindingRandomnessHex: String,
)

/** Full result of building a shielded transfer, ready for proof server. */
data class TransferResult(
    val transactionHex: String,
    val updatedStatePtr: Long,
    val offerHex: String,
)

/**
 * Builds shielded transfer transactions via composable FFI primitives.
 *
 * Deep module: small public interface hiding 7 FFI calls (ADR-001).
 *
 * **Unit-testable** (no native library): [parseSelectedCoins], [parseSpendResult],
 * [parseOutputResult], [parseOfferResult], [validateTransferInputs]
 *
 * **Requires native library**: [buildTransfer], [createOutput], [buildTransaction]
 */
class ZswapTransferBuilder private constructor() {

    companion object {

        private val nativeLoaded: Boolean by lazy {
            try {
                System.loadLibrary("kuira_crypto_ffi")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        private fun ensureNativeLoaded() {
            check(nativeLoaded) { "Native library kuira_crypto_ffi not available" }
        }

        // ── Public API (requires native library) ──

        /**
         * Build a shielded transfer: select coins → spend → create output → build offer → build transaction.
         *
         * This is the main entry point for simple transfers. Advanced callers
         * (ConnectedAPI, agents) can use the lower-level primitives directly.
         *
         * @param state ZswapLocalState with available coins
         * @param seed 32-byte zswap seed (m/44'/2400'/0'/3/0)
         * @param recipientCoinPk Recipient's coin public key (64-char hex)
         * @param recipientEncPk Recipient's encryption public key (64-char hex)
         * @param tokenType Token type hex (all zeros for NIGHT)
         * @param amount Transfer amount (micro-units)
         * @param networkId Network identifier ("undeployed", "preprod", "preview")
         * @param ttlMs Transaction TTL in milliseconds since epoch
         * @param dustTxHex Optional pre-built dust transaction hex
         * @return TransferResult or null on failure
         */
        fun buildTransfer(
            state: ZswapLocalState,
            seed: ByteArray,
            recipientCoinPk: String,
            recipientEncPk: String,
            tokenType: String,
            amount: BigInteger,
            networkId: String,
            ttlMs: Long,
            dustTxHex: String? = null,
        ): TransferResult? {
            ensureNativeLoaded()

            val validationError = validateTransferInputs(seed, recipientCoinPk, recipientEncPk, tokenType, amount)
            if (validationError != null) return null

            // Track intermediate state pointers for cleanup on failure
            val statePtrs = mutableListOf<Long>()
            try {
                // 7a: Select coins
                val coins = selectAndParseCoins(state.nativePtr(), tokenType, amount) ?: return null

                // 7b: Spend each selected coin, chaining state pointers
                val (finalStatePtr, inputHexes) = spendCoins(state.nativePtr(), seed, coins, statePtrs)
                    ?: return null

                // 7c: Create outputs (recipient + change if needed)
                val outputHexes = createTransferOutputs(
                    recipientCoinPk, recipientEncPk, tokenType, amount, coins, seed
                ) ?: return null

                // 7d: Build offer from inputs + outputs
                val offerResult = buildOfferFromParts(inputHexes, outputHexes) ?: return null

                // 7g: Build full transaction for proof server
                val txHex = nativeBuildShieldedTransaction(
                    offerResult.offerHex, networkId, dustTxHex, ttlMs
                ) ?: return null

                // Success — clean up intermediate states (keep only final)
                for (i in 0 until statePtrs.size - 1) {
                    ZswapLocalState.freeNativePtr(statePtrs[i])
                }
                statePtrs.clear()

                return TransferResult(
                    transactionHex = txHex,
                    updatedStatePtr = finalStatePtr,
                    offerHex = offerResult.offerHex,
                )
            } finally {
                // Clean up all state pointers on failure (statePtrs is cleared on success)
                statePtrs.forEach { ZswapLocalState.freeNativePtr(it) }
            }
        }

        private fun selectAndParseCoins(
            statePtr: Long, tokenType: String, amount: BigInteger,
        ): List<SelectedCoin>? {
            val json = nativeSelectCoins(statePtr, tokenType, amount.toString()) ?: return null
            val coins = parseSelectedCoins(json)
            return coins.ifEmpty { null }
        }

        private fun spendCoins(
            initialStatePtr: Long, seed: ByteArray, coins: List<SelectedCoin>,
            statePtrs: MutableList<Long>,
        ): Pair<Long, List<String>>? {
            var currentStatePtr = initialStatePtr
            val inputHexes = mutableListOf<String>()

            for (coin in coins) {
                val resultJson = nativeSpendCoinFull(currentStatePtr, seed, coin.toJson()) ?: return null
                val result = parseSpendResult(resultJson) ?: return null
                inputHexes.add(result.inputHex)
                statePtrs.add(result.newStatePtr)
                currentStatePtr = result.newStatePtr
            }
            return Pair(currentStatePtr, inputHexes)
        }

        private fun createTransferOutputs(
            recipientCoinPk: String, recipientEncPk: String,
            tokenType: String, amount: BigInteger,
            coins: List<SelectedCoin>, seed: ByteArray,
        ): List<String>? {
            val recipientJson = nativeCreateOutput(recipientCoinPk, recipientEncPk, tokenType, amount.toString())
                ?: return null
            val recipientOutput = parseOutputResult(recipientJson) ?: return null
            val outputs = mutableListOf(recipientOutput.outputHex)

            val totalSelected = coins.sumOf { it.value }
            if (totalSelected > amount) {
                val changeAmount = totalSelected - amount
                val senderKeys = ShieldedKeyDeriver.deriveKeys(seed) ?: return null
                val changeJson = nativeCreateOutput(
                    senderKeys.coinPublicKey, senderKeys.encryptionPublicKey,
                    tokenType, changeAmount.toString()
                ) ?: return null
                val changeOutput = parseOutputResult(changeJson) ?: return null
                outputs.add(changeOutput.outputHex)
            }
            return outputs
        }

        private fun buildOfferFromParts(
            inputHexes: List<String>, outputHexes: List<String>,
        ): OfferResult? {
            val inputsJson = "[${inputHexes.joinToString(",") { "\"$it\"" }}]"
            val outputsJson = "[${outputHexes.joinToString(",") { "\"$it\"" }}]"
            val offerJson = nativeBuildOffer(inputsJson, outputsJson) ?: return null
            return parseOfferResult(offerJson)
        }

        /**
         * Create a single shielded output (for receiving or change).
         * Lower-level primitive for ConnectedAPI callers.
         */
        fun createOutput(
            recipientCoinPk: String,
            recipientEncPk: String,
            tokenType: String,
            amount: BigInteger,
        ): OutputResult? {
            ensureNativeLoaded()
            val json = nativeCreateOutput(recipientCoinPk, recipientEncPk, tokenType, amount.toString())
                ?: return null
            return parseOutputResult(json)
        }

        /**
         * Build the full unproven Transaction for proof server submission.
         * Lower-level primitive for ConnectedAPI callers.
         */
        fun buildTransaction(
            offerHex: String,
            networkId: String,
            dustTxHex: String? = null,
            ttlMs: Long,
        ): String? {
            ensureNativeLoaded()
            return nativeBuildShieldedTransaction(offerHex, networkId, dustTxHex, ttlMs)
        }

        // NOTE on Offer Files (MIP-0005): [OfferResult.offerHex] as produced by
        // [buildTransfer]/[nativeBuildOffer] is an UNPROVEN offer — proving in
        // this pipeline happens later, at transaction level (see LocalProver).
        // MIP-0005 requires that "offer files MUST contain proven offers only",
        // so offerHex must never be wrapped by [OfferFile]. Producing a real
        // Offer File needs an FFI addition: extract the proven guaranteed offer
        // from a proven transaction and return its canonical ledger
        // serialization; those bytes are what [OfferFile.encode] wraps.

        /**
         * Build shielded Transaction with dust fee payment.
         * The dust spend is built internally from the DustLocalState.
         */
        fun buildTransactionWithDust(
            offerHex: String,
            networkId: String,
            dustStatePtr: Long,
            dustSeed: ByteArray,
            dustUtxosJson: String,
            currentTimeMs: Long,
            ttlMs: Long,
        ): String? {
            ensureNativeLoaded()
            return nativeBuildShieldedTransactionWithDust(
                offerHex, networkId, dustStatePtr, dustSeed, dustUtxosJson, currentTimeMs, ttlMs
            )
        }

        private fun cleanupStatePtrs(ptrs: List<Long>) {
            ptrs.forEach { ZswapLocalState.freeNativePtr(it) }
        }

        // ── JSON Parsing (unit-testable) ──

        fun parseSelectedCoins(json: String): List<SelectedCoin> {
            val trimmed = json.trim()
            if (trimmed == "[]") return emptyList()

            // Split JSON array into individual objects
            // Format: [{"key":"val",...},{"key":"val",...}]
            val objects = splitJsonArray(trimmed)
            return objects.map { objStr ->
                val fields = parseJsonObject(objStr)
                SelectedCoin(
                    typeHex = fields["type_hex"] ?: error("Missing type_hex"),
                    value = BigInteger(fields["value"] ?: error("Missing value")),
                    nonceHex = fields["nonce_hex"] ?: error("Missing nonce_hex"),
                    mtIndex = (fields["mt_index"] ?: error("Missing mt_index")).toLong(),
                    nullifierHex = fields["nullifier_hex"] ?: error("Missing nullifier_hex"),
                )
            }
        }

        fun parseSpendResult(json: String): SpendResult? {
            val fields = parseJsonObject(json)
            return try {
                SpendResult(
                    newStatePtr = (fields["new_state_ptr"] ?: return null).toLong(),
                    inputHex = fields["input_hex"] ?: return null,
                    bindingRandomnessHex = fields["binding_randomness_hex"] ?: return null,
                )
            } catch (e: NumberFormatException) {
                null
            }
        }

        fun parseOutputResult(json: String): OutputResult? {
            val fields = parseJsonObject(json)
            return OutputResult(
                outputHex = fields["output_hex"] ?: return null,
                bindingRandomnessHex = fields["binding_randomness_hex"] ?: return null,
            )
        }

        fun parseOfferResult(json: String): OfferResult? {
            val fields = parseJsonObject(json)
            return OfferResult(
                offerHex = fields["offer_hex"] ?: return null,
                bindingRandomnessHex = fields["binding_randomness_hex"] ?: return null,
            )
        }

        /**
         * Validates inputs before calling FFI. Returns error message or null if valid.
         */
        fun validateTransferInputs(
            seed: ByteArray,
            recipientCoinPk: String,
            recipientEncPk: String,
            tokenType: String,
            amount: BigInteger,
        ): String? {
            if (seed.size != 32) return "Seed must be 32 bytes, got ${seed.size}"
            if (amount <= BigInteger.ZERO) return "Transfer amount must be positive"
            if (!isValidHex(recipientCoinPk)) return "Invalid coin public key hex"
            if (!isValidHex(recipientEncPk)) return "Invalid encryption public key hex"
            if (!isValidHex(tokenType)) return "Invalid token type hex"
            return null
        }

        private fun isValidHex(hex: String): Boolean {
            if (hex.isEmpty()) return false
            if (hex.length % 2 != 0) return false
            return hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }

        /**
         * Parse a flat JSON object into a map of key→value strings.
         * Handles: {"key":"str_val","key2":123} — string and numeric values.
         * Does NOT handle nested objects or arrays.
         */
        internal fun parseJsonObject(json: String): Map<String, String> {
            val content = json.trim().removeSurrounding("{", "}")
            if (content.isBlank()) return emptyMap()

            val result = mutableMapOf<String, String>()
            // Split by comma, but respect quotes
            var i = 0
            while (i < content.length) {
                // Skip whitespace
                while (i < content.length && content[i].isWhitespace()) i++
                if (i >= content.length) break

                // Parse key (quoted string)
                if (content[i] != '"') { i++; continue }
                val keyEnd = content.indexOf('"', i + 1)
                if (keyEnd < 0) break
                val key = content.substring(i + 1, keyEnd)
                i = keyEnd + 1

                // Skip colon
                while (i < content.length && (content[i] == ':' || content[i].isWhitespace())) i++

                // Parse value
                val value: String
                if (i < content.length && content[i] == '"') {
                    // Quoted string value
                    val valEnd = content.indexOf('"', i + 1)
                    if (valEnd < 0) break
                    value = content.substring(i + 1, valEnd)
                    i = valEnd + 1
                } else {
                    // Numeric or boolean value — read until comma or end
                    val valStart = i
                    while (i < content.length && content[i] != ',') i++
                    value = content.substring(valStart, i).trim()
                }

                result[key] = value

                // Skip comma
                while (i < content.length && (content[i] == ',' || content[i].isWhitespace())) i++
            }
            return result
        }

        /** Exposed for testing — builds offer from hex arrays. */
        fun nativeBuildOfferPublic(inputsHexJson: String, outputsHexJson: String): String? {
            ensureNativeLoaded()
            return nativeBuildOffer(inputsHexJson, outputsHexJson)
        }

        // ── Native Methods (JNI bridge to Rust FFI) ──

        @JvmStatic private external fun nativeSelectCoins(statePtr: Long, tokenTypeHex: String, amountStr: String): String?
        @JvmStatic private external fun nativeSpendCoinFull(statePtr: Long, seed: ByteArray, coinJson: String): String?
        @JvmStatic private external fun nativeCreateOutput(coinPkHex: String, encPkHex: String, tokenTypeHex: String, amountStr: String): String?
        @JvmStatic private external fun nativeBuildOffer(inputsHexJson: String, outputsHexJson: String): String?
        @JvmStatic private external fun nativeMergeOffers(offer1Hex: String, offer2Hex: String): String?
        @JvmStatic private external fun nativeSerializeOffer(offerHex: String): String?
        @JvmStatic private external fun nativeBuildShieldedTransaction(offerHex: String, networkId: String, dustTxHex: String?, ttlMs: Long): String?
        @JvmStatic private external fun nativeBuildShieldedTransactionWithDust(offerHex: String, networkId: String, dustStatePtr: Long, dustSeed: ByteArray, dustUtxosJson: String, currentTimeMs: Long, ttlMs: Long): String?

        // ── Internal Helpers ──

        internal fun splitJsonArray(json: String): List<String> {
            val content = json.trim().removePrefix("[").removeSuffix("]").trim()
            if (content.isEmpty()) return emptyList()

            val objects = mutableListOf<String>()
            var depth = 0
            var start = -1
            for (i in content.indices) {
                when (content[i]) {
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            objects.add(content.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
            return objects
        }
    }
}
