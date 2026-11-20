package com.midnight.kuira.core.grant

/**
 * The JSON this module reads and writes: the roster file and an audience's revocation list. Numbers are kept as
 * their exact text, so an integer such as 2^64 − 1 is never rounded through a double.
 */
internal sealed class GrantJson {
    data class Obj(val fields: Map<String, GrantJson>) : GrantJson()
    data class Arr(val items: List<GrantJson>) : GrantJson()
    data class Str(val value: String) : GrantJson()
    data class Num(val text: String) : GrantJson()
    data class Bool(val value: Boolean) : GrantJson()
    data object Null : GrantJson()

    operator fun get(key: String): GrantJson? = (this as? Obj)?.fields?.get(key)

    val string: String? get() = (this as? Str)?.value
    val array: List<GrantJson>? get() = (this as? Arr)?.items
    val obj: Map<String, GrantJson>? get() = (this as? Obj)?.fields
    val boolean: Boolean? get() = (this as? Bool)?.value

    /** A non-negative integer below 2^63. */
    val long: Long? get() = (this as? Num)?.text?.takeIf { it.all(Char::isDigit) }?.toLongOrNull()

    /** A non-negative integer below 2^64, as the [Long] with the same 64 bits. */
    val unsignedLong: Long?
        get() = (this as? Num)?.text?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let {
            try {
                java.lang.Long.parseUnsignedLong(it)
            } catch (_: NumberFormatException) {
                null
            }
        }

    fun encode(): String = StringBuilder().also { write(it) }.toString()

    private fun write(out: StringBuilder) {
        when (this) {
            is Obj -> {
                out.append('{')
                fields.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    writeString(out, key)
                    out.append(':')
                    value.write(out)
                }
                out.append('}')
            }
            is Arr -> {
                out.append('[')
                items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    item.write(out)
                }
                out.append(']')
            }
            is Str -> writeString(out, value)
            is Num -> out.append(text)
            is Bool -> out.append(value)
            Null -> out.append("null")
        }
    }

    companion object {
        fun number(value: Long): GrantJson = Num(value.toString())

        fun parse(text: String): GrantJson {
            val parser = Parser(text)
            val value = parser.value()
            parser.skipSpace()
            if (!parser.atEnd()) throw GrantRefusal.Malformed("JSON with text after its value")
            return value
        }

        private fun writeString(out: StringBuilder, value: String) {
            out.append('"')
            for (c in value) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c < ' ' -> out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index == text.length

        fun skipSpace() {
            while (index < text.length && text[index] in " \t\r\n") index++
        }

        fun value(): GrantJson {
            skipSpace()
            if (atEnd()) fail()
            return when (text[index]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Str(string())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> number()
            }
        }

        private fun obj(): GrantJson {
            index++
            val fields = LinkedHashMap<String, GrantJson>()
            skipSpace()
            if (peek() == '}') {
                index++
                return Obj(fields)
            }
            while (true) {
                skipSpace()
                if (peek() != '"') fail()
                val key = string()
                skipSpace()
                expect(':')
                if (fields.put(key, value()) != null) throw GrantRefusal.Malformed("JSON with a repeated key")
                skipSpace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return Obj(fields)
                    }
                    else -> fail()
                }
            }
        }

        private fun arr(): GrantJson {
            index++
            val items = ArrayList<GrantJson>()
            skipSpace()
            if (peek() == ']') {
                index++
                return Arr(items)
            }
            while (true) {
                items.add(value())
                skipSpace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return Arr(items)
                    }
                    else -> fail()
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail()
                val c = text[index++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail()
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail()
                                val code = text.substring(index, index + 4).toIntOrNull(16) ?: fail()
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                    else -> out.append(c)
                }
            }
        }

        private fun number(): GrantJson {
            val start = index
            if (peek() == '-') index++
            val integerStart = index
            while (peek()?.isDigit() == true) index++
            if (index == integerStart) fail()
            if (text[integerStart] == '0' && index - integerStart > 1) fail()
            if (peek() == '.') {
                index++
                val fractionStart = index
                while (peek()?.isDigit() == true) index++
                if (index == fractionStart) fail()
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                val exponentStart = index
                while (peek()?.isDigit() == true) index++
                if (index == exponentStart) fail()
            }
            return Num(text.substring(start, index))
        }

        private fun literal(word: String, value: GrantJson): GrantJson {
            if (!text.startsWith(word, index)) fail()
            index += word.length
            return value
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun expect(c: Char) {
            if (peek() != c) fail()
            index++
        }

        private fun fail(): Nothing = throw GrantRefusal.Malformed("not JSON at offset $index")
    }
}
