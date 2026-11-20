package com.midnight.kuira.core.grant

/** Why a grant, use, revocation or request was refused, by the names of the grant spec's section 8. */
sealed class GrantRefusal(val name: String, val detail: String? = null) :
    Exception(if (detail == null) name else "$name: $detail") {
    class Malformed(detail: String) : GrantRefusal("malformed", detail)
    data object UnknownTag : GrantRefusal("unknownTag")
    data object UnknownVersion : GrantRefusal("unknownVersion")
    data object UnknownKind : GrantRefusal("unknownKind")
    data object UnknownArm : GrantRefusal("unknownArm")
    data object BadRootKey : GrantRefusal("badRootKey")
    data object BadSignature : GrantRefusal("badSignature")
    data object BadGranteeKey : GrantRefusal("badGranteeKey")
    class BadScope(detail: String) : GrantRefusal("badScope", detail)
    data object WrongAudience : GrantRefusal("wrongAudience")
    data object NotYetValid : GrantRefusal("notYetValid")
    data object Expired : GrantRefusal("expired")
    data object LifetimeTooLong : GrantRefusal("lifetimeTooLong")
    data object TimeOutOfRange : GrantRefusal("timeOutOfRange")
    data object Revoked : GrantRefusal("revoked")
    data object Retired : GrantRefusal("retired")
    data object FeatureNotGranted : GrantRefusal("featureNotGranted")
    data object FeatureUnknown : GrantRefusal("featureUnknown")
    data object Replayed : GrantRefusal("replayed")
    data object StaleUse : GrantRefusal("staleUse")
}

/** Why a client id or grantee key was refused. */
sealed class GrantError(message: String) : Exception(message) {
    /** Not one of the client-id forms of the scoped-grants MIP, section 4.4. */
    class InvalidClientID(val value: String) : GrantError("invalid client id `$value`")

    /** A key's wire form is not 128 lowercase hex characters, or a coordinate is not 32 bytes. */
    class MalformedKey(val detail: String) : GrantError(detail)

    /** A key coordinate at or above its field's modulus; it is refused, never reduced. */
    class NonCanonicalCoordinate : GrantError("a coordinate at or above its field's modulus")

    /** A `k1` envelope other than 0 or 1. */
    class UnknownEnvelope(val envelope: Int) : GrantError("unknown k1 envelope $envelope")
}

/** Why the Sigil would not issue: section 6's rules and the privacy rules of section 2.4. */
sealed class GrantIssueRefusal(message: String) : Exception(message) {
    /** The approved scope holds a feature the request did not ask for. */
    data class ScopeNotRequested(val feature: String) : GrantIssueRefusal("`$feature` was not requested")

    /** The approved expiry is after the one wanted, not after now, or longer than the maximum lifetime. */
    data object ExpiryNotAllowed : GrantIssueRefusal("expiry not allowed")

    /** This request's nonce was already used. */
    data object RequestReplayed : GrantIssueRefusal("request replayed")

    /** This grantee key was granted at another audience. */
    data object KeyGrantedAtAnotherAudience : GrantIssueRefusal("key granted at another audience")

    /** This client id was granted at another audience. */
    data object ClientGrantedAtAnotherAudience : GrantIssueRefusal("client granted at another audience")

    /** This client id holds a live grant at this audience under another key. */
    data object ClientHasAnotherKey : GrantIssueRefusal("client has another key")

    /** No grant with this id is in the roster. */
    data object UnknownGrant : GrantIssueRefusal("unknown grant")

    /** The seed is not the 64-byte BIP-39 seed. */
    data class InvalidSeedLength(val length: Int) : GrantIssueRefusal("a seed of $length bytes, not 64")
}
