package com.midnight.kuira.core.connector

import android.util.Log
import com.midnight.kuira.core.connector.model.*
import com.midnight.kuira.core.network.NetworkConfig
import java.math.BigInteger

/**
 * Implements all 16 ConnectedAPI methods (15 WalletConnectedAPI + hintUsage).
 *
 * Pure business logic — delegates to existing Kuira services.
 * No networking, no Android UI. Transport layers wrap this class.
 *
 * Source of truth: @midnight-ntwrk/dapp-connector-api/src/api.ts
 */
/**
 * Wallet addresses — derived once at wallet setup, passed to the handler.
 */
data class WalletAddresses(
    val unshieldedAddress: String,
    val shieldedAddress: String,
    val shieldedCoinPublicKey: String,
    val shieldedEncryptionPublicKey: String,
    val dustAddress: String,
)

/**
 * Balance provider interface — decouples handler from specific repository implementations.
 * Allows the SDK to work with different data sources (real repos, mocks, etc.)
 */
interface BalanceProvider {
    suspend fun getUnshieldedBalances(): Map<TokenType, BigInteger>
    suspend fun getShieldedBalances(): Map<TokenType, BigInteger>
    suspend fun getDustBalance(): DustBalance
}

class ConnectedAPIHandler(
    private val networkConfig: NetworkConfig,
    val walletAddresses: WalletAddresses,
    private val balanceProvider: BalanceProvider? = null,
    private val signDataFn: (suspend (String, SignDataOptions) -> SignatureResult)? = null,
    private val submitTransactionFn: (suspend (String) -> Unit)? = null,
    private val makeTransferFn: (suspend (List<DesiredOutput>, Boolean) -> String)? = null,
    private val balanceUnsealedFn: (suspend (String, Boolean) -> String)? = null,
    private val balanceSealedFn: (suspend (String, Boolean) -> String)? = null,
    private val makeIntentFn: (suspend (List<DesiredInput>, List<DesiredOutput>, IntentOptions) -> String)? = null,
    private val getTxHistoryFn: (suspend (Int, Int) -> List<HistoryEntry>)? = null,
) {
    companion object {
        private const val TAG = "ConnectedAPI"
    }

    // ── Read Methods (auto-approved) ──

    fun getConfiguration(): Configuration {
        return Configuration(
            indexerUri = networkConfig.indexerBaseUrl + "/graphql",
            indexerWsUri = networkConfig.indexerBaseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://") + "/graphql/ws",
            substrateNodeUri = networkConfig.nodeRpcUrl,
            networkId = networkConfig.network.name.lowercase(),
        )
    }

    fun getConnectionStatus(): ConnectionStatus {
        return ConnectionStatus.Connected(
            networkId = networkConfig.network.name.lowercase()
        )
    }

    fun hintUsage(methodNames: List<String>) {
        Log.d(TAG, "DApp hints usage of: ${methodNames.joinToString()}")
    }

    // ── Remaining read methods (to be implemented) ──

    suspend fun getUnshieldedBalances(): Map<TokenType, BigInteger> {
        return balanceProvider?.getUnshieldedBalances() ?: emptyMap()
    }

    suspend fun getShieldedBalances(): Map<TokenType, BigInteger> {
        return balanceProvider?.getShieldedBalances() ?: emptyMap()
    }

    suspend fun getDustBalance(): DustBalance {
        return balanceProvider?.getDustBalance() ?: DustBalance(BigInteger.ZERO, BigInteger.ZERO)
    }

    fun getUnshieldedAddress(): UnshieldedAddressResult {
        return UnshieldedAddressResult(walletAddresses.unshieldedAddress)
    }

    fun getShieldedAddresses(): ShieldedAddressesResult {
        return ShieldedAddressesResult(
            shieldedAddress = walletAddresses.shieldedAddress,
            shieldedCoinPublicKey = walletAddresses.shieldedCoinPublicKey,
            shieldedEncryptionPublicKey = walletAddresses.shieldedEncryptionPublicKey,
        )
    }

    fun getDustAddress(): DustAddressResult {
        return DustAddressResult(walletAddresses.dustAddress)
    }

    suspend fun getTxHistory(pageNumber: Int, pageSize: Int): List<HistoryEntry> {
        return getTxHistoryFn?.invoke(pageNumber, pageSize) ?: emptyList()
    }

    // ── Write Methods (require approval) ──

    suspend fun makeTransfer(
        desiredOutputs: List<DesiredOutput>,
        payFees: Boolean = true,
    ): String {
        val fn = makeTransferFn ?: throw ConnectorApiError(
            ErrorCodes.INTERNAL_ERROR, "Transfer not configured"
        )
        return fn(desiredOutputs, payFees)
    }

    suspend fun submitTransaction(tx: String) {
        val fn = submitTransactionFn ?: throw ConnectorApiError(
            ErrorCodes.INTERNAL_ERROR, "Transaction submission not configured"
        )
        fn(tx)
    }

    suspend fun balanceUnsealedTransaction(
        tx: String,
        payFees: Boolean = true,
    ): String {
        val fn = balanceUnsealedFn ?: throw ConnectorApiError(
            ErrorCodes.INTERNAL_ERROR, "Balance unsealed not configured"
        )
        return fn(tx, payFees)
    }

    suspend fun balanceSealedTransaction(
        tx: String,
        payFees: Boolean = true,
    ): String {
        val fn = balanceSealedFn ?: throw ConnectorApiError(
            ErrorCodes.INTERNAL_ERROR, "Balance sealed not configured"
        )
        return fn(tx, payFees)
    }

    suspend fun makeIntent(
        desiredInputs: List<DesiredInput>,
        desiredOutputs: List<DesiredOutput>,
        options: IntentOptions,
    ): String {
        val fn = makeIntentFn ?: throw ConnectorApiError(
            ErrorCodes.INTERNAL_ERROR, "Intent creation not configured"
        )
        return fn(desiredInputs, desiredOutputs, options)
    }

    suspend fun signData(data: String, options: SignDataOptions): SignatureResult {
        val fn = signDataFn ?: throw ConnectorApiError(
            ErrorCodes.INTERNAL_ERROR, "Signing not configured"
        )
        return fn(data, options)
    }

    fun getProvingProvider(): ProvingProviderResult {
        // Local proving (Phase 4C) — no external prover server needed
        return ProvingProviderResult(proverServerUri = null)
    }
}

// Result types for address methods
data class DustBalance(val cap: BigInteger, val balance: BigInteger)
data class UnshieldedAddressResult(val unshieldedAddress: String)
data class ShieldedAddressesResult(
    val shieldedAddress: String,
    val shieldedCoinPublicKey: String,
    val shieldedEncryptionPublicKey: String,
)
data class DustAddressResult(val dustAddress: String)
data class ProvingProviderResult(val proverServerUri: String?)
