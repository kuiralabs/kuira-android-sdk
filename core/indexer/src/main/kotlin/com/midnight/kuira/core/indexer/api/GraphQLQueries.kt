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
}
