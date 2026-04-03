package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.ConnectorApiError
import com.midnight.kuira.core.connector.model.ErrorCodes
import com.midnight.kuira.core.network.NetworkConfig

/**
 * Initial API — the connection entry point for dApps.
 *
 * DApps call [connect] with a network ID to establish a session
 * and receive a [ConnectedAPIHandler] for wallet operations.
 *
 * Matches `InitialAPI` from @midnight-ntwrk/dapp-connector-api.
 */
class InitialAPI(
    private val networkConfig: NetworkConfig,
    private val handlerFactory: (String) -> ConnectedAPIHandler,
) {
    /** Wallet identifier in reverse DNS notation. */
    val rdns: String = "com.kuira.wallet"

    /** Wallet name for display. */
    val name: String = "Kuira"

    /** API version — matches @midnight-ntwrk/dapp-connector-api package version. */
    val apiVersion: String = API_VERSION

    /**
     * Connect to wallet for a specific network.
     *
     * @param networkId Network to connect to ("undeployed", "preprod", "mainnet", etc.)
     * @return ConnectedAPIHandler for wallet operations
     * @throws ConnectorApiError if network ID doesn't match wallet's configured network
     */
    fun connect(networkId: String): ConnectedAPIHandler {
        val configuredNetwork = networkConfig.network.name.lowercase()
        if (networkId != configuredNetwork) {
            throw ConnectorApiError(
                code = ErrorCodes.INVALID_REQUEST,
                message = "Network mismatch: requested '$networkId' but wallet is on '$configuredNetwork'"
            )
        }
        return handlerFactory(networkId)
    }

    companion object {
        const val API_VERSION = "4.0.1"
    }
}
