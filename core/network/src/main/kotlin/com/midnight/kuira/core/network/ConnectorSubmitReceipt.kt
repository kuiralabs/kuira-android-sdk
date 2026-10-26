package com.midnight.kuira.core.network

/**
 * The `submitTransaction` answer a Kuira connector wallet sends back, shared by the wallet side
 * that writes it and the dApp side that reads it.
 *
 * Midnight's DApp Connector API answers `submitTransaction` with nothing, so a wallet that follows
 * it only promises the transaction was submitted. A Kuira wallet answers with this object instead.
 */
object ConnectorSubmitReceipt {
    const val TX_HASH = "transactionHash"

    /**
     * Where a wallet built on Midnight's wallet SDK (`mn serve` among them) puts its answer. Despite
     * the name it holds the transaction's identifier, not its hash.
     */
    const val WALLET_SDK_TX_IDENTIFIER = "txHash"
    const val STATUS = "status"
    const val BLOCK_HEIGHT = "blockHeight"

    const val STATUS_FINALIZED = "finalized"
    const val STATUS_PENDING = "pending"
}
