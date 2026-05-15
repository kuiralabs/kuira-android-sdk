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
 * **Localhost host resolution (see [DeviceType.localhostHost]):**
 * - Emulator: `10.0.2.2` (Android emulator's special host alias)
 * - Physical device: `127.0.0.1` (requires `adb reverse tcp:<port> tcp:<port>`
 *   for the indexer/node/proof-server ports). See `./gradlew adbReverseLocalnet`.
 *
 * The localnet (UNDEPLOYED) network uses this for all three services.
 * PREPROD and PREVIEW use it only for the local proof server.
 *
 * @property network The network this configuration is for
 * @property indexerBaseUrl Indexer API base URL
 * @property nodeRpcUrl Node RPC URL
 * @property proofServerUrl Proof server URL
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
         * Path component of every Midnight indexer GraphQL endpoint, including
         * the API version segment. Single source of truth — when the indexer
         * cuts a new major (v5, v6, …) this constant is the only line that
         * changes; every per-network URL composes from it.
         *
         * The path includes the leading `/` so callers can append it directly
         * to a host: `"https://indexer.preprod.midnight.network$INDEXER_API_PATH"`.
         *
         * Background: `/api/v3` is an alias to `/api/v4` in the indexer source
         * (see midnight-indexer/indexer-api/src/infra/api.rs), so a path bump
         * is cosmetic from a behavior standpoint — but centralizing it here
         * means we never have to sed across the repo again.
         */
        const val INDEXER_API_PATH = "/api/v4"

        // Localnet ports — keep in sync with the docker-compose / Gradle adbReverseLocalnet task
        const val LOCALNET_INDEXER_PORT = 8088
        const val LOCALNET_NODE_RPC_PORT = 9944
        const val LOCALNET_PROOF_SERVER_PORT = 6300

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
         *
         * Localhost endpoints use [DeviceType.localhostHost] which resolves to
         * `10.0.2.2` on the emulator and `127.0.0.1` on a physical device.
         */
        fun forNetwork(network: MidnightNetwork): NetworkConfig {
            val host = DeviceType.localhostHost
            val proofServerUrl = "http://$host:$LOCALNET_PROOF_SERVER_PORT"

            return when (network) {
                MidnightNetwork.PREPROD -> NetworkConfig(
                    network = network,
                    indexerBaseUrl = "https://indexer.preprod.midnight.network$INDEXER_API_PATH",
                    nodeRpcUrl = "wss://rpc.preprod.midnight.network",
                    proofServerUrl = proofServerUrl,
                    developmentMode = true // Allow local proof server HTTP
                )

                MidnightNetwork.PREVIEW -> NetworkConfig(
                    network = network,
                    indexerBaseUrl = "https://indexer.preview.midnight.network$INDEXER_API_PATH",
                    nodeRpcUrl = "wss://rpc.preview.midnight.network",
                    proofServerUrl = proofServerUrl,
                    developmentMode = true // Allow local proof server HTTP
                )

                MidnightNetwork.UNDEPLOYED -> NetworkConfig(
                    network = network,
                    indexerBaseUrl = "http://$host:$LOCALNET_INDEXER_PORT$INDEXER_API_PATH",
                    nodeRpcUrl = "ws://$host:$LOCALNET_NODE_RPC_PORT",
                    proofServerUrl = proofServerUrl,
                    developmentMode = true // All local services
                )
            }
        }
    }
}
