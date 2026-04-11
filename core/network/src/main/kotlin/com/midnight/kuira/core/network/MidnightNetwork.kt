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
) {
    PREPROD("Preprod", "mn_addr_preprod", "mn_shield-addr_preprod"),
    PREVIEW("Preview", "mn_addr_preview", "mn_shield-addr_preview"),
    UNDEPLOYED("Undeployed", "mn_addr_undeployed", "mn_shield-addr_undeployed");

    companion object {
        val DEFAULT = UNDEPLOYED

        fun fromName(name: String): MidnightNetwork {
            return entries.find { it.name == name } ?: DEFAULT
        }
    }
}
