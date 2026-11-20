package com.midnight.kuira.core.grant

import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** The shared vectors, `kuira-grant-v1-vectors.json`, copied unchanged from the iOS SDK's fixtures. */
internal object Vectors {
    val json: GrantJson by lazy {
        val stream = checkNotNull(Vectors::class.java.classLoader?.getResourceAsStream("kuira-grant-v1-vectors.json")) {
            "kuira-grant-v1-vectors.json is not on the test classpath"
        }
        GrantJson.parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    operator fun get(key: String): GrantJson = checkNotNull(json[key]) { "no `$key` in the vectors" }
}

internal fun GrantJson?.field(key: String): GrantJson = checkNotNull(this?.get(key)) { "no `$key`" }

internal fun GrantJson.text(): String = checkNotNull(string) { "not a string: $this" }

internal fun GrantJson.bytes(): ByteArray = checkNotNull(hexBytes(text())) { "not hex: $this" }

internal fun GrantJson.number(): Long = checkNotNull(unsignedLong) { "not an integer: $this" }

internal fun GrantJson.list(): List<GrantJson> = checkNotNull(array) { "not an array: $this" }

/** The spec's name for why [body] was refused, or `valid`. */
internal inline fun outcome(body: () -> Unit): String = try {
    body()
    "valid"
} catch (refusal: GrantRefusal) {
    refusal.name
}

internal inline fun <reified E : Throwable> assertThrowsExactly(expected: E, body: () -> Unit) {
    try {
        body()
    } catch (error: Throwable) {
        if (error !is E) throw AssertionError("expected $expected, got $error", error)
        assertEquals(expected, error)
        return
    }
    fail("expected $expected, and it succeeded")
}

internal inline fun assertRefused(expected: String, body: () -> Unit) {
    assertEquals(expected, outcome(body))
}
