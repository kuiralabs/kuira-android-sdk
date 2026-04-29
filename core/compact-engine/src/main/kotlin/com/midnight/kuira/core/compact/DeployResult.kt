package com.midnight.kuira.core.compact

/** Result of deploying a contract to the blockchain. */
data class DeployResult(
    val contractAddress: String,
    val timings: DeployTimings,
)

data class DeployTimings(
    val executeMs: Long,
    val proveMs: Long,
    val balanceMs: Long,
)
