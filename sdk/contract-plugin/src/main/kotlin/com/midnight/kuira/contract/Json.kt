package com.midnight.kuira.contract

/**
 * A tiny, dependency-free JSON model + recursive-descent parser, just enough to
 * walk a compactc `contract-info.json` ABI.
 *
 * Why not a JSON library: the contract plugin's only dependency today is
 * `gradleApi()`. Pulling a JSON parser onto the plugin classpath (and the unit
 * test classpath) for a single well-formed file the compiler emits is more
 * surface than it's worth — and a binary `groovy-json` isn't reliably present in
 * an offline Gradle cache. This parser handles the full JSON grammar the ABI
 * uses (objects, arrays, strings with escapes, numbers, booleans, null) and is
 * pure, so [ContractApiGenerator] stays trivially unit-testable.
 */
internal sealed interface JsonValue {
    companion object {
        /** Parse [text] into a [JsonValue]. Throws [IllegalArgumentException] on malformed input. */
        fun parse(text: String): JsonValue = JsonParser(text).parseDocument()
    }
}

/** A JSON object — insertion-ordered so generated output is deterministic. */
internal class JsonObject(private val map: LinkedHashMap<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = map[key]
    val keys: Set<String> get() = map.keys
}

/** A JSON array. */
internal class JsonArray(val items: List<JsonValue>) : JsonValue

/** A JSON string. */
internal class JsonString(val value: String) : JsonValue

/** A JSON number, kept as its source text (the ABI's only numbers are sizes/maxvals we don't consume). */
internal class JsonNumber(val raw: String) : JsonValue

/** A JSON boolean. */
internal class JsonBoolean(val value: Boolean) : JsonValue

/** JSON null. */
internal object JsonNull : JsonValue

private class JsonParser(private val src: String) {
    private var pos = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        require(pos >= src.length) { "Trailing content after JSON document at index $pos" }
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        require(pos < src.length) { "Unexpected end of JSON input" }
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> {
                if (c == '-' || c in '0'..'9') parseNumber()
                else throw IllegalArgumentException("Unexpected character '$c' at index $pos")
            }
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        val map = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { pos++; return JsonObject(map) }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            map[key] = value
            skipWhitespace()
            when (val c = next()) {
                ',' -> continue
                '}' -> break
                else -> throw IllegalArgumentException("Expected ',' or '}' in object, got '$c' at index ${pos - 1}")
            }
        }
        return JsonObject(map)
    }

    private fun parseArray(): JsonArray {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { pos++; return JsonArray(items) }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (val c = next()) {
                ',' -> continue
                ']' -> break
                else -> throw IllegalArgumentException("Expected ',' or ']' in array, got '$c' at index ${pos - 1}")
            }
        }
        return JsonArray(items)
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            require(pos < src.length) { "Unterminated string" }
            when (val c = src[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    require(pos < src.length) { "Unterminated escape" }
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(pos + 4 <= src.length) { "Truncated unicode escape" }
                            val hex = src.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape '\\$esc' at index ${pos - 1}")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonNumber {
        val start = pos
        if (peek() == '-') pos++
        while (pos < src.length && (src[pos] in '0'..'9' || src[pos] in charArrayOf('.', 'e', 'E', '+', '-'))) pos++
        return JsonNumber(src.substring(start, pos))
    }

    private fun parseBoolean(): JsonBoolean = when {
        src.startsWith("true", pos) -> { pos += 4; JsonBoolean(true) }
        src.startsWith("false", pos) -> { pos += 5; JsonBoolean(false) }
        else -> throw IllegalArgumentException("Invalid literal at index $pos")
    }

    private fun parseNull(): JsonValue {
        require(src.startsWith("null", pos)) { "Invalid literal at index $pos" }
        pos += 4
        return JsonNull
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char {
        require(pos < src.length) { "Unexpected end of JSON input" }
        return src[pos]
    }

    private fun next(): Char {
        require(pos < src.length) { "Unexpected end of JSON input" }
        return src[pos++]
    }

    private fun expect(c: Char) {
        val actual = next()
        require(actual == c) { "Expected '$c' but got '$actual' at index ${pos - 1}" }
    }
}
