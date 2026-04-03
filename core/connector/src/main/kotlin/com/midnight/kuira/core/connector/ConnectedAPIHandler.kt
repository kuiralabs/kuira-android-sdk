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
    private val walletAddresses: WalletAddresses,
    private val balanceProvider: BalanceProvider? = null,
    // More dependencies added as methods are implemented
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
        TODO("Implement with Room DB")
    }

    // ── Write Methods (require approval) ──

    suspend fun makeTransfer(
        desiredOutputs: List<DesiredOutput>,
        payFees: Boolean = true,
    ): String {
        TODO("Implement with ZswapTransferBuilder + TransactionSubmitter")
    }

    suspend fun submitTransaction(tx: String) {
        TODO("Implement with TransactionSubmitter")
    }

    suspend fun balanceUnsealedTransaction(
        tx: String,
        payFees: Boolean = true,
    ): String {
        TODO("Implement with Composable FFI (ADR-001)")
    }

    suspend fun balanceSealedTransaction(
        tx: String,
        payFees: Boolean = true,
    ): String {
        TODO("Implement with Composable FFI (ADR-001)")
    }

    suspend fun makeIntent(
        desiredInputs: List<DesiredInput>,
        desiredOutputs: List<DesiredOutput>,
        options: IntentOptions,
    ): String {
        TODO("Implement with Composable FFI (ADR-001)")
    }

    suspend fun signData(data: String, options: SignDataOptions): SignatureResult {
        TODO("Implement with TransactionSigner")
    }

    fun getProvingProvider(): ProvingProviderResult {
        TODO("Implement with LocalProver (Phase 4C)")
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
