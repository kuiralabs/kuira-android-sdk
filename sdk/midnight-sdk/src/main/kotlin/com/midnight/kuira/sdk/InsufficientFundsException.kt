package com.midnight.kuira.sdk

/**
 * Thrown when the wallet's unshielded NIGHT can't cover a requested amount — e.g. funding
 * the value a contract receives via `receiveUnshielded` in [MidnightSdk.buildUnshieldedFundingJson].
 *
 * Sibling of [InsufficientDustException] (which covers the fee token, not the transfer value).
 */
class InsufficientFundsException(message: String) : IllegalStateException(message)
