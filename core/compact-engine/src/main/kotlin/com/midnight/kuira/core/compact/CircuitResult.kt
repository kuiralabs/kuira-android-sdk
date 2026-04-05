package com.midnight.kuira.core.compact

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a field-aligned byte array value from the Midnight runtime.
 *
 * The value is a list of byte arrays, one per alignment slot.
 * The alignment describes how each slot maps to ZK circuit fields.
 *
 * Example: a 32-byte public key has value = [[...32 bytes...]]
 *          and alignment = [{ tag: "atom", value: { tag: "bytes", length: 32 } }]
 */
data class AlignedValue(
    val value: List<ByteArray>,
    val alignmentJson: String,
) {
    companion object {
        /**
         * Parse from JSON with our custom Uint8Array encoding.
         * The JS serializer encodes Uint8Array as: { __type: "Uint8Array", data: [1,2,3,...] }
         */
        fun fromJson(json: JSONObject): AlignedValue {
            val valueArray = json.getJSONArray("value")
            val values = mutableListOf<ByteArray>()
            for (i in 0 until valueArray.length()) {
                values.add(parseByteArray(valueArray, i))
            }
            val alignment = json.getJSONArray("alignment")
            return AlignedValue(values, alignment.toString())
        }

        private fun parseByteArray(array: JSONArray, index: Int): ByteArray {
            val item = array.get(index)
            return when {
                // Encoded Uint8Array: { __type: "Uint8Array", data: [1,2,...] }
                item is JSONObject && item.optString("__type") == "Uint8Array" -> {
                    val data = item.getJSONArray("data")
                    ByteArray(data.length()) { data.getInt(it).toByte() }
                }
                // Plain array: [1, 2, 3, ...]
                item is JSONArray -> {
                    ByteArray(item.length()) { item.getInt(it).toByte() }
                }
                else -> ByteArray(0)
            }
        }
    }

}

/**
 * Proof data produced by a circuit execution.
 *
 * Contains everything needed to construct a ZK proof:
 * - input/output: the circuit's encoded arguments and return value
 * - publicTranscript: state read operations (opcodes + results)
 * - privateTranscriptOutputs: witness function outputs
 */
data class ProofData(
    val input: AlignedValue,
    val output: AlignedValue,
    val publicTranscriptJson: String,
    val privateTranscriptOutputs: List<AlignedValue>,
) {
    companion object {
        fun fromJson(json: JSONObject): ProofData {
            val input = AlignedValue.fromJson(json.getJSONObject("input"))
            val output = AlignedValue.fromJson(json.getJSONObject("output"))
            val transcriptArray = json.getJSONArray("publicTranscript")
            val privateOutputs = mutableListOf<AlignedValue>()
            val privArray = json.getJSONArray("privateTranscriptOutputs")
            for (i in 0 until privArray.length()) {
                privateOutputs.add(AlignedValue.fromJson(privArray.getJSONObject(i)))
            }
            return ProofData(
                input = input,
                output = output,
                publicTranscriptJson = transcriptArray.toString(),
                privateTranscriptOutputs = privateOutputs,
            )
        }
    }
}

/**
 * Full result from executing a contract circuit in QuickJS.
 *
 * Captures everything needed to build an UnprovenTransaction:
 * - proofData: ZK proof preimage
 * - stateHandle: Rust-side handle to the updated contract state
 * - contractAddress: address of the contract
 * - entryPoint: circuit name (e.g., "post", "takeDown")
 * - zswapLocalStateJson: coin inputs/outputs from circuit execution
 * - privateStateJson: updated private state
 */
data class CircuitResult(
    val proofData: ProofData,
    val stateHandle: Long,
    val contractAddress: String,
    val entryPoint: String,
    val zswapLocalStateJson: String,
    val privateStateJson: String,
) {
    companion object {
        /**
         * Parse from the JSON extracted from QuickJS.
         *
         * @param json The raw JSON from circuit execution
         * @param contractAddress The contract address (known by the caller)
         * @param entryPoint The circuit name (known by the caller)
         */
        fun fromJson(
            json: JSONObject,
            contractAddress: String,
            entryPoint: String,
        ): CircuitResult {
            val proofData = ProofData.fromJson(json.getJSONObject("proofData"))
            return CircuitResult(
                proofData = proofData,
                stateHandle = json.getLong("stateHandle"),
                contractAddress = contractAddress,
                entryPoint = entryPoint,
                zswapLocalStateJson = json.getJSONObject("zswapLocalState").toString(),
                privateStateJson = json.get("privateState").toString(),
            )
        }
    }

    // Note: Transaction assembly JSON is built in JS (via transformPublicTranscript)
    // and passed directly to ContractRuntime.assembleContractCallTx().
    // The transcript transform MUST happen in JS before extraction because it
    // uses the shim's transformAlignedValuePadded which handles Uint8Array conversion.
}
