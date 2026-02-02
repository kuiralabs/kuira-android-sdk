package com.midnight.kuira.core.ledger.api

import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.ledger.fee.DustSpendCreator
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.ledger.model.UtxoOutput
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes signed transactions to SCALE codec for node submission.
 *
 * **Phase 2:** Unshielded transactions only
 * **Future:** Will delegate to Rust FFI (midnight-ledger SCALE codec)
 */
interface TransactionSerializer {
    /**
     * Serialize a signed Intent to SCALE codec hex.
     *
     * @param intent Signed Intent with all signatures included
     * @return Hex-encoded SCALE bytes (without "0x" prefix)
     * @throws IllegalArgumentException if intent is invalid
     * @throws IllegalStateException if serialization fails
     */
    fun serialize(intent: Intent): String

    /**
     * Seal a proven transaction by transforming the binding commitment.
     *
     * After receiving a proven transaction from the proof server, this method
     * transforms the binding from `PedersenRandomness` (embedded-fr[v1]) to
     * `PureGeneratorPedersen` (pedersen-schnorr[v1]) before submitting to the node.
     *
     * @param provenTxHex Hex-encoded proven transaction from proof server
     * @return Hex-encoded finalized (sealed) transaction, or null on error
     */
    fun sealProvenTransaction(provenTxHex: String): String?
}

/**
 * FFI-based serializer using Rust midnight-ledger (Phase 2E).
 *
 * Serializes signed Intent to SCALE codec using midnight-ledger types.
 * Converts Kotlin models → JSON → Rust FFI → SCALE hex.
 */
class FfiTransactionSerializer : TransactionSerializer {

    init {
        // Load native library (same as TransactionSigner)
        try {
            System.loadLibrary("kuira_crypto_ffi")
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "Failed to load native library 'kuira_crypto_ffi'. " +
                "Ensure the library is built and bundled in the APK.",
                e
            )
        }
    }

    /**
     * Binding commitment returned from getSigningMessageForInput().
     * MUST be set before calling serialize()!
     */
    private var bindingCommitment: String? = null

    override fun serialize(intent: Intent): String {
        // Validate intent structure
        require(intent.guaranteedUnshieldedOffer != null) {
            "Phase 2: Intent must have guaranteed unshielded offer"
        }

        val offer = intent.guaranteedUnshieldedOffer
        require(offer.signatures.isNotEmpty()) {
            "Transaction must be signed before serialization"
        }

        // Validate we have binding_commitment from getSigningMessageForInput()
        val commitment = bindingCommitment
            ?: throw IllegalStateException("bindingCommitment not set! Must call getSigningMessageForInput() first")

        // Convert Intent components to JSON for Rust FFI
        val inputsJson = serializeInputsToJson(offer.inputs)
        val outputsJson = serializeOutputsToJson(offer.outputs)
        val signaturesJson = serializeSignaturesToJson(offer.signatures)
        val dustActionsJson = serializeDustActionsToJson(intent.dustActions)

        // Call Rust FFI for real SCALE serialization
        val hexResult = nativeSerializeTransaction(
            inputsJson,
            outputsJson,
            signaturesJson,
            dustActionsJson,
            intent.ttl,
            commitment  // CRITICAL: Use same binding_commitment from signing
        ) ?: throw IllegalStateException("FFI SCALE serialization failed")

        // DON'T clear binding_commitment here - it may be needed for serializeWithDust() later
        // It will be cleared after serializeWithDust() completes

        return hexResult
    }

    /**
     * Serialize a signed transaction WITH DUST FEE PAYMENT to SCALE codec hex.
     *
     * This method calls the Rust FFI that creates real DustActions by calling
     * state.spend() on the provided DustLocalState.
     *
     * @param inputs Transaction inputs
     * @param outputs Transaction outputs
     * @param signatures Signature ByteArrays (one per input, 64 bytes each)
     * @param dustState DustLocalState with available dust
     * @param seed 32-byte seed for deriving DustSecretKey
     * @param dustUtxosJson JSON array of {utxo_index, v_fee} selections
     * @param ttl Transaction time-to-live (milliseconds)
     * @return Hex-encoded SCALE bytes (without "0x" prefix)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws IllegalStateException if serialization fails
     */
    fun serializeWithDust(
        inputs: List<UtxoSpend>,
        outputs: List<UtxoOutput>,
        signatures: List<ByteArray>,
        dustState: com.midnight.kuira.core.crypto.dust.DustLocalState,
        seed: ByteArray,
        dustUtxosJson: String,
        ttl: Long
    ): String {
        // Validate
        require(seed.size == 32) { "Seed must be 32 bytes" }
        require(signatures.size == inputs.size) { "Must have one signature per input" }

        val commitment = bindingCommitment
            ?: throw IllegalStateException("bindingCommitment not set! Must call getSigningMessageForInput() first")

        // Convert to JSON
        val inputsJson = serializeInputsToJson(inputs)
        val outputsJson = serializeOutputsToJson(outputs)
        val signaturesJson = serializeSignaturesToJson(signatures)

        // DEBUG: Log parameters
        println("[serializeWithDust] Calling FFI with:")
        println("   - Inputs: ${inputs.size}")
        println("   - Outputs: ${outputs.size}")
        println("   - Signatures: ${signatures.size}")
        println("   - Dust UTXOs JSON: $dustUtxosJson")
        println("   - Seed size: ${seed.size} bytes")
        println("   - Binding commitment: ${commitment.take(32)}...")
        println("   - Dust state pointer: ${dustState.getStatePointer()}")

        // Call Rust FFI with dust
        val hexResult = nativeSerializeTransactionWithDust(
            inputsJson,
            outputsJson,
            signaturesJson,
            dustState.getStatePointer(),
            seed,
            dustUtxosJson,
            System.currentTimeMillis(),
            ttl,
            commitment
        ) ?: throw IllegalStateException("FFI SCALE serialization with dust failed")

        // Clear binding_commitment after use
        bindingCommitment = null

        return hexResult
    }

    /**
     * Serializes UtxoSpend list to JSON array.
     *
     * Format matches Rust JsonUtxoSpend struct:
     * ```json
     * [{
     *   "value": "1000000",
     *   "owner": "hex-encoded-verifying-key",
     *   "type": "hex-encoded-token-type",
     *   "intent_hash": "hex-encoded-intent-hash",  // Blockchain identifies UTXOs by intentHash + outputNo
     *   "output_no": 0
     * }]
     * ```
     */
    private fun serializeInputsToJson(inputs: List<UtxoSpend>): String {
        println("[FfiSerializer] Serializing ${inputs.size} inputs to JSON:")
        val jsonArray = JSONArray()
        for ((index, input) in inputs.withIndex()) {
            println("[FfiSerializer]   Input $index: intentHash=${input.intentHash}, outputNo=${input.outputNo}, value=${input.value}")
            println("[FfiSerializer]            intentHash.length=${input.intentHash.length} chars")
            val jsonObject = JSONObject().apply {
                put("value", input.value.toString())
                put("owner", input.ownerPublicKey)  // Hex-encoded public key (32 bytes BIP-340 x-only)
                put("type", input.tokenType)  // Already hex string (64 chars)
                // CRITICAL: The blockchain identifies UTXOs by intentHash + outputNo.
                put("intent_hash", input.intentHash)
                put("output_no", input.outputNo)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    /**
     * Serializes UtxoOutput list to JSON array.
     *
     * Format matches Rust JsonUtxoOutput struct:
     * ```json
     * [{
     *   "value": "1000000",
     *   "owner": "hex-encoded-user-address",
     *   "type": "hex-encoded-token-type"
     * }]
     * ```
     */
    private fun serializeOutputsToJson(outputs: List<UtxoOutput>): String {
        val jsonArray = JSONArray()
        for (output in outputs) {
            // Decode Bech32m address to get UserAddress bytes (SHA-256 hash of public key)
            val (_, userAddressBytes) = Bech32m.decode(output.owner)
            val userAddressHex = userAddressBytes.toHexString()

            val jsonObject = JSONObject().apply {
                put("value", output.value.toString())
                put("owner", userAddressHex)  // Hex-encoded UserAddress (32 bytes)
                put("type", output.tokenType)  // Already hex string (64 chars)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    /**
     * Serializes signatures list to JSON array.
     *
     * Format: array of hex-encoded signature strings:
     * ```json
     * ["hex-sig-1", "hex-sig-2", ...]
     * ```
     */
    private fun serializeSignaturesToJson(signatures: List<ByteArray>): String {
        val jsonArray = JSONArray()
        for (signature in signatures) {
            jsonArray.put(signature.toHexString())
        }
        return jsonArray.toString()
    }

    /**
     * Serializes dust actions to JSON array.
     *
     * Format matches Rust JsonDustSpend struct:
     * ```json
     * [{
     *   "v_fee": "1000000",
     *   "old_nullifier": "hex-encoded-nullifier",
     *   "new_commitment": "hex-encoded-commitment",
     *   "proof": "proof-preimage"
     * }]
     * ```
     */
    private fun serializeDustActionsToJson(dustActions: List<DustSpendCreator.DustSpend>?): String {
        if (dustActions == null || dustActions.isEmpty()) {
            return "[]"  // Empty array for no dust actions
        }

        val jsonArray = JSONArray()
        for (dustSpend in dustActions) {
            val jsonObject = JSONObject().apply {
                put("v_fee", dustSpend.vFee.toString())
                put("old_nullifier", dustSpend.oldNullifier)
                put("new_commitment", dustSpend.newCommitment)
                put("proof", dustSpend.proof)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    /**
     * Converts ByteArray to lowercase hex string (no "0x" prefix).
     */
    private fun ByteArray.toHexString(): String {
        return this.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * JNI: Serializes transaction to SCALE codec.
     *
     * @param inputsJson JSON array of UtxoSpend objects
     * @param outputsJson JSON array of UtxoOutput objects
     * @param signaturesJson JSON array of signature hex strings
     * @param dustActionsJson JSON array of DustSpend objects (empty array if no dust)
     * @param ttl Transaction time-to-live (milliseconds)
     * @param bindingCommitmentHex Hex-encoded binding commitment (MUST match the one from nativeGetSigningMessageForInput!)
     * @return Hex-encoded SCALE bytes, or null on error
     */
    private external fun nativeSerializeTransaction(
        inputsJson: String,
        outputsJson: String,
        signaturesJson: String,
        dustActionsJson: String,
        ttl: Long,
        bindingCommitmentHex: String
    ): String?

    /**
     * JNI: Serializes transaction with REAL dust fee payment.
     *
     * This function creates real DustActions by calling state.spend() on the DustLocalState,
     * following the TypeScript SDK pattern. This is the CORRECT way to add dust fees.
     *
     * @param inputsJson JSON array of UtxoSpend objects
     * @param outputsJson JSON array of UtxoOutput objects
     * @param signaturesJson JSON array of signature hex strings
     * @param dustStatePtr Pointer to DustLocalState (from DustWalletManager)
     * @param seed 32-byte seed for deriving DustSecretKey
     * @param dustUtxosJson JSON array of {utxo_index, v_fee} objects
     * @param currentTimeMs Current time in milliseconds
     * @param ttl Transaction time-to-live (milliseconds)
     * @param bindingCommitmentHex Hex-encoded binding commitment
     * @return Hex-encoded SCALE bytes, or null on error
     */
    private external fun nativeSerializeTransactionWithDust(
        inputsJson: String,
        outputsJson: String,
        signaturesJson: String,
        dustStatePtr: Long,
        seed: ByteArray,
        dustUtxosJson: String,
        currentTimeMs: Long,
        ttl: Long,
        bindingCommitmentHex: String
    ): String?

    /**
     * JNI: Generates signing message for a specific UTXO input.
     *
     * This is CRITICAL for real on-chain transactions. This function:
     * 1. Builds an Intent from the inputs/outputs
     * 2. Binds the Intent (required by Midnight protocol)
     * 3. Returns the signature data that must be signed for the given input
     *
     * **Usage:**
     * ```kotlin
     * val signingMessageHex = serializer.getSigningMessageForInput(inputsJson, outputsJson, inputIndex, ttl)
     * val signingMessage = hex.decode(signingMessageHex)
     * val signature = TransactionSigner.signData(privateKey, signingMessage)
     * ```
     *
     * @param inputsJson JSON array of UtxoSpend objects (WITHOUT signatures)
     * @param outputsJson JSON array of UtxoOutput objects
     * @param inputIndex Which input to generate signature data for (0-based)
     * @param ttl Transaction time-to-live (milliseconds)
     * @return Hex-encoded signing message bytes, or null on error
     */
    private external fun nativeGetSigningMessageForInput(
        inputsJson: String,
        outputsJson: String,
        inputIndex: Int,
        ttl: Long
    ): String?

    /**
     * Public API: Get signing message for a specific input.
     *
     * This is the data that MUST be signed for the transaction to be valid on-chain.
     *
     * IMPORTANT: This function also stores the binding_commitment internally,
     * which will be used when calling serialize(). You must call this before serialize()!
     *
     * @param inputs List of UtxoSpend (inputs to the transaction)
     * @param outputs List of UtxoOutput (outputs from the transaction)
     * @param inputIndex Which input to sign (0-based)
     * @param ttl Transaction time-to-live (milliseconds since epoch)
     * @return Hex-encoded signing message, or null on error
     */
    fun getSigningMessageForInput(
        inputs: List<UtxoSpend>,
        outputs: List<UtxoOutput>,
        inputIndex: Int,
        ttl: Long
    ): String? {
        require(inputIndex >= 0 && inputIndex < inputs.size) {
            "inputIndex $inputIndex out of bounds (have ${inputs.size} inputs)"
        }

        val inputsJson = serializeInputsToJson(inputs)
        val outputsJson = serializeOutputsToJson(outputs)

        // Get JSON response: {"signing_message": "hex", "binding_commitment": "hex"}
        val jsonResponse = nativeGetSigningMessageForInput(inputsJson, outputsJson, inputIndex, ttl)
            ?: return null

        // Parse JSON response
        val json = JSONObject(jsonResponse)
        val signingMessage = json.getString("signing_message")
        val commitment = json.getString("binding_randomness")

        // Store binding_commitment for later use in serialize()
        bindingCommitment = commitment

        println("[TransactionSerializer] Stored binding_commitment: ${commitment.take(20)}...")

        return signingMessage
    }

    /**
     * JNI: Seals a proven transaction by transforming the binding commitment.
     */
    override fun sealProvenTransaction(provenTxHex: String): String? {
        return nativeSealProvenTransaction(provenTxHex)
    }

    /**
     * Native function: Seals a proven transaction.
     *
     * @param provenTxHex Hex-encoded proven transaction from proof server
     * @return Hex-encoded finalized (sealed) transaction, or null on error
     */
    private external fun nativeSealProvenTransaction(provenTxHex: String): String?

    /**
     * Get the Midnight transaction hash from a sealed transaction.
     *
     * This returns the hash that will appear in the indexer, NOT the extrinsic hash
     * that the node RPC returns. These are different hashes:
     * - Extrinsic hash: Hash of the Substrate extrinsic wrapper (from node RPC)
     * - Midnight tx hash: Hash of the actual Midnight transaction (what indexer uses)
     *
     * @param sealedTxHex Hex-encoded sealed transaction
     * @return Hex-encoded Midnight transaction hash, or null on error
     */
    fun getTransactionHash(sealedTxHex: String): String? {
        return nativeGetTransactionHash(sealedTxHex)
    }

    /**
     * JNI: Gets the Midnight transaction hash from a sealed transaction.
     */
    private external fun nativeGetTransactionHash(sealedTxHex: String): String?

    /**
     * DEPRECATED: Stub function for backward compatibility.
     * Use nativeSerializeTransaction() instead.
     */
    @Deprecated("Use nativeSerializeTransaction with JSON parameters")
    private external fun nativeSerializeTransactionStub(ttl: Long): String?
}

/**
 * Pure Kotlin stub serializer (for testing without FFI).
 *
 * Generates deterministic mock hex for unit tests.
 */
class StubTransactionSerializer : TransactionSerializer {
    override fun serialize(intent: Intent): String {
        // Validate intent structure
        require(intent.guaranteedUnshieldedOffer != null) {
            "Phase 2: Intent must have guaranteed unshielded offer"
        }

        val offer = intent.guaranteedUnshieldedOffer
        require(offer.signatures.isNotEmpty()) {
            "Transaction must be signed before serialization"
        }

        // Generate deterministic mock hex based on intent data
        // Format: [magic][version][inputs_count][outputs_count][signatures_count][ttl]
        val magic = "4d4e"  // "MN" in hex
        val version = "01"
        val inputsCount = String.format("%02x", offer.inputs.size)
        val outputsCount = String.format("%02x", offer.outputs.size)
        val signaturesCount = String.format("%02x", offer.signatures.size)
        val ttlHex = String.format("%016x", intent.ttl)

        // Add some padding to make it look like a real transaction (128 bytes total)
        val padding = "00".repeat(100)

        return magic + version + inputsCount + outputsCount + signaturesCount + ttlHex + padding
    }

    override fun sealProvenTransaction(provenTxHex: String): String? {
        // Stub implementation: just return the input unchanged
        // Real sealing would transform binding commitment type
        return provenTxHex
    }
}
