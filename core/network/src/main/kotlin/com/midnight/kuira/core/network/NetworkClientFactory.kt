package com.midnight.kuira.core.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates [NetworkConfig] instances for any [MidnightNetwork] on demand.
 * The entry point for reactive network switching — consumers use this
 * in a `flatMapLatest` on [NetworkRepository.selectedNetworkFlow] to
 * create fresh clients when the network changes.
 *
 * ```kotlin
 * networkRepository.selectedNetworkFlow
 *     .flatMapLatest { network ->
 *         val config = networkClientFactory.configFor(network)
 *         val indexer = IndexerClientImpl(config.indexerBaseUrl, developmentMode = config.developmentMode)
 *         // start subscriptions, return Flow
 *     }
 * ```
 *
 * The factory is @Singleton (stateless). Clients created from configs
 * are NOT singletons — they're scoped to the flatMapLatest block and
 * closed when the network changes.
 */
@Singleton
class NetworkClientFactory @Inject constructor() {

    /** Returns a [NetworkConfig] with the correct URLs for [network]. */
    fun configFor(network: MidnightNetwork): NetworkConfig =
        NetworkConfig.forNetwork(network)
}
