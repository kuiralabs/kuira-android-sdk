package com.midnight.kuira.core.indexer.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.withTransaction
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.sync.SubscriptionManager
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.network.NetworkConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for dust state DataStore.
 * Distinguishes it from other DataStore instances.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DustStateDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ShieldedStateDataStore

private val Context.dustStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dust_state"
)

private val Context.shieldedStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shielded_state"
)

/**
 * Hilt module for Indexer component dependencies.
 *
 * **Provided Dependencies:**
 * - IndexerClient: WebSocket client for Midnight Indexer API
 * - UtxoDatabase: Room database for UTXO storage
 * - UtxoManager: UTXO processing and balance calculation
 * - BalanceRepository: Public API for balance queries
 * - SyncStateManager: Sync progress persistence
 * - SubscriptionManagerFactory: Factory for creating SubscriptionManager instances
 *
 * **Note on SubscriptionManager:**
 * SubscriptionManager is NOT a singleton because each instance should manage
 * a single address subscription. Use SubscriptionManagerFactory to create instances.
 */
@Module
@InstallIn(SingletonComponent::class)
object IndexerModule {

    /**
     * Provide IndexerClient singleton.
     *
     * **Singleton Scope:** Expensive to create (WebSocket connection), shared across app.
     *
     * **Configuration:**
     * - Uses NetworkConfig for URL and development mode settings
     * - URLs are read from persisted network selection at startup
     * - Changing networks requires app restart
     */
    @Provides
    @Singleton
    fun provideIndexerClient(
        networkConfig: NetworkConfig
    ): IndexerClient {
        return IndexerClientImpl(
            baseUrl = networkConfig.indexerBaseUrl,
            developmentMode = networkConfig.developmentMode
        )
    }

    /**
     * Provide UtxoDatabase singleton.
     *
     * **Singleton Scope:** Room database should be singleton to avoid multiple instances.
     *
     * **Database Name:** "utxo_database" (persistent storage)
     */
    @Provides
    @Singleton
    fun provideUtxoDatabase(
        @ApplicationContext context: Context
    ): UtxoDatabase {
        return Room.databaseBuilder(
            context,
            UtxoDatabase::class.java,
            "utxo_database"
        ).build()
    }

    /**
     * Provide UtxoManager singleton.
     *
     * **Singleton Scope:** Single instance manages all UTXOs for all addresses.
     */
    @Provides
    @Singleton
    fun provideUtxoManager(database: UtxoDatabase): UtxoManager {
        return UtxoManager(
            utxoDao = database.unshieldedUtxoDao(),
            inTransaction = { block -> database.withTransaction { block() } },
        )
    }

    /**
     * Provide DustDao from database.
     *
     * **Purpose:** For DustRepository to access dust token database.
     */
    @Provides
    @Singleton
    fun provideDustDao(database: UtxoDatabase) = database.dustDao()

    /**
     * Provide BalanceRepository singleton.
     *
     * **Singleton Scope:** Repository layer is stateless, safe to share.
     */
    @Provides
    @Singleton
    fun provideBalanceRepository(
        utxoManager: UtxoManager,
        indexerClient: IndexerClient
    ): BalanceRepository {
        return BalanceRepository(utxoManager, indexerClient)
    }

    /**
     * Provide SyncStateManager singleton.
     *
     * **Singleton Scope:** Manages sync state for ALL addresses, uses shared DataStore.
     */
    @Provides
    @Singleton
    fun provideSyncStateManager(
        @ApplicationContext context: Context
    ): SyncStateManager {
        return SyncStateManager(context)
    }

    /**
     * Provide DataStore for dust state persistence.
     *
     * **Singleton Scope:** Shared DataStore instance for all dust state operations.
     *
     * **Qualifier:** @DustStateDataStore distinguishes from other DataStore instances.
     */
    @Provides
    @Singleton
    @DustStateDataStore
    fun provideDustStateDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.dustStateDataStore
    }

    @Provides
    @Singleton
    @ShieldedStateDataStore
    fun provideShieldedStateDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.shieldedStateDataStore
    }

    /**
     * Provide SubscriptionManagerFactory.
     *
     * **Why Factory:** SubscriptionManager should NOT be singleton because:
     * - Each instance manages subscription for ONE address
     * - Multiple addresses = multiple SubscriptionManager instances
     * - Lifecycle tied to ViewModel/screen scope, not app scope
     *
     * **Usage in ViewModel:**
     * ```kotlin
     * @HiltViewModel
     * class BalanceViewModel @Inject constructor(
     *     private val subscriptionManagerFactory: SubscriptionManagerFactory
     * ) : ViewModel() {
     *
     *     fun syncBalance(address: String) {
     *         viewModelScope.launch {
     *             subscriptionManagerFactory.create()
     *                 .startSubscription(address)
     *                 .collect { state -> ... }
     *         }
     *     }
     * }
     * ```
     */
    @Provides
    fun provideSubscriptionManagerFactory(
        @ApplicationContext context: Context,
        indexerClient: IndexerClient,
        utxoManager: UtxoManager,
        syncStateManager: SyncStateManager,
        networkRepository: com.midnight.kuira.core.network.NetworkRepository,
    ): SubscriptionManagerFactory {
        return SubscriptionManagerFactory(context, indexerClient, utxoManager, syncStateManager, networkRepository)
    }
}

/**
 * Factory for creating SubscriptionManager instances.
 *
 * Network-aware: creates a fresh [IndexerClient] for the currently-selected
 * network so subscriptions connect to the correct indexer after a network
 * switch in Settings. Falls back to the injected singleton client when the
 * network hasn't changed.
 */
class SubscriptionManagerFactory(
    private val context: Context,
    private val defaultIndexerClient: IndexerClient,
    private val utxoManager: UtxoManager,
    private val syncStateManager: SyncStateManager,
    private val networkRepository: com.midnight.kuira.core.network.NetworkRepository? = null,
) {
    /**
     * Create a new SubscriptionManager for the currently-selected network.
     *
     * Each instance should manage subscription for ONE address.
     * Collect the subscription flow in a ViewModel-scoped coroutine.
     */
    fun create(): SubscriptionManager {
        val client = resolveIndexerClient()
        return SubscriptionManager(context, client, utxoManager, syncStateManager)
    }

    private fun resolveIndexerClient(): IndexerClient {
        val repo = networkRepository ?: return defaultIndexerClient
        val network = repo.getSelectedNetworkBlocking()
        val config = com.midnight.kuira.core.network.NetworkConfig.forNetwork(network)
        return com.midnight.kuira.core.indexer.api.IndexerClientImpl(
            baseUrl = config.indexerBaseUrl,
            developmentMode = config.developmentMode,
        )
    }
}
