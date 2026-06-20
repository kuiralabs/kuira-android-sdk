package com.midnight.kuira.core.indexer.api

/**
 * GraphQL queries and subscriptions for Midnight Indexer API.
 *
 * Centralized location for all GraphQL operations.
 * Makes it easy to:
 * - Find and update queries
 * - Test query syntax
 * - Compare with Midnight's TypeScript SDK
 */
object GraphQLQueries {

    /**
     * Subscribe to unshielded transactions for an address.
     *
     * Variables:
     * - address: UnshieldedAddress! (required)
     * - transactionId: Int (optional, start from this tx)
     */
    const val SUBSCRIBE_UNSHIELDED_TRANSACTIONS = """
        subscription UnshieldedTransactions(${'$'}address: UnshieldedAddress!, ${'$'}transactionId: Int) {
          unshieldedTransactions(address: ${'$'}address, transactionId: ${'$'}transactionId) {
            __typename
            ... on UnshieldedTransaction {
              type: __typename
              transaction {
                id
                hash
                type: __typename
                protocolVersion
                block {
                  timestamp
                }
                ... on RegularTransaction {
                  identifiers
                  fees {
                    paidFees
                    estimatedFees
                  }
                  transactionResult {
                    status
                    segments {
                      id
                      success
                    }
                  }
                }
              }
              createdUtxos {
                value
                owner
                tokenType
                intentHash
                outputIndex
                ctime
                registeredForDustGeneration
              }
              spentUtxos {
                value
                owner
                tokenType
                intentHash
                outputIndex
                ctime
                registeredForDustGeneration
              }
            }
            ... on UnshieldedTransactionsProgress {
              type: __typename
              highestTransactionId
            }
          }
        }
    """

    /**
     * Subscribe to blocks.
     */
    const val SUBSCRIBE_BLOCKS = """
        subscription {
          blocks {
            height
            hash
            timestamp
          }
        }
    """

    /**
     * Query network state.
     */
    const val QUERY_NETWORK_STATE = """
        query {
          networkState {
            currentBlock
            maxBlock
          }
        }
    """

    /**
     * Query current block with ledger parameters.
     */
    const val QUERY_CURRENT_BLOCK = """
        query {
          block {
            height
            hash
            ledgerParameters
            timestamp
          }
        }
    """

    /**
     * Query the GENESIS block (height 0). Its hash is a stable per-chain identity: a fresh
     * localnet (after a `docker` reset) has a different genesis hash, so a mismatch against the
     * pinned value flags a chain reset. The `block` field takes an optional `offset`;
     * `{ height: 0 }` pins it to genesis (no offset = latest block).
     */
    const val QUERY_GENESIS_BLOCK = """
        query {
          block(offset: { height: 0 }) {
            height
            hash
          }
        }
    """

    /**
     * Query zswap ledger events in range.
     *
     * Variables:
     * - fromId: Long!
     * - toId: Long!
     */
    const val QUERY_ZSWAP_EVENTS = """
        query(${'$'}fromId: Long!, ${'$'}toId: Long!) {
          zswapLedgerEvents(fromId: ${'$'}fromId, toId: ${'$'}toId) {
            id
            raw
            maxId
          }
        }
    """

    /**
     * Subscribe to dust ledger events.
     *
     * Streams ALL dust events from the given ID (global, not filtered by address).
     * DustLocalState.replayEvents() filters internally using the dust seed.
     *
     * Variables:
     * - id: Int (optional, event cursor — pass last processed ID to resume)
     */
    const val SUBSCRIBE_DUST_LEDGER_EVENTS = """
        subscription DustLedgerEvents(${'$'}id: Int) {
          dustLedgerEvents(id: ${'$'}id) {
            id
            raw
            maxId
          }
        }
    """

    /**
     * Subscribe to zswap (shielded) ledger events.
     *
     * Streams ALL zswap events from the given ID (global, not filtered by address).
     * ZswapLocalState.replayEvents() decrypts internally using the zswap secret keys.
     *
     * Variables:
     * - id: Int (optional, event cursor — pass last processed ID to resume)
     */
    const val SUBSCRIBE_ZSWAP_LEDGER_EVENTS = """
        subscription ZswapLedgerEvents(${'$'}id: Int) {
          zswapLedgerEvents(id: ${'$'}id) {
            id
            raw
            maxId
          }
        }
    """

    /**
     * Query the current state of a deployed contract.
     *
     * Returns the SCALE-encoded contract state as hex.
     * Used by dApps to fetch on-chain state before circuit execution.
     *
     * Variables:
     * - address: HexEncoded! (required, 64-char contract address)
     */
    const val QUERY_CONTRACT_STATE = """
        query ContractState(${'$'}address: HexEncoded!) {
          contractAction(address: ${'$'}address) {
            state
          }
        }
    """
}
