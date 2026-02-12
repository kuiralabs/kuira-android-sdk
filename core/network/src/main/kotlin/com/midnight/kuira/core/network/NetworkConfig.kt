package com.midnight.kuira.core.network

/**
 * Configuration for a Midnight network environment.
 *
 * **URL Conventions:**
 * - indexerBaseUrl: Base URL for indexer API (without /graphql suffix)
 *   - IndexerClientImpl appends /graphql for HTTP and /graphql/ws for WebSocket
 * - nodeRpcUrl: Full URL for node JSON-RPC endpoint
 * - proofServerUrl: Full URL for proof server (appends /prove-tx internally)
 *
 * **Android Emulator Note:**
 * - 10.0.2.2 maps to host machine's localhost
 * - Physical devices need actual IP addresses
 *
 * @property network The network this configuration is for
 * @property indexerBaseUrl Indexer API base URL (e.g., "https://indexer.preprod.midnight.network/api/v3")
 * @property nodeRpcUrl Node RPC URL (e.g., "wss://rpc.preprod.midnight.network")
 * @property proofServerUrl Proof server URL (e.g., "http://10.0.2.2:6300")
 * @property developmentMode If true, allows HTTP connections (for local testing only)
 */
data class NetworkConfig(
    val network: MidnightNetwork,
    val indexerBaseUrl: String,
    val nodeRpcUrl: String,
    val proofServerUrl: String,
    val developmentMode: Boolean
) {
    companion object {
        /**
         * Get configuration for a specific network.
         *
         * **Network Endpoints:**
         *
         * | Network | Indexer | Node RPC | Proof Server |
         * |---------|---------|----------|--------------|
         * | Preprod | Remote (HTTPS) | Remote (WSS) | Local |
         * | Preview | Remote (HTTPS) | Remote (WSS) | Local |
         * | Undeployed | Local (HTTP) | Local (WS) | Local |
         */
        fun forNetwork(network: MidnightNetwork): NetworkConfig {
            return when (network) {
                MidnightNetwork.PREPROD -> NetworkConfig(
                    network = network,
                    indexerBaseUrl = "https://indexer.preprod.midnight.network/api/v3",
                    nodeRpcUrl = "wss://rpc.preprod.midnight.network",
                    proofServerUrl = "http://10.0.2.2:6300",
                    developmentMode = true // Allow local proof server HTTP
                )

                MidnightNetwork.PREVIEW -> NetworkConfig(
                    network = network,
                    indexerBaseUrl = "https://indexer.preview.midnight.network/api/v3",
                    nodeRpcUrl = "wss://rpc.preview.midnight.network",
                    proofServerUrl = "http://10.0.2.2:6300",
                    developmentMode = true // Allow local proof server HTTP
                )

                MidnightNetwork.UNDEPLOYED -> NetworkConfig(
                    network = network,
                    indexerBaseUrl = "http://10.0.2.2:8088/api/v3",
                    nodeRpcUrl = "ws://10.0.2.2:9944",
                    proofServerUrl = "http://10.0.2.2:6300",
                    developmentMode = true // All local services
                )
            }
        }
    }
}
