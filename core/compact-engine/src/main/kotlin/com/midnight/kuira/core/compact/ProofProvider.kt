package com.midnight.kuira.core.compact

import com.midnight.kuira.core.compact.proving.LocalProver
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates ZK proofs for unproven transactions.
 *
 * Takes the hex-encoded (Transaction, ProvingKeys) tuple from
 * [CircuitExecutor] and produces a proven transaction ready
 * for wallet balancing and submission.
 */
interface ProofProvider {
    /**
     * Prove an unproven transaction.
     *
     * @param unprovenTxHex Hex-encoded SCALE serialized (Transaction, ProvingKeys) tuple
     * @return Hex-encoded proven Transaction
     * @throws ProvingException if proof generation fails
     */
    suspend fun prove(unprovenTxHex: String): String
}

/**
 * Proves transactions locally on the phone using cached proving keys.
 *
 * Uses the same Rust proving engine as the Midnight proof server.
 * Proving keys must be downloaded via [ProvingKeyManager] before use.
 *
 * @param provingKeyManager Manages proving key download and caching
 */
class LocalProofProvider(
    private val provingKeyManager: ProvingKeyManager,
) : ProofProvider {

    override suspend fun prove(unprovenTxHex: String): String {
        if (!provingKeyManager.keysDir.exists()) {
            throw ProvingException(
                "Proving keys directory does not exist. Download keys first.",
            )
        }

        val keysDir = provingKeyManager.keysDir.absolutePath
        val result = withContext(Dispatchers.IO) {
            LocalProver.proveTransaction(unprovenTxHex, keysDir)
        } ?: throw ProvingException("Local prover returned null — proving failed")

        return result
    }
}

/** Thrown when ZK proof generation fails. */
class ProvingException(message: String) : Exception(message)
