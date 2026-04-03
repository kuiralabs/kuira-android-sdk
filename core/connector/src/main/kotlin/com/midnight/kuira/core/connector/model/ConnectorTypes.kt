package com.midnight.kuira.core.connector.model

import java.math.BigInteger

/**
 * Types matching the official @midnight-ntwrk/dapp-connector-api.
 *
 * Source of truth: midnight-dapp-connector-api/src/api.ts
 */

/** Hex-encoded token type (64 zero chars for NIGHT). */
typealias TokenType = String

/** Desired output from a transfer or intent. */
data class DesiredOutput(
    val kind: TransferKind,
    val type: TokenType,
    val value: BigInteger,
    val recipient: String,
)

/** Desired input for an intent (swaps). */
data class DesiredInput(
    val kind: TransferKind,
    val type: TokenType,
    val value: BigInteger,
)

enum class TransferKind { SHIELDED, UNSHIELDED }

/** Wallet configuration exposed to dApps. */
data class Configuration(
    val indexerUri: String,
    val indexerWsUri: String,
    val substrateNodeUri: String,
    val networkId: String,
    // proverServerUri is @deprecated — use getProvingProvider instead
)

/** Connection status. */
sealed class ConnectionStatus {
    data class Connected(val networkId: String) : ConnectionStatus()
    data object Disconnected : ConnectionStatus()
}

/** Transaction history entry. */
data class HistoryEntry(
    val txHash: String,
    val txStatus: TxStatus,
)

sealed class TxStatus {
    data class Finalized(val executionStatus: Map<Int, String>) : TxStatus()
    data class Confirmed(val executionStatus: Map<Int, String>) : TxStatus()
    data object Pending : TxStatus()
    data object Discarded : TxStatus()
}

/** Sign data options. */
data class SignDataOptions(
    val encoding: SignEncoding,
    val keyType: String = "unshielded",
)

enum class SignEncoding { HEX, BASE64, TEXT }

/** Signature result. */
data class SignatureResult(
    val data: String,
    val signature: String,
    val verifyingKey: String,
)

/** Intent options. */
data class IntentOptions(
    val intentId: IntentId,
    val payFees: Boolean,
)

sealed class IntentId {
    data class Fixed(val id: Int) : IntentId()
    data object Random : IntentId()
}

/** Error codes matching the official API. */
object ErrorCodes {
    const val INTERNAL_ERROR = "InternalError"
    const val REJECTED = "Rejected"
    const val INVALID_REQUEST = "InvalidRequest"
    const val PERMISSION_REJECTED = "PermissionRejected"
    const val DISCONNECTED = "Disconnected"
}

/** Connector API error. */
class ConnectorApiError(
    val code: String,
    override val message: String,
) : Exception(message)

// ── Result types ──

data class DustBalance(val cap: BigInteger, val balance: BigInteger)
data class UnshieldedAddressResult(val unshieldedAddress: String)
data class ShieldedAddressesResult(
    val shieldedAddress: String,
    val shieldedCoinPublicKey: String,
    val shieldedEncryptionPublicKey: String,
)
data class DustAddressResult(val dustAddress: String)
data class ProvingProviderResult(val proverServerUri: String?)
