package com.midnight.kuira.sdk

import com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Builds the `unshielded_funding` JSON that funds the value a contract receives via
 * `receiveUnshielded` (e.g. a treasury deposit). The native assembler (`build_funding_offer`)
 * turns this into a signed guaranteed offer whose inputs cover `amount` (+ change), so the
 * unshielded segment balances instead of underflowing → node error 138.
 *
 * Pure (no crypto / IO / SDK state) so it's unit-testable: [MidnightSdk.buildUnshieldedFundingJson]
 * resolves the wallet UTXOs, owner key, change address, and signing key, then delegates here.
 */
internal object UnshieldedFundingBuilder {

    /** Result of [select]: the covering UTXOs (largest-first) and the change they leave. */
    data class Selection(val inputs: List<UnshieldedUtxoEntity>, val change: BigInteger)

    /**
     * Pick a minimal largest-first set of [candidates] covering [amount]. This is the logic that
     * matters (coverage + input count), kept free of org.json so it's unit-testable on the JVM.
     *
     * @throws IllegalArgumentException if [amount] is not positive.
     * @throws InsufficientFundsException when [candidates] can't cover [amount].
     */
    fun select(candidates: List<UnshieldedUtxoEntity>, amount: BigInteger): Selection {
        require(amount > BigInteger.ZERO) { "Funding amount must be positive, got $amount" }

        // Largest-first: minimise the input count (each input adds fee + a signature). Stop as
        // soon as the running total covers the amount.
        val selected = mutableListOf<UnshieldedUtxoEntity>()
        var total = BigInteger.ZERO
        for (utxo in candidates.sortedByDescending { BigInteger(it.value) }) {
            if (total >= amount) break
            selected += utxo
            total += BigInteger(utxo.value)
        }
        if (total < amount) {
            throw InsufficientFundsException(
                "Insufficient NIGHT to fund the contract: need $amount, have $total " +
                    "across ${candidates.size} UTXO(s).",
            )
        }
        return Selection(selected, total - amount)
    }

    /**
     * Build the `unshielded_funding` JSON for the native assembler.
     *
     * @param candidates wallet UTXOs already filtered to the funding token type.
     * @param amount base units the contract will receive (> 0).
     * @param ownerPublicKeyHex derived HD verifying key (64 hex chars) — the input owner.
     * @param changeOwnerHex this wallet's 32-byte UserAddress hex (change recipient).
     * @param nightPrivateKeyHex the wallet's NIGHT signing key hex (32 bytes). Never logged.
     */
    fun build(
        candidates: List<UnshieldedUtxoEntity>,
        amount: BigInteger,
        ownerPublicKeyHex: String,
        changeOwnerHex: String,
        nightPrivateKeyHex: String,
    ): String {
        val selection = select(candidates, amount)

        val inputsJson = JSONArray()
        for (utxo in selection.inputs) {
            inputsJson.put(
                JSONObject().apply {
                    put("value", utxo.value)
                    put("owner", ownerPublicKeyHex)
                    put("type", utxo.tokenType)
                    put("intent_hash", utxo.intentHash)
                    put("output_no", utxo.outputIndex)
                },
            )
        }

        // The native side omits the change output when change == 0 (change = total − amount).
        return JSONObject().apply {
            put("amount", amount.toString())
            put("change_owner", changeOwnerHex)
            put("night_private_key", nightPrivateKeyHex)
            put("inputs", inputsJson)
        }.toString()
    }
}
