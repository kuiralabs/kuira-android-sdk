package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.ConnectorApiError
import com.midnight.kuira.core.connector.model.ConnectionStatus
import com.midnight.kuira.core.connector.model.DesiredInput
import com.midnight.kuira.core.connector.model.DesiredOutput
import com.midnight.kuira.core.connector.model.IntentId
import com.midnight.kuira.core.connector.model.IntentOptions
import com.midnight.kuira.core.connector.model.SignDataOptions
import com.midnight.kuira.core.connector.model.SignEncoding
import com.midnight.kuira.core.connector.model.TransferKind
import com.midnight.kuira.core.connector.model.TxStatus
import kotlinx.serialization.json.*
import java.math.BigInteger

/**
 * JSON-RPC 2.0 message router for the ConnectedAPI.
 *
 * Parses incoming JSON-RPC requests, routes to the appropriate
 * ConnectedAPIHandler method, and formats JSON-RPC responses.
 *
 * Protocol: https://www.jsonrpc.org/specification
 */
class JsonRpcRouter(private val handler: ConnectedAPIHandler) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handleMessage(message: String): String {
        var id: JsonElement = JsonNull
        try {
            val request = json.parseToJsonElement(message).jsonObject
            id = request["id"] ?: JsonNull
            val method = request["method"]?.jsonPrimitive?.content
                ?: return errorResponse(id, -32600, "Missing method")
            val params = request["params"]?.jsonObject ?: buildJsonObject {}

            val result = routeMethod(method, params)
            return successResponse(id, result)
        } catch (e: kotlinx.serialization.SerializationException) {
            return errorResponse(id, -32700, "Parse error: ${e.message}")
        } catch (e: MethodNotFoundException) {
            return errorResponse(id, -32601, e.message ?: "Method not found")
        } catch (e: ConnectorApiError) {
            return errorResponse(id, -32603, "${e.code}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return errorResponse(id, -32602, "Invalid params: ${e.message}")
        } catch (e: Exception) {
            return errorResponse(id, -32603, "Internal error: ${e.message}")
        }
    }

    private suspend fun routeMethod(method: String, params: JsonObject): JsonElement {
        return when (method) {
            "getConnectionStatus" -> {
                val status = handler.getConnectionStatus()
                buildJsonObject {
                    when (status) {
                        is ConnectionStatus.Connected -> {
                            put("status", "connected")
                            put("networkId", status.networkId)
                        }
                        is ConnectionStatus.Disconnected -> {
                            put("status", "disconnected")
                        }
                    }
                }
            }

            "getConfiguration" -> {
                val config = handler.getConfiguration()
                buildJsonObject {
                    put("indexerUri", config.indexerUri)
                    put("indexerWsUri", config.indexerWsUri)
                    put("proverServerUri", JsonNull) // @deprecated — use getProvingProvider
                    put("substrateNodeUri", config.substrateNodeUri)
                    put("networkId", config.networkId)
                }
            }

            "getUnshieldedAddress" -> {
                val result = handler.getUnshieldedAddress()
                buildJsonObject {
                    put("unshieldedAddress", result.unshieldedAddress)
                }
            }

            "getShieldedAddresses" -> {
                val result = handler.getShieldedAddresses()
                buildJsonObject {
                    put("shieldedAddress", result.shieldedAddress)
                    put("shieldedCoinPublicKey", result.shieldedCoinPublicKey)
                    put("shieldedEncryptionPublicKey", result.shieldedEncryptionPublicKey)
                }
            }

            "getDustAddress" -> {
                val result = handler.getDustAddress()
                buildJsonObject {
                    put("dustAddress", result.dustAddress)
                }
            }

            "getUnshieldedBalances" -> {
                val balances = handler.getUnshieldedBalances()
                buildJsonObject {
                    balances.forEach { (token, amount) ->
                        put(token, amount.toString())
                    }
                }
            }

            "getShieldedBalances" -> {
                val balances = handler.getShieldedBalances()
                buildJsonObject {
                    balances.forEach { (token, amount) ->
                        put(token, amount.toString())
                    }
                }
            }

            "getDustBalance" -> {
                val dust = handler.getDustBalance()
                buildJsonObject {
                    put("cap", dust.cap.toString())
                    put("balance", dust.balance.toString())
                }
            }

            "getTxHistory" -> {
                val pageNumber = params["pageNumber"]?.jsonPrimitive?.int ?: 0
                val pageSize = params["pageSize"]?.jsonPrimitive?.int ?: 20
                val entries = handler.getTxHistory(pageNumber, pageSize)
                buildJsonArray {
                    entries.forEach { entry ->
                        add(buildJsonObject {
                            put("txHash", entry.txHash)
                            put("txStatus", serializeTxStatus(entry.txStatus))
                        })
                    }
                }
            }

            "getProvingProvider" -> {
                val result = handler.getProvingProvider()
                buildJsonObject {
                    put("proverServerUri", result.proverServerUri?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }

            "hintUsage" -> {
                val methods = params["methodNames"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                handler.hintUsage(methods)
                JsonNull
            }

            "makeTransfer" -> {
                val outputs = parseDesiredOutputs(params["desiredOutputs"]?.jsonArray)
                val payFees = parsePayFees(params)
                val txHex = handler.makeTransfer(outputs, payFees)
                buildJsonObject { put("tx", txHex) }
            }

            "submitTransaction" -> {
                val tx = params["tx"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing 'tx' parameter")
                handler.submitTransaction(tx)
                JsonNull
            }

            "balanceUnsealedTransaction" -> {
                val tx = params["tx"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing 'tx' parameter")
                val payFees = parsePayFees(params)
                val result = handler.balanceUnsealedTransaction(tx, payFees)
                buildJsonObject { put("tx", result) }
            }

            "balanceSealedTransaction" -> {
                val tx = params["tx"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing 'tx' parameter")
                val payFees = parsePayFees(params)
                val result = handler.balanceSealedTransaction(tx, payFees)
                buildJsonObject { put("tx", result) }
            }

            "makeIntent" -> {
                val inputs = parseDesiredInputs(params["desiredInputs"]?.jsonArray)
                val outputs = parseDesiredOutputs(params["desiredOutputs"]?.jsonArray)
                val options = parseIntentOptions(params["options"]?.jsonObject)
                val result = handler.makeIntent(inputs, outputs, options)
                buildJsonObject { put("tx", result) }
            }

            "signData" -> {
                val data = params["data"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing 'data' parameter")
                val options = parseSignDataOptions(params["options"]?.jsonObject)
                val result = handler.signData(data, options)
                buildJsonObject {
                    put("data", result.data)
                    put("signature", result.signature)
                    put("verifyingKey", result.verifyingKey)
                }
            }

            else -> throw MethodNotFoundException(method)
        }
    }

    // ── Response formatting ──

    private fun successResponse(id: JsonElement, result: JsonElement): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString()
    }

    // ── Serialization helpers ──

    private fun serializeTxStatus(status: TxStatus): JsonObject {
        return when (status) {
            is TxStatus.Finalized -> buildJsonObject {
                put("status", "finalized")
                put("executionStatus", serializeExecutionStatus(status.executionStatus))
            }
            is TxStatus.Confirmed -> buildJsonObject {
                put("status", "confirmed")
                put("executionStatus", serializeExecutionStatus(status.executionStatus))
            }
            is TxStatus.Pending -> buildJsonObject {
                put("status", "pending")
            }
            is TxStatus.Discarded -> buildJsonObject {
                put("status", "discarded")
            }
        }
    }

    private fun serializeExecutionStatus(status: Map<Int, String>): JsonObject {
        return buildJsonObject {
            status.forEach { (index, result) ->
                put(index.toString(), result)
            }
        }
    }

    // ── Parameter parsing ──

    /** Parse payFees from either options.payFees or top-level payFees. */
    private fun parsePayFees(params: JsonObject): Boolean {
        return params["options"]?.jsonObject?.get("payFees")?.jsonPrimitive?.boolean
            ?: params["payFees"]?.jsonPrimitive?.boolean
            ?: true
    }

    private fun parseDesiredOutputs(array: JsonArray?): List<DesiredOutput> {
        return array?.map { element ->
            val obj = element.jsonObject
            DesiredOutput(
                kind = TransferKind.valueOf(
                    obj["kind"]!!.jsonPrimitive.content.uppercase()
                ),
                type = obj["type"]!!.jsonPrimitive.content,
                value = BigInteger(obj["value"]!!.jsonPrimitive.content),
                recipient = obj["recipient"]!!.jsonPrimitive.content,
            )
        } ?: emptyList()
    }

    private fun parseDesiredInputs(array: JsonArray?): List<DesiredInput> {
        return array?.map { element ->
            val obj = element.jsonObject
            DesiredInput(
                kind = TransferKind.valueOf(
                    obj["kind"]!!.jsonPrimitive.content.uppercase()
                ),
                type = obj["type"]!!.jsonPrimitive.content,
                value = BigInteger(obj["value"]!!.jsonPrimitive.content),
            )
        } ?: emptyList()
    }

    private fun parseIntentOptions(obj: JsonObject?): IntentOptions {
        requireNotNull(obj) { "Missing 'options' parameter" }
        val intentIdElement = obj["intentId"]
            ?: throw IllegalArgumentException("Missing 'intentId' in options")
        val intentId = when {
            intentIdElement is JsonPrimitive && intentIdElement.isString
                && intentIdElement.content == "random" -> IntentId.Random
            intentIdElement is JsonPrimitive -> IntentId.Fixed(intentIdElement.int)
            else -> throw IllegalArgumentException("Invalid intentId: expected number or \"random\"")
        }
        return IntentOptions(
            intentId = intentId,
            payFees = obj["payFees"]?.jsonPrimitive?.boolean ?: true,
        )
    }

    private fun parseSignDataOptions(obj: JsonObject?): SignDataOptions {
        requireNotNull(obj) { "Missing 'options' parameter" }
        val encoding = SignEncoding.valueOf(
            obj["encoding"]!!.jsonPrimitive.content.uppercase()
        )
        val keyType = obj["keyType"]?.jsonPrimitive?.content ?: "unshielded"
        return SignDataOptions(
            encoding = encoding,
            keyType = keyType,
        )
    }
}

private class MethodNotFoundException(method: String) :
    Exception("Method not found: $method")
