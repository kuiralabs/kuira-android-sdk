package com.midnight.kuira.core.grant

/**
 * Who a grant is for, in the exact normalised forms of Midnight Passport's scoped-grants MIP (section 4.4): a
 * browser origin, `rdns:`, `mais:` (Midnight Agent Identity Standard) or `self:`. Anything else is refused, never
 * normalised: the bytes are hashed as given.
 */
class GrantClientID(val value: String) {
    enum class Kind { ORIGIN, REVERSE_DNS, AGENT, OWNER }

    val kind: Kind = kindOf(value)

    /** `SHA-256(pad(32, "midnight:account:grant:origin:v1") || client id)`, the client's half of a Passport grant id. */
    val originHash: ByteArray
        get() = GrantBytes.sha256(ORIGIN_TAG, value.toByteArray(Charsets.UTF_8))

    override fun equals(other: Any?): Boolean = other is GrantClientID && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private companion object {
        val ORIGIN_TAG = GrantBytes.tag("midnight:account:grant:origin:v1")

        fun kindOf(value: String): Kind {
            if (!value.all { it.code in 0x21..0x7e }) throw GrantError.InvalidClientID(value)
            value.dropPrefix("rdns:")?.let { rest ->
                if (rest.length > 253 || !isHostname(rest)) throw GrantError.InvalidClientID(value)
                return Kind.REVERSE_DNS
            }
            value.dropPrefix("mais:")?.let { rest ->
                if (rest.length !in 1..128 || !rest.all(::isUnreservedLowercase)) throw GrantError.InvalidClientID(value)
                return Kind.AGENT
            }
            value.dropPrefix("self:")?.let { rest ->
                if (rest.length !in 1..64 || !rest.all(::isSelfLabelChar)) throw GrantError.InvalidClientID(value)
                return Kind.OWNER
            }
            if (!isOrigin(value)) throw GrantError.InvalidClientID(value)
            return Kind.ORIGIN
        }

        /**
         * An RFC 6454 origin as a browser serialises it, in lower case: `https`, or `http` for `localhost` only
         * (MIP section 9.1); a host name or a dotted-decimal IPv4 address; a port only when it is not the default.
         * IPv6 hosts are not accepted.
         */
        fun isOrigin(value: String): Boolean {
            val schemes = listOf("https://" to "443", "http://" to "80")
            val scheme = schemes.firstOrNull { value.startsWith(it.first) } ?: return false
            val authority = value.substring(scheme.first.length)
            val parts = authority.split(":", limit = 2)
            val host = parts[0]
            if (!(isHostname(host) || isIPv4(host))) return false
            if (!(scheme.first == "https://" || host == "localhost")) return false
            if (parts.size != 2) return true
            val port = parts[1]
            if (port.isEmpty() || !port.all(::isDigit) || port.first() == '0' || port == scheme.second) return false
            val number = port.toIntOrNull() ?: return false
            return number <= 65_535
        }

        /**
         * At most 253 bytes of dot-separated LDH labels in lower case, none empty, none longer than 63 bytes, none
         * starting or ending with a hyphen, and a last label that is not all digits.
         */
        fun isHostname(host: String): Boolean {
            val labels = host.split(".")
            if (host.length > 253 || labels.last().all(::isDigit)) return false
            return labels.all { label ->
                label.length in 1..63 && label.all(::isLDH) && label.first() != '-' && label.last() != '-'
            }
        }

        /** Four decimal octets of 0 to 255 with no leading zeros, the only IPv4 form a browser serialises. */
        fun isIPv4(host: String): Boolean {
            val octets = host.split(".")
            return octets.size == 4 && octets.all { octet ->
                octet.length in 1..3 && octet.all(::isDigit) && (octet.length == 1 || octet.first() != '0') &&
                    (octet.toIntOrNull() ?: 256) <= 255
            }
        }

        fun isDigit(c: Char): Boolean = c in '0'..'9'
        fun isLower(c: Char): Boolean = c in 'a'..'z'
        fun isLDH(c: Char): Boolean = isLower(c) || isDigit(c) || c == '-'
        fun isSelfLabelChar(c: Char): Boolean = isLDH(c) || c == '_'
        fun isUnreservedLowercase(c: Char): Boolean =
            isLower(c) || isDigit(c) || c == '-' || c == '.' || c == '_' || c == '~'

        fun String.dropPrefix(prefix: String): String? = if (startsWith(prefix)) substring(prefix.length) else null
    }
}
