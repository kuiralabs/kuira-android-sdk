package com.midnight.kuira.core.grant

/**
 * The grantee's side (spec sections 4 and 6): one secp256k1 key per audience, which asks for a grant and signs
 * each use under it. Signing takes typed fields, never a digest.
 */
class GranteeSigner(secret: ByteArray) {
    private val secret: ByteArray = secret.copyOf()

    /** The public key for [secret], a 32-byte big-endian scalar in `[1, n − 1]`. */
    val key: GranteeKey = GrantCurves.k1PublicKey(this.secret).let { wire ->
        GranteeKey(GranteeKey.Arm.K1(0), wire.copyOfRange(0, 32), wire.copyOfRange(32, 64))
    }

    /** A request for a grant, live for [window] seconds (at most 600) from [now]. */
    fun request(
        audience: GrantClientID,
        client: GrantClientID,
        scope: List<String>,
        wantedExpiry: Long,
        now: Long,
        window: Long = GrantBytes.MAX_REQUEST_WINDOW,
    ): String {
        val request = GrantRequest(audience, key, client, scope, wantedExpiry, GrantIssuer.random(16), now, now + window)
        return GrantWire(request.preimage, GrantCurves.k1Sign(secret, request.digest)).text
    }

    /** A use of [feature] on [subject] under [grant], at [now]. */
    fun use(grant: Grant, feature: String, subject: ByteArray, now: Long): String {
        if (grant.grantee != key) throw GrantRefusal.BadGranteeKey
        val use = GrantUse(grant.id, feature, subject, GrantIssuer.random(16), now)
        return GrantWire(use.preimage, GrantCurves.k1Sign(secret, use.digest)).text
    }

    companion object {
        /** A new random key. */
        fun generate(): GranteeSigner {
            while (true) {
                val secret = GrantIssuer.random(32)
                try {
                    return GranteeSigner(secret)
                } catch (_: GrantRefusal) {
                    continue
                } finally {
                    secret.fill(0)
                }
            }
        }
    }
}
