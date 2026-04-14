package com.midnight.kuira.core.network

/**
 * Midnight network environments.
 *
 * **Networks:**
 * - PREPROD: Pre-production testnet (uses remote services)
 * - PREVIEW: Preview testnet (uses remote services)
 * - UNDEPLOYED: Local development (Docker compose, all local services)
 *
 * **Default:** PREPROD (matches wallet.json in kuira-verification-test)
 */
enum class MidnightNetwork(
    val displayName: String,
    val addressPrefix: String,
    val shieldedAddressPrefix: String,
    /**
     * Network identifier embedded in every Transaction we build. The node compares
     * this string byte-for-byte against its own `LedgerState.network_id` during
     * `well_formed()` verification — mismatch = `MalformedError::InvalidNetworkId`.
     *
     * Derived from the node's `MidnightNetwork::network_id()` helper
     * (midnight-node/res/src/networks/mod.rs:191): strips a leading "midnight_"
     * from the chain id, or maps "midnight" → "mainnet".
     */
    val rustNetworkId: String,
) {
    PREPROD("Preprod", "mn_addr_preprod", "mn_shield-addr_preprod", "preprod"),
    PREVIEW("Preview", "mn_addr_preview", "mn_shield-addr_preview", "preview"),
    UNDEPLOYED("Undeployed", "mn_addr_undeployed", "mn_shield-addr_undeployed", "undeployed");

    companion object {
        val DEFAULT = UNDEPLOYED

        fun fromName(name: String): MidnightNetwork {
            return entries.find { it.name == name } ?: DEFAULT
        }
    }
}
