package com.midnight.kuira.core.ledger.di

import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.NodeRpcClientImpl
import com.midnight.kuira.core.ledger.api.ProofServerClient
import com.midnight.kuira.core.ledger.api.ProofServerClientImpl
import com.midnight.kuira.core.ledger.api.TransactionSerializer
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.ledger.fee.DustActionsBuilder
import com.midnight.kuira.core.ledger.fee.DustSpendCreator
import com.midnight.kuira.core.ledger.fee.FeeCalculator
import com.midnight.kuira.core.crypto.proving.ProvingKeyManager
import com.midnight.kuira.core.network.NetworkConfig
import com.midnight.kuira.core.network.NetworkRepository
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Ledger component dependencies.
 *
 * **Provided Dependencies:**
 * - NodeRpcClient: HTTP client for Midnight node JSON-RPC API
 * - ProofServerClient: HTTP client for Midnight proof server (Phase 2)
 * - TransactionSerializer: SCALE serialization using Rust FFI
 * - TransactionSubmitter: Transaction submission orchestrator
 * - FeeCalculator: Calculates transaction fees
 * - DustSpendCreator: Creates dust spend actions
 *
 * **Note:**
 * DustActionsBuilder is auto-provided by Hilt via @Inject constructor
 */
@Module
@InstallIn(SingletonComponent::class)
object LedgerModule {

    /**
     * Provide NodeRpcClient singleton.
     *
     * **Singleton Scope:** HTTP client is expensive to create, shared across app.
     *
     * **Configuration:**
     * - Uses NetworkConfig for URL and development mode settings
     * - URLs are read from persisted network selection at startup
     * - Changing networks requires app restart
     */
    @Provides
    @Singleton
    fun provideNodeRpcClient(
        networkConfig: NetworkConfig
    ): NodeRpcClient {
        return NodeRpcClientImpl(
            nodeUrl = networkConfig.nodeRpcUrl,
            developmentMode = networkConfig.developmentMode
        )
    }

    /**
     * Provide ProofServerClient singleton.
     *
     * **Singleton Scope:** HTTP client is expensive to create, shared across app.
     *
     * **Configuration:**
     * - Uses NetworkConfig for URL and development mode settings
     * - Currently all networks use local proof server (localhost:6300)
     * - URLs are read from persisted network selection at startup
     * - Changing networks requires app restart
     */
    @Provides
    @Singleton
    fun provideProofServerClient(
        networkConfig: NetworkConfig
    ): ProofServerClient {
        return ProofServerClientImpl(
            proofServerUrl = networkConfig.proofServerUrl,
            developmentMode = networkConfig.developmentMode
        )
    }

    /**
     * Provide TransactionSerializer singleton.
     *
     * **Singleton Scope:** Stateless serializer, safe to share.
     *
     * **Implementation:** FfiTransactionSerializer uses Rust midnight-ledger
     * for SCALE encoding via JNI.
     */
    @Provides
    @Singleton
    fun provideTransactionSerializer(
        networkConfig: NetworkConfig,
    ): TransactionSerializer {
        return FfiTransactionSerializer(
            networkId = networkConfig.network.rustNetworkId,
        )
    }

    /**
     * Provide FeeCalculator object.
     *
     * **Note:** FeeCalculator is a Kotlin object, but we need to provide it
     * explicitly for Hilt dependency injection.
     */
    @Provides
    @Singleton
    fun provideFeeCalculator(): FeeCalculator = FeeCalculator

    /**
     * Provide DustSpendCreator object.
     *
     * **Note:** DustSpendCreator is a Kotlin object, but we need to provide it
     * explicitly for Hilt dependency injection.
     */
    @Provides
    @Singleton
    fun provideDustSpendCreator(): DustSpendCreator = DustSpendCreator

    /** Provide ProvingKeyManager for local ZK proving (Phase 4C). */
    @Provides
    @Singleton
    fun provideProvingKeyManager(@ApplicationContext context: Context): ProvingKeyManager {
        return ProvingKeyManager(context)
    }

    /**
     * Provide TransactionSubmitter singleton with network-aware client resolution.
     * The [NetworkClientProvider] creates fresh clients for the currently-selected
     * network on each transaction, so network switches in Settings take effect
     * without app restart.
     */
    @Provides
    @Singleton
    fun provideTransactionSubmitter(
        nodeRpcClient: NodeRpcClient,
        proofServerClient: ProofServerClient,
        indexerClient: IndexerClient,
        serializer: TransactionSerializer,
        utxoManager: UtxoManager,
        dustActionsBuilder: DustActionsBuilder,
        dustRepository: DustRepository,
        provingKeyManager: ProvingKeyManager,
        networkRepository: NetworkRepository,
    ): TransactionSubmitter {
        return TransactionSubmitter(
            nodeRpcClient = nodeRpcClient,
            proofServerClient = proofServerClient,
            indexerClient = indexerClient,
            serializer = serializer,
            utxoManager = utxoManager,
            dustActionsBuilder = dustActionsBuilder,
            dustRepository = dustRepository,
            provingKeyManager = provingKeyManager,
            networkClientProvider = TransactionSubmitter.NetworkClientProvider {
                val network = networkRepository.getSelectedNetworkBlocking()
                val config = NetworkConfig.forNetwork(network)
                TransactionSubmitter.ResolvedClients(
                    networkId = network.name,
                    node = NodeRpcClientImpl(nodeUrl = config.nodeRpcUrl, developmentMode = config.developmentMode),
                    proofServer = ProofServerClientImpl(proofServerUrl = config.proofServerUrl, developmentMode = config.developmentMode),
                    indexer = IndexerClientImpl(baseUrl = config.indexerBaseUrl, developmentMode = config.developmentMode),
                )
            },
        )
    }
}
