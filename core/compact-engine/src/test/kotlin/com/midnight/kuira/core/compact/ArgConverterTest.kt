package com.midnight.kuira.core.compact

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class ArgConverterTest {

    // ── Strings ──

    @Test
    fun `string becomes single-quoted JS literal`() {
        assertEquals("'Hello'", ArgConverter.toJsExpression("Hello"))
    }

    @Test
    fun `string with single quotes is escaped`() {
        assertEquals("'it\\'s done'", ArgConverter.toJsExpression("it's done"))
    }

    @Test
    fun `string with backslash is escaped`() {
        assertEquals("'back\\\\slash'", ArgConverter.toJsExpression("back\\slash"))
    }

    @Test
    fun `string with newlines is escaped`() {
        assertEquals("'line1\\nline2'", ArgConverter.toJsExpression("line1\nline2"))
    }

    @Test
    fun `empty string`() {
        assertEquals("''", ArgConverter.toJsExpression(""))
    }

    // ── Numbers ──

    @Test
    fun `int becomes JS number`() {
        assertEquals("42", ArgConverter.toJsExpression(42))
    }

    @Test
    fun `negative int`() {
        assertEquals("-1", ArgConverter.toJsExpression(-1))
    }

    @Test
    fun `zero`() {
        assertEquals("0", ArgConverter.toJsExpression(0))
    }

    @Test
    fun `long becomes JS number`() {
        assertEquals("1000000000", ArgConverter.toJsExpression(1000000000L))
    }

    @Test
    fun `BigInteger becomes JS BigInt`() {
        assertEquals("99999999999999999999n", ArgConverter.toJsExpression(BigInteger("99999999999999999999")))
    }

    // ── Booleans ──

    @Test
    fun `true becomes JS true`() {
        assertEquals("true", ArgConverter.toJsExpression(true))
    }

    @Test
    fun `false becomes JS false`() {
        assertEquals("false", ArgConverter.toJsExpression(false))
    }

    // ── Null ──

    @Test
    fun `null becomes JS null`() {
        assertEquals("null", ArgConverter.toJsExpression(null))
    }

    // ── ByteArray ──

    @Test
    fun `byte array becomes Uint8Array`() {
        assertEquals(
            "new Uint8Array([1,2,3])",
            ArgConverter.toJsExpression(byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun `empty byte array`() {
        assertEquals("new Uint8Array([])", ArgConverter.toJsExpression(byteArrayOf()))
    }

    @Test
    fun `byte array with unsigned values`() {
        // 0xFF = -1 as signed byte, should be 255 in JS
        assertEquals(
            "new Uint8Array([255,128,0])",
            ArgConverter.toJsExpression(byteArrayOf(-1, -128, 0)),
        )
    }

    // ── Maps (JS objects) ──

    @Test
    fun `map becomes JS object`() {
        val result = ArgConverter.toJsExpression(mapOf("key" to "value"))
        assertEquals("{ key: 'value' }", result)
    }

    @Test
    fun `map with mixed types`() {
        val result = ArgConverter.toJsExpression(mapOf(
            "name" to "Alice",
            "age" to 30,
            "active" to true,
        ))
        assertEquals("{ name: 'Alice', age: 30, active: true }", result)
    }

    @Test
    fun `nested map`() {
        val result = ArgConverter.toJsExpression(mapOf(
            "inner" to mapOf("x" to 1),
        ))
        assertEquals("{ inner: { x: 1 } }", result)
    }

    @Test
    fun `map with ByteArray value`() {
        val result = ArgConverter.toJsExpression(mapOf(
            "secretKey" to ByteArray(3),
        ))
        assertEquals("{ secretKey: new Uint8Array([0,0,0]) }", result)
    }

    // ── Lists (JS arrays) ──

    @Test
    fun `list becomes JS array`() {
        assertEquals("[1, 2, 3]", ArgConverter.toJsExpression(listOf(1, 2, 3)))
    }

    @Test
    fun `empty list`() {
        assertEquals("[]", ArgConverter.toJsExpression(emptyList<Any>()))
    }

    @Test
    fun `list of strings`() {
        assertEquals("['a', 'b']", ArgConverter.toJsExpression(listOf("a", "b")))
    }

    // ── toJsExpressions (vararg helper) ──

    @Test
    fun `toJsExpressions converts multiple args`() {
        val result = ArgConverter.toJsExpressions("Hello", 42, true)
        assertEquals(listOf("'Hello'", "42", "true"), result)
    }

    @Test
    fun `toJsExpressions empty`() {
        val result = ArgConverter.toJsExpressions()
        assertEquals(emptyList<String>(), result)
    }

    // ── Private state map conversion ──

    @Test
    fun `toJsObjectLiteral converts map to JS object expression`() {
        val result = ArgConverter.toJsObjectLiteral(mapOf(
            "secretKey" to ByteArray(32),
        ))
        assertTrue(result.startsWith("{ secretKey: new Uint8Array(["))
        assertTrue(result.endsWith("]) }"))
    }

    // ── Injection prevention ──

    @Test
    fun `string cannot break out of quotes`() {
        val malicious = "'); process.exit(1); ('"
        val result = ArgConverter.toJsExpression(malicious)
        // All single quotes in the input are escaped with backslash
        assertTrue("Should start with opening quote", result.startsWith("'"))
        assertTrue("Should end with closing quote", result.endsWith("'"))
        // The escaped result should not contain unescaped single quotes mid-string
        // (i.e., every ' inside is preceded by \)
        val inner = result.substring(1, result.length - 1)
        assertFalse("Inner content should not have unescaped quotes",
            inner.replace("\\'", "").contains("'"))
    }

    @Test
    fun `string with template literal chars`() {
        val result = ArgConverter.toJsExpression("\${alert(1)}")
        assertTrue(result.startsWith("'"))
        // Template literals don't execute inside single quotes
    }

    // ── Unsupported types ──

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported type throws`() {
        ArgConverter.toJsExpression(Object())
    }
}
