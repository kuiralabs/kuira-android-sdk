package com.midnight.kuira.core.grant

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a verification consulted (spec section 7.3). */
sealed class GrantCheck {
    /** No revocation list was consulted: a revocation the audience holds may exist. */
    data object Offline : GrantCheck()

    /** The audience's list for this root was read at [readAt]. */
    data class WithRevocations(val readAt: Long) : GrantCheck()
}

/** The revocations an audience publishes for one root (spec section 5), each verified before it is kept. */
class GrantRevocationList(root: ByteArray, wires: List<String>, val readAt: Long) {
    private val rootBytes = root.copyOf()

    /**
     * Each wire form verified; one that is malformed or not signed by the root it names refuses the list. An
     * entry naming another root is kept and never applies to this root's grants.
     */
    val revocations: List<GrantRevocation> = wires.map { GrantVerifier.revocation(it) }

    val root: ByteArray get() = rootBytes.copyOf()

    /** Refuses [grant] if a revocation of its root applies to it. */
    internal fun check(grant: Grant, id: ByteArray) {
        if (!grant.root.contentEquals(rootBytes)) throw GrantRefusal.Malformed("the list is for another root")
        for (revocation in revocations) {
            if (!revocation.root.contentEquals(grant.root)) continue
            when (revocation) {
                is GrantRevocation.Revoke -> if (revocation.grantID.contentEquals(id)) throw GrantRefusal.Revoked
                is GrantRevocation.RevokeAll ->
                    if (revocation.audience == grant.audience && grant.generation < revocation.generation) {
                        throw GrantRefusal.Revoked
                    }
                is GrantRevocation.Retire -> if (revocation.audience == grant.audience) throw GrantRefusal.Retired
            }
        }
    }

    internal fun retires(audience: GrantClientID): Boolean =
        revocations.any { it is GrantRevocation.Retire && it.audience == audience }

    /** The highest revoke-all generation at [audience]. */
    internal fun generation(audience: GrantClientID): Long =
        revocations.filterIsInstance<GrantRevocation.RevokeAll>().filter { it.audience == audience }
            .maxOfOrNull { it.generation } ?: 0L

    companion object {
        /** The JSON the audience serves: `{"revocations": [<wire form>, …]}`. */
        fun fromJson(root: ByteArray, json: String, readAt: Long): GrantRevocationList {
            val entries = try {
                GrantJson.parse(json)["revocations"]?.array
            } catch (_: GrantRefusal) {
                null
            }
            val wires = entries?.map { it.string ?: throw GrantRefusal.Malformed("not a revocation list") }
                ?: throw GrantRefusal.Malformed("not a revocation list")
            return GrantRevocationList(root, wires, readAt)
        }
    }
}

/** A grant that passed section 7.1. */
class VerifiedGrant internal constructor(val grant: Grant, id: ByteArray, val check: GrantCheck) {
    private val idBytes = id.copyOf()
    val id: ByteArray get() = idBytes.copyOf()
}

/** A use that passed section 7.2. */
class VerifiedUse internal constructor(val grant: VerifiedGrant, val use: GrantUse)

/** The pairs `(grant id, nonce)` a live verifier has seen, kept 120 s past their `at` (spec section 7.2). */
interface GrantReplayStore {
    /** When the store's memory begins; uses with `at` earlier than this plus 60 s are refused. */
    suspend fun startedAt(): Long

    /** Records the pair; `false` if it was already seen. */
    suspend fun claim(grantID: ByteArray, nonce: ByteArray, at: Long, now: Long): Boolean
}

/**
 * A replay store in this process's memory. It suits one verifier; an audience served by several instances
 * shares one store among them.
 */
class InMemoryGrantReplayStore(private val startedAt: Long) : GrantReplayStore {
    private val mutex = Mutex()
    private val seen = HashMap<String, Long>()

    override suspend fun startedAt(): Long = startedAt

    override suspend fun claim(grantID: ByteArray, nonce: ByteArray, at: Long, now: Long): Boolean = mutex.withLock {
        seen.entries.removeAll { it.value + GrantVerifier.REPLAY_WINDOW <= now }
        val key = hex(grantID + nonce)
        if (seen.containsKey(key)) return@withLock false
        seen[key] = at
        true
    }
}

/** Verifies the spec's structures (section 7). Every time is whole seconds since the Unix epoch. */
object GrantVerifier {
    /** The clock skew a verifier allows. */
    const val SKEW = 60L
    internal const val REPLAY_WINDOW = 120L

    sealed class UseMode {
        /** The board at publish: fresh, never seen before. */
        class Live(val feature: String, val now: Long, val replay: GrantReplayStore) : UseMode()

        /** A taker, later: the grant is checked at the use's own time. */
        class Record(val feature: String) : UseMode()
    }

    /** Section 7.1: a grant at [audience], at time [now]. */
    fun grant(
        wire: String,
        audience: GrantClientID,
        now: Long,
        revocations: GrantRevocationList? = null,
    ): VerifiedGrant {
        val parsed = GrantWire.parse(wire)
        val grant = Grant.decode(parsed.preimage)
        checkRootSignature(grant.root, parsed.preimage, parsed.signature)
        if (!GrantCurves.k1KeyIsValid(grant.grantee.x + grant.grantee.y)) throw GrantRefusal.BadGranteeKey
        if (grant.audience != audience) throw GrantRefusal.WrongAudience
        checkWindow(grant, now)
        val id = grant.id
        revocations?.check(grant, id)
        val check = if (revocations != null) GrantCheck.WithRevocations(revocations.readAt) else GrantCheck.Offline
        return VerifiedGrant(grant, id, check)
    }

    /** Section 7.2: a use under [grantWire], in [mode]. */
    suspend fun use(
        wire: String,
        grantWire: String,
        audience: GrantClientID,
        mode: UseMode,
        revocations: GrantRevocationList? = null,
    ): VerifiedUse {
        val parsed = GrantWire.parse(wire)
        val use = GrantUse.decode(parsed.preimage)
        val (verified, feature) = grantFor(use, grantWire, audience, mode, revocations)
        if (!use.grantID.contentEquals(verified.id)) throw GrantRefusal.Malformed("the use names another grant")
        if (use.feature != feature) throw GrantRefusal.FeatureUnknown
        if (feature !in verified.grant.scope) throw GrantRefusal.FeatureNotGranted
        if (verified.grant.issuedAt > use.at) throw GrantRefusal.NotYetValid
        if (use.at >= verified.grant.expiresAt) throw GrantRefusal.Expired
        checkGranteeSignature(verified.grant.grantee, use.digest, parsed.signature)
        if (mode is UseMode.Live && !mode.replay.claim(use.grantID, use.nonce, use.at, mode.now)) {
            throw GrantRefusal.Replayed
        }
        return VerifiedUse(verified, use)
    }

    /**
     * The grant checked at the time the mode asks (now when live, the use's own time as a record), the live
     * freshness rules, and the feature the caller expects.
     */
    private suspend fun grantFor(
        use: GrantUse,
        grantWire: String,
        audience: GrantClientID,
        mode: UseMode,
        revocations: GrantRevocationList?,
    ): Pair<VerifiedGrant, String> = when (mode) {
        is UseMode.Live -> {
            GrantBytes.checkTime(mode.now)
            val verified = grant(grantWire, audience, mode.now, revocations)
            if (use.at > mode.now + SKEW || mode.now > use.at + SKEW) throw GrantRefusal.StaleUse
            if (use.at - SKEW < mode.replay.startedAt()) throw GrantRefusal.StaleUse
            verified to mode.feature
        }
        is UseMode.Record -> grant(grantWire, audience, use.at, revocations) to mode.feature
    }

    /** Section 5: a revocation, signed by the root it names. */
    fun revocation(wire: String): GrantRevocation {
        val parsed = GrantWire.parse(wire)
        val revocation = GrantRevocation.decode(parsed.preimage)
        checkRootSignature(revocation.root, parsed.preimage, parsed.signature)
        return revocation
    }

    /** Section 6: a grantee's request, signed by the key it asks a grant for and live at [now]. */
    fun request(wire: String, now: Long): GrantRequest {
        GrantBytes.checkTime(now)
        val parsed = GrantWire.parse(wire)
        val request = GrantRequest.decode(parsed.preimage)
        checkGranteeSignature(request.grantee, request.digest, parsed.signature)
        if (request.issuedAt > now + SKEW) throw GrantRefusal.NotYetValid
        if (now >= request.expiresAt + SKEW) throw GrantRefusal.Expired
        return request
    }

    internal fun checkRootSignature(root: ByteArray, preimage: ByteArray, signature: ByteArray) {
        when (GrantCurves.ed25519Verify(root, preimage, signature)) {
            GrantSignatureCheck.VALID -> Unit
            GrantSignatureCheck.BAD_KEY -> throw GrantRefusal.BadRootKey
            GrantSignatureCheck.BAD_SIGNATURE -> throw GrantRefusal.BadSignature
        }
    }

    internal fun checkGranteeSignature(key: GranteeKey, digest: ByteArray, signature: ByteArray) {
        when (GrantCurves.k1Verify(key.x + key.y, digest, signature)) {
            GrantSignatureCheck.VALID -> Unit
            GrantSignatureCheck.BAD_KEY -> throw GrantRefusal.BadGranteeKey
            GrantSignatureCheck.BAD_SIGNATURE -> throw GrantRefusal.BadSignature
        }
    }

    /** `issued at ≤ at + skew` and `at < expires at + skew`; times are below 2^40, so nothing overflows. */
    private fun checkWindow(grant: Grant, time: Long) {
        GrantBytes.checkTime(time)
        if (grant.issuedAt > time + SKEW) throw GrantRefusal.NotYetValid
        if (time >= grant.expiresAt + SKEW) throw GrantRefusal.Expired
    }
}
