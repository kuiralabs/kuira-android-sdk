package com.midnight.kuira.sdk

/**
 * The native balancer's return value: the balanced+sealed transaction hex plus the
 * dust nullifiers that balance spent.
 *
 * Wire format is a single delimited string `<txHex>;<n1>,<n2>,...` (rather than
 * JSON) so the JNI layer carries one plain string and the parse needs no JSON
 * library — the Android unit-test classpath has no working `org.json`. Hex contains
 * neither `;` nor `,`, so the delimiters are unambiguous; the nullifier list may be
 * empty (`<txHex>;`).
 */
internal data class BalanceEnvelope(
    val txHex: String,
    val spentNullifiers: List<String>,
) {
    companion object {
        private const val TX_SEPARATOR = ';'
        private const val NULLIFIER_SEPARATOR = ","

        fun parse(raw: String): BalanceEnvelope {
            val separator = raw.indexOf(TX_SEPARATOR)
            // Defensive: the native side always appends ';' (even with no spends),
            // but if it's absent treat the whole string as the tx hex.
            if (separator < 0) return BalanceEnvelope(raw, emptyList())
            val txHex = raw.substring(0, separator)
            val spentNullifiers = raw.substring(separator + 1)
                .split(NULLIFIER_SEPARATOR)
                .filter { it.isNotEmpty() }
            return BalanceEnvelope(txHex, spentNullifiers)
        }
    }
}
