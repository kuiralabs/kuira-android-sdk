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
    /**
     * This config's URLs with any Blockfrost `project_id` masked. The credential is IN the URL,
     * so this is the only form safe to log.
     */
    fun redacted(): NetworkConfig = copy(
        indexerBaseUrl = redactProjectId(indexerBaseUrl),
        nodeRpcUrl = redactProjectId(nodeRpcUrl),
    )

    /**
     * True only for the LOCAL dev chain (localnet / [MidnightNetwork.UNDEPLOYED]) — the one place a
     * chain RESET is a routine event and wiping the wallet's caches on detection is safe.
     *
     * This is DELIBERATELY separate from [developmentMode]: `developmentMode` is true on PreProd and
     * Preview too (it only means "allow the local proof server over HTTP"), so it must NOT be used to
     * gate destructive actions. Wiring a wallet-wipe to `developmentMode` would let an anomalous
     * remote-indexer reading nuke a healthy PreProd wallet. See
     * [com.midnight.kuira.core.indexer.sync.ChainResetGuard].
     */
    val isLocalDevChain: Boolean get() = network == MidnightNetwork.UNDEPLOYED

    companion object {

        /**
         * Path component of every Midnight indexer GraphQL endpoint, including
         * the API version segment. Single source of truth — when the indexer
         * cuts a new major (v5, v6, …) this constant is the only line that
         * changes; every per-network URL composes from it.
         *
         * The path includes the leading `/` so callers can append it directly
         * to a host: `"https://indexer.preview.midnight.network$INDEXER_API_PATH"`.
         *
         * Preview, not PreProd, in that example on purpose: PreProd no longer uses this path at
         * all. Its indexer moved to Blockfrost, which serves [BLOCKFROST_API_PATH] — see the
         * Blockfrost block below.
         *
         * Background: `/api/v3` is an alias to `/api/v4` in the indexer source
         * (see midnight-indexer/indexer-api/src/infra/api.rs), so a path bump
         * is cosmetic from a behavior standpoint — but centralizing it here
         * means we never have to sed across the repo again.
         */
        const val INDEXER_API_PATH = "/api/v4"

        // PreProd's indexer was decommissioned 2026-10-09; Blockfrost serves it now.

        /** Blockfrost host serving PreProd's indexer and node. */
        const val BLOCKFROST_PREPROD_HOST = "midnight-preprod.blockfrost.io"

        /** Blockfrost's API path — NOT [INDEXER_API_PATH]; the path changed with the host. */
        const val BLOCKFROST_API_PATH = "/api/v0"

        /** Blockfrost's subscription path. The Midnight indexer uses `/graphql/ws`; Blockfrost does not. */
        const val BLOCKFROST_WS_PATH = "/ws"

        /** Blockfrost authenticates with a query parameter, not a header. */
        const val BLOCKFROST_PROJECT_ID_PARAM = "project_id"

        /** Blockfrost scopes an id to one network; a foreign one is rejected as an opaque 403. */
        const val BLOCKFROST_PREPROD_ID_PREFIX = "nightpreprod"

        /** Where a developer gets an id, named in every error about a missing one. */
        const val BLOCKFROST_SITE = "https://blockfrost.io"

        /** Manifest `<meta-data>` key the SDK reads a project id from. */
        const val BLOCKFROST_PROJECT_ID_MANIFEST_KEY = "io.github.kuiralabs.BLOCKFROST_PROJECT_ID"

        /** True when this network's indexer is Blockfrost-served and so requires a project id. */
        fun requiresBlockfrostProjectId(network: MidnightNetwork): Boolean =
            network == MidnightNetwork.PREPROD

        /**
         * Append [path] to [url] BEFORE any query string — PreProd's base carries
         * `?project_id=<id>`, so plain concatenation would bury the path in the credential.
         */
        fun appendPath(url: String, path: String): String {
            val queryAt = url.indexOf('?')
            return if (queryAt < 0) url + path
            else url.substring(0, queryAt) + path + url.substring(queryAt)
        }

        /** Mask a `project_id` value. Safe on any string, with or without one. */
        fun redactProjectId(url: String): String =
            url.replace(Regex("($BLOCKFROST_PROJECT_ID_PARAM=)[^&\\s]+"), "$1…")

        /**
         * Reject an id that cannot work on PreProd, before it becomes a 403 from inside a sync.
         *
         * @throws IllegalArgumentException naming what is wrong and how to fix it.
         */
        fun validateBlockfrostProjectId(projectId: String) {
            require(projectId.isNotBlank()) {
                "Blockfrost project id is blank. PreProd's indexer is served by Blockfrost and " +
                    "will not answer without one — get a PreProd project id at $BLOCKFROST_SITE."
            }
            require(projectId.startsWith(BLOCKFROST_PREPROD_ID_PREFIX)) {
                "Blockfrost project id '${projectId.take(BLOCKFROST_PREPROD_ID_PREFIX.length)}…' " +
                    "is not a PreProd id — those start with '$BLOCKFROST_PREPROD_ID_PREFIX'. An id " +
                    "for another network is rejected by Blockfrost as an opaque 403. Create a " +
                    "PreProd project at $BLOCKFROST_SITE."
            }
        }

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
         * | Preprod | Blockfrost (HTTPS, needs project id) | Remote (WSS) | Local |
         * | Preview | Remote (HTTPS) | Remote (WSS) | Local |
         * | Undeployed | Local (HTTP) | Local (WS) | Local |
         *
         * Localhost endpoints use [DeviceType.localhostHost] which resolves to
         * `10.0.2.2` on the emulator and `127.0.0.1` on a physical device.
         *
         * **PreProd requires [blockfrostProjectId].** Its own indexer was decommissioned on
         * 2026-10-09 and the replacement authenticates on every request, so a PreProd config
         * without one cannot read the chain at all. Rather than build a config that fails later
         * as a 410 or 403 from inside a sync, this throws here, where the message can say what is
         * missing and where to get it. Preview and Undeployed ignore the parameter.
         *
         * @throws IllegalArgumentException for PreProd with a missing or non-PreProd id.
         */
        @JvmOverloads
        fun forNetwork(network: MidnightNetwork, blockfrostProjectId: String? = null): NetworkConfig {
            val host = DeviceType.localhostHost
            val proofServerUrl = "http://$host:$LOCALNET_PROOF_SERVER_PORT"

            return when (network) {
                MidnightNetwork.PREPROD -> {
                    requireNotNull(blockfrostProjectId) {
                        "PreProd needs a Blockfrost project id and none was supplied, so it will " +
                            "not be functional.\n" +
                            "  indexer.preprod.midnight.network was decommissioned on 2026-10-09 " +
                            "(it answers HTTP 410) and PreProd's indexer is now served by " +
                            "Blockfrost, which authenticates every request.\n" +
                            "  Get a PreProd project id at $BLOCKFROST_SITE (it starts with " +
                            "'$BLOCKFROST_PREPROD_ID_PREFIX'), then put it in your app's " +
                            "local.properties as kuira.blockfrost.projectId=<id> — it is read " +
                            "from the '$BLOCKFROST_PROJECT_ID_MANIFEST_KEY' manifest meta-data, " +
                            "or pass it to MidnightSdk.Builder.blockfrostProjectId(...).\n" +
                            "  Run ./gradlew :app:kuiraDoctor to check your setup."
                    }
                    validateBlockfrostProjectId(blockfrostProjectId)
                    // The project id rides in the QUERY STRING, so these URLs are credentials.
                    // Never log them raw — use redacted(). The node keeps its own host: only the
                    // indexer moved to Blockfrost, which is why a node health probe still passes
                    // while every chain read fails.
                    val auth = "?$BLOCKFROST_PROJECT_ID_PARAM=$blockfrostProjectId"
                    NetworkConfig(
                        network = network,
                        indexerBaseUrl = "https://$BLOCKFROST_PREPROD_HOST$BLOCKFROST_API_PATH$auth",
                        nodeRpcUrl = "wss://rpc.preprod.midnight.network",
                        proofServerUrl = proofServerUrl,
                        developmentMode = true // Allow local proof server HTTP
                    )
                }

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
