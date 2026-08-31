package com.midnight.kuira.sdk

import android.util.Log
import com.midnight.kuira.core.compact.ContractRuntime
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.RuntimeVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thrown when a submit is aborted because the node's ledger version is incompatible with the
 * client's bundled version AND the policy is set to hard-fail ([VersionCoherence.HARD_FAIL_ON_SKEW]).
 * Under the default warn policy this is never thrown.
 */
class VersionSkewException(message: String) : Exception(message)

/**
 * Runs the [VersionCoherence] pre-flight at most ONCE per SDK instance and caches the verdict, so
 * every submit path (send, dust registration, contract call) shares a single check. The actual
 * comparison policy lives in [VersionCoherence]; this class only owns the I/O (node RPC + native
 * version read), the loud logging, and the memoization.
 *
 * NON-BLOCKING by policy: a skew logs and returns [VersionCoherence.Result.Warning]; a check that
 * can't run returns [VersionCoherence.Result.Unknown]. Neither throws. [ensure] is the submit-guard
 * that throws [VersionSkewException] only under the hard-fail policy.
 */
internal class VersionCoherenceChecker(
    private val nodeRpcClient: NodeRpcClient,
    private val clientLedgerVersion: () -> String? = { runCatching { ContractRuntime.ledgerVersion() }.getOrNull() },
) {
    private val mutex = Mutex()
    @Volatile private var cached: VersionCoherence.Result? = null

    /** Memoized one-time check. Returns the typed verdict; never throws for a skew. */
    suspend fun check(): VersionCoherence.Result {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }

            val clientVersion = clientLedgerVersion() ?: VersionCoherence.CLIENT_LEDGER_VERSION_FALLBACK

            val result = try {
                VersionCoherence.evaluate(nodeRpcClient.getRuntimeVersion(), clientVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VersionCoherence.Result.Unknown("version-coherence check could not run: ${e.message}")
            }

            when (result) {
                is VersionCoherence.Result.Coherent ->
                    Log.i(TAG, "Ledger version coherent: client $clientVersion vs node ${result.nodeSpecName} specVersion ${result.nodeSpecVersion}")
                is VersionCoherence.Result.Warning -> Log.w(TAG, result.message)
                is VersionCoherence.Result.Incompatible -> Log.e(TAG, result.message)
                is VersionCoherence.Result.Unknown -> Log.w(TAG, result.reason)
            }

            cached = result
            result
        }
    }

    /**
     * Submit-guard: throws [VersionSkewException] only when the verdict is [Incompatible] (i.e. the
     * hard-fail policy is on AND the node is skewed). A pre-flight that itself errors never blocks a
     * submit — the node validates the tx regardless.
     */
    suspend fun ensure() {
        val result = runCatching { check() }.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TAG, "version-coherence pre-flight errored, proceeding: ${it.message}")
            return
        }
        if (result is VersionCoherence.Result.Incompatible) {
            throw VersionSkewException(result.message)
        }
    }

    private companion object {
        const val TAG = "VersionCoherence"
    }
}

/**
 * Pre-flight check that the client's bundled ledger version is coherent with the node it's
 * talking to.
 *
 * **Why this exists.** The client links a fixed `midnight-ledger` (its op encoding + cost model).
 * When the node runs a different ledger, ops can mis-decode and the node rejects the tx with an
 * opaque `Custom error: N` (e.g. 138) — a class of failure that's near-impossible to debug from the
 * tx-build code. A version comparison up front turns that silent failure into a named, actionable
 * warning.
 *
 * **Why it WARNS instead of hard-failing.** The published support matrix is known to LAG the live
 * node: a node may validate fine with `8.0.2` while the matrix (and our bundled string) say `8.0.3`.
 * Strict equality would false-positive and block a perfectly working node. So the policy is an
 * allowlist of known-compatible node spec versions + a loud warning on anything outside it — never a
 * hard block. The single decision point lives in [evaluate]; flip [HARD_FAIL_ON_SKEW] to change it.
 */
object VersionCoherence {

    /**
     * The client's bundled `midnight-ledger` version, from the native FFI (`kuira_ledger_version`).
     * Falls back to a compile-time constant if the native call is unavailable (so the check still
     * produces a sensible message rather than throwing during a pre-flight).
     */
    const val CLIENT_LEDGER_VERSION_FALLBACK = "8.0.3"

    /**
     * Node runtime `specVersion`s we've validated the bundled ledger against. The node's Substrate
     * runtime version doesn't carry the ledger semver directly, so we gate on `specVersion` — the
     * monotonic counter the runtime bumps on a ledger-affecting upgrade. Outside this set we WARN
     * (the matrix lags reality), we do not block.
     *
     * Keep this in sync as new node releases are verified on PreProd/localnet.
     */
    val KNOWN_COMPATIBLE_SPEC_VERSIONS: Set<Long> = setOf(
        // localnet node observed validating the bundled 8.0.3 ledger (specName "midnight",
        // specVersion 22000, verified via state_getRuntimeVersion on 2026-06-30). Add the
        // PreProd/mainnet specVersions here once verified against a live node — an unverified
        // specVersion warns (the matrix lags reality), it does not fail.
        22000L,
        // localnet node (specName "midnight", specVersion 1000000) verified KNOWN-GOOD on
        // 2026-08-31 by the full instrumented E2E gate (295 tests / 0 failed): fresh dust
        // registration (SdkRegistrationE2ETest), unshielded + shielded send, and contract
        // deploy/call all validated the bundled 8.0.3 ledger against this node. (#87)
        1000000L,
    )

    /**
     * THE decision point. With the support matrix known to lag the live node, a version skew is a
     * WARNING by default — loud (logged + surfaced to the caller) but non-blocking. A human can flip
     * this to make a skew fatal once the matrix is trustworthy. Documented as a single, obvious switch
     * so the policy is easy to find and change.
     */
    const val HARD_FAIL_ON_SKEW = false

    /**
     * Compare the [node] runtime to the [clientLedgerVersion] and classify coherence.
     *
     * Pure (no I/O) so it's unit-testable; the SDK does the RPC + native read and passes the values
     * in. The [Result] names BOTH versions and the remedy so a caller can show it verbatim.
     */
    fun evaluate(node: RuntimeVersion, clientLedgerVersion: String): Result {
        val compatible = node.specVersion in KNOWN_COMPATIBLE_SPEC_VERSIONS
        if (compatible) {
            return Result.Coherent(
                clientLedgerVersion = clientLedgerVersion,
                nodeSpecName = node.specName,
                nodeSpecVersion = node.specVersion,
            )
        }

        val message = buildString {
            append("Ledger version skew: client bundles midnight-ledger $clientLedgerVersion, ")
            append("but the node reports runtime ${node.specName} specVersion ${node.specVersion}, ")
            append("which is not in the known-compatible set $KNOWN_COMPATIBLE_SPEC_VERSIONS. ")
            append("Transactions may be rejected with an opaque \"Custom error: N\". ")
            append("Remedy: update your localnet/node to a matching ledger version ")
            append("(or, if this node is known-good, add its specVersion to KNOWN_COMPATIBLE_SPEC_VERSIONS).")
        }

        // ── Single warn-vs-fail switch (see HARD_FAIL_ON_SKEW) ──
        return if (HARD_FAIL_ON_SKEW) {
            Result.Incompatible(
                clientLedgerVersion = clientLedgerVersion,
                nodeSpecName = node.specName,
                nodeSpecVersion = node.specVersion,
                message = message,
            )
        } else {
            Result.Warning(
                clientLedgerVersion = clientLedgerVersion,
                nodeSpecName = node.specName,
                nodeSpecVersion = node.specVersion,
                message = message,
            )
        }
    }

    /** Outcome of a coherence check. All variants carry both versions for display. */
    sealed interface Result {
        val clientLedgerVersion: String
        val nodeSpecName: String
        val nodeSpecVersion: Long

        /** Node version is in the known-compatible set — proceed normally. */
        data class Coherent(
            override val clientLedgerVersion: String,
            override val nodeSpecName: String,
            override val nodeSpecVersion: Long,
        ) : Result

        /**
         * Node version is outside the known-compatible set, but the policy is non-blocking
         * ([HARD_FAIL_ON_SKEW] = false). Loud warning; the SDK still proceeds.
         */
        data class Warning(
            override val clientLedgerVersion: String,
            override val nodeSpecName: String,
            override val nodeSpecVersion: Long,
            val message: String,
        ) : Result

        /**
         * Node version is outside the known-compatible set AND the policy is set to hard-fail
         * ([HARD_FAIL_ON_SKEW] = true). The SDK should refuse to submit.
         */
        data class Incompatible(
            override val clientLedgerVersion: String,
            override val nodeSpecName: String,
            override val nodeSpecVersion: Long,
            val message: String,
        ) : Result

        /**
         * The check itself couldn't run (node unreachable, RPC unsupported, parse failure). NOT a
         * skew — the SDK proceeds (the node will validate the tx anyway); recorded for diagnostics.
         */
        data class Unknown(val reason: String) : Result {
            override val clientLedgerVersion: String = ""
            override val nodeSpecName: String = ""
            override val nodeSpecVersion: Long = -1
        }
    }
}
