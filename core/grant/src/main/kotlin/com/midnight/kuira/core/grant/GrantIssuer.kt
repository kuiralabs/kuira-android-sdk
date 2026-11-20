package com.midnight.kuira.core.grant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** The 64-byte BIP-39 seed, read for one issuer operation (the biometric or passkey ceremony). */
fun interface GrantSeedSource {
    /** The issuer copies what this returns, wipes its copy when the operation ends, and never modifies the array. */
    suspend fun seed(): ByteArray
}

/** Where the audience publishes its revocations (spec section 5). */
fun interface GrantRevocationSource {
    suspend fun list(root: ByteArray): GrantRevocationList
}

/** Reads `<origin>/.well-known/kuira-grant/v1/revocations/<root hex>`. */
class HttpGrantRevocationSource(
    private val origin: String,
    private val clock: () -> Long = GrantIssuer.wallClock,
) : GrantRevocationSource {
    override suspend fun list(root: ByteArray): GrantRevocationList = withContext(Dispatchers.IO) {
        val url = URL("${origin.trimEnd('/')}/$PATH_PREFIX/${hex(root)}")
        val connection = url.openConnection() as HttpURLConnection
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw GrantRefusal.Malformed("the revocation list answered $status")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            GrantRevocationList.fromJson(root, body, clock())
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PATH_PREFIX = ".well-known/kuira-grant/v1/revocations"
    }
}

/** A grant the Sigil issued. */
class IssuedGrant internal constructor(val grant: Grant, val wire: String)

/**
 * The Sigil's side of the grant spec: it issues grants to a grantee that proved it holds its key, and revokes
 * them. The root key exists only while one of these calls runs: each reads the seed from [seeds], derives the
 * audience's root, signs typed preimages, and wipes what it owns.
 */
class GrantIssuer(
    private val seeds: GrantSeedSource,
    private val roster: GrantRoster,
    private val clock: () -> Long = wallClock,
) {
    /** Checks a request before the owner is asked: its signature, its window, and the roster's rules. */
    suspend fun review(requestWire: String): GrantRequest {
        val request = GrantVerifier.request(requestWire, clock())
        checkRoster(request)
        return request
    }

    /**
     * Issues what the owner approved of [requestWire]: [scope], a subset of the requested scope, until
     * [expiresAt], no later than the expiry wanted. [revocations] is the audience's list; it moves the rotation
     * past a retired root and the generation past a revoke-all a restored device does not know of.
     */
    suspend fun issue(
        requestWire: String,
        scope: List<String>,
        expiresAt: Long,
        revocations: GrantRevocationSource? = null,
    ): IssuedGrant {
        val now = clock()
        val request = GrantVerifier.request(requestWire, now)
        checkRoster(request)
        GrantBytes.checkScope(scope)
        scope.firstOrNull { it !in request.scope }?.let { throw GrantIssueRefusal.ScopeNotRequested(it) }
        if (expiresAt <= now || expiresAt > request.wantedExpiry || expiresAt - now > GrantBytes.MAX_LIFETIME) {
            throw GrantIssueRefusal.ExpiryNotAllowed
        }

        return withRoots(request.audience) { roots ->
            var state = roster.state(request.audience)
            var root = roots.key(state.rotation)
            if (revocations != null) {
                var list = revocations.list(root.publicKey)
                while (list.retires(request.audience)) {
                    state = state.copy(rotation = nextRotation(state.rotation), generation = 0)
                    root = roots.key(state.rotation)
                    list = revocations.list(root.publicKey)
                }
                state = state.copy(generation = maxOf(state.generation, list.generation(request.audience)))
            }
            state = state.copy(roots = state.roots + (state.rotation to hex(root.publicKey)))
            val grant = Grant(
                root = root.publicKey,
                audience = request.audience,
                grantee = request.grantee,
                client = request.client,
                scope = scope,
                issuedAt = now,
                expiresAt = expiresAt,
                generation = state.generation,
                issuance = random(32),
            )
            val wire = GrantWire(grant.preimage, root.sign(grant.preimage)).text
            val record = GrantRecord(
                id = hex(grant.id),
                wire = wire,
                audience = grant.audience.value,
                rotation = state.rotation,
                root = hex(grant.root),
                client = grant.client.value,
                grantee = grant.grantee.wire,
                scope = scope,
                issuedAt = now,
                expiresAt = expiresAt,
                generation = state.generation,
            )
            val finalState = state
            val nonce = hex(request.nonce)
            roster.update { contents ->
                if (contents.nonces.containsKey(nonce)) throw GrantIssueRefusal.RequestReplayed
                contents.nonces.entries.removeAll { it.value + GrantVerifier.SKEW <= now }
                contents.nonces[nonce] = request.expiresAt
                contents.audiences[grant.audience.value] = finalState
                contents.records.add(record)
            }
            IssuedGrant(grant, wire)
        }
    }

    /** Revokes one grant the roster holds. Returns the revocation's wire form, for the audience. */
    suspend fun revoke(grantID: String): String {
        val record = roster.records().firstOrNull { it.id == grantID } ?: throw GrantIssueRefusal.UnknownGrant
        val audience = GrantClientID(record.audience)
        val at = clock()
        val wire = withRoots(audience) { roots ->
            val root = roots.key(record.rotation)
            val id = hexBytes(record.id) ?: throw GrantIssueRefusal.UnknownGrant
            val revocation = GrantRevocation.Revoke(root.publicKey, id, at)
            GrantWire(revocation.preimage, root.sign(revocation.preimage)).text
        }
        roster.update { contents ->
            contents.records.replaceAll { if (it.id == grantID && it.revokedAt == null) it.copy(revokedAt = at) else it }
        }
        return wire
    }

    /** Revokes every grant of the current root at [audience]: the generation moves up by one. */
    suspend fun revokeAll(audience: GrantClientID): String {
        val at = clock()
        var state = roster.state(audience)
        if (state.generation >= GrantBytes.U32_MAX) throw GrantRefusal.Malformed("every generation is revoked; retire the root")
        state = state.copy(generation = state.generation + 1)
        val rotation = state.rotation
        val generation = state.generation
        val wire = withRoots(audience) { roots ->
            val root = roots.key(rotation)
            state = state.copy(roots = state.roots + (rotation to hex(root.publicKey)))
            val revocation = GrantRevocation.RevokeAll(root.publicKey, audience, generation, at)
            GrantWire(revocation.preimage, root.sign(revocation.preimage)).text
        }
        val finalState = state
        roster.update { contents ->
            contents.audiences[audience.value] = finalState
            contents.records.replaceAll {
                if (it.audience == audience.value && it.rotation == rotation && it.generation < generation && it.revokedAt == null) {
                    it.copy(revokedAt = at)
                } else {
                    it
                }
            }
        }
        return wire
    }

    /** Retires the current root at [audience], which revokes all its grants; the next grant uses the next rotation. */
    suspend fun retire(audience: GrantClientID): String {
        val at = clock()
        var state = roster.state(audience)
        val retired = state.rotation
        val wire = withRoots(audience) { roots ->
            val root = roots.key(retired)
            state = state.copy(roots = state.roots + (retired to hex(root.publicKey)))
            val revocation = GrantRevocation.Retire(root.publicKey, audience, at)
            GrantWire(revocation.preimage, root.sign(revocation.preimage)).text
        }
        val finalState = state.copy(rotation = nextRotation(retired), generation = 0)
        roster.update { contents ->
            contents.audiences[audience.value] = finalState
            contents.records.replaceAll {
                if (it.audience == audience.value && it.rotation == retired && it.revokedAt == null) it.copy(revokedAt = at) else it
            }
        }
        return wire
    }

    /**
     * Revokes every grant at every audience the roster knows, as unlinking a device does. Returns the revoke-all
     * wire forms by audience.
     */
    suspend fun revokeEverything(): Map<String, String> {
        val wires = linkedMapOf<String, String>()
        for (audience in roster.records().map { it.audience }.toSortedSet()) {
            wires[audience] = revokeAll(GrantClientID(audience))
        }
        return wires
    }

    /** The public root key at [audience] for the roster's current rotation. */
    suspend fun rootKey(audience: GrantClientID): ByteArray {
        val rotation = roster.state(audience).rotation
        return withRoots(audience) { it.key(rotation).publicKey }
    }

    private suspend fun checkRoster(request: GrantRequest) {
        val contents = roster.snapshot()
        if (contents.nonces.containsKey(hex(request.nonce))) throw GrantIssueRefusal.RequestReplayed
        val now = clock()
        for (record in contents.records) {
            if (record.grantee == request.grantee.wire && record.audience != request.audience.value) {
                throw GrantIssueRefusal.KeyGrantedAtAnotherAudience
            }
            if (record.client == request.client.value && record.audience != request.audience.value) {
                throw GrantIssueRefusal.ClientGrantedAtAnotherAudience
            }
            if (record.audience == request.audience.value && record.client == request.client.value &&
                record.grantee != request.grantee.wire && record.revokedAt == null && now < record.expiresAt
            ) {
                throw GrantIssueRefusal.ClientHasAnotherKey
            }
        }
    }

    /** Reads the seed, derives the grant-root node, runs [body], and wipes the seed copy and node however it ends. */
    private suspend fun <T> withRoots(audience: GrantClientID, body: suspend (GrantRoots) -> T): T {
        val seed = seeds.seed().copyOf()
        try {
            if (seed.size != 64) throw GrantIssueRefusal.InvalidSeedLength(seed.size)
            val roots = GrantRoots(seed, audience)
            try {
                return body(roots)
            } finally {
                roots.wipe()
            }
        } finally {
            seed.fill(0)
        }
    }

    private fun nextRotation(rotation: Long): Long {
        if (rotation >= GrantBytes.U32_MAX) throw GrantRefusal.Malformed("every rotation is retired")
        return rotation + 1
    }

    companion object {
        val wallClock: () -> Long = { System.currentTimeMillis() / 1000 }

        private val secureRandom = SecureRandom()

        internal fun random(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }
    }
}

/** The grant-root node for one audience, held for one ceremony (spec section 2.1). */
internal class GrantRoots(seed: ByteArray, private val audience: GrantClientID) {
    /** A root key; its secret is wiped with the [GrantRoots] that made it. */
    class Key internal constructor(private val secret: ByteArray) {
        val publicKey: ByteArray = GrantCurves.ed25519PublicKey(secret)

        fun sign(preimage: ByteArray): ByteArray = GrantCurves.ed25519Sign(secret, preimage)

        internal fun wipe() = secret.fill(0)
    }

    val node: Slip10Ed25519.Node = Slip10Ed25519.derive(seed, Slip10Ed25519.GRANT_ROOT_PATH)
    private val keys = mutableListOf<Key>()

    /** The root at [rotation]: the first 32 bytes of HMAC-SHA512 over the node, keyed by its key and chain code. */
    fun key(rotation: Long): Key = Key(secret(node, audience, rotation)).also { keys.add(it) }

    fun wipe() {
        keys.forEach { it.wipe() }
        keys.clear()
        node.wipe()
    }

    companion object {
        const val AUDIENCE_TAG = "kuira:grant:audience:v1"

        fun secret(node: Slip10Ed25519.Node, audience: GrantClientID, rotation: Long): ByteArray {
            val key = node.key + node.chainCode
            val mac = Slip10Ed25519.hmacSHA512(
                key,
                GrantBytes.tag(AUDIENCE_TAG) + GrantBytes.u32(rotation) + audience.value.toByteArray(Charsets.UTF_8),
            )
            try {
                return mac.copyOfRange(0, 32)
            } finally {
                key.fill(0)
                mac.fill(0)
            }
        }
    }
}
