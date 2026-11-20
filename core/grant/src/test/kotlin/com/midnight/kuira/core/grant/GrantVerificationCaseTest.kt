package com.midnight.kuira.core.grant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every verification case of the shared vectors (spec section 11): the inputs and the outcome the spec
 * requires, the same file the Swift and TypeScript verifiers run.
 */
class GrantVerificationCaseTest {
    private fun cases(kind: String): List<GrantJson> = Vectors["verification"].field(kind).list()

    private fun list(entry: GrantJson, grant: String): GrantRevocationList? {
        val wires = entry["revocations"]?.array ?: return null
        val root = GrantWire.parse(grant).preimage.copyOfRange(34, 66)
        return GrantRevocationList(root, wires.map { it.text() }, 0)
    }

    @Test
    fun everyGrantCase() {
        val entries = cases("grants")
        assertEquals(19, entries.size)
        for (entry in entries) {
            val name = entry.field("name").text()
            val grant = entry.field("grant").text()
            val audience = GrantClientID(entry.field("audience").text())
            val now = entry.field("now").number()

            val result = outcome { GrantVerifier.grant(grant, audience, now, list(entry, grant)) }

            assertEquals(name, entry.field("expect").text(), result)
        }
    }

    @Test
    fun everyRevocationCase() {
        val entries = cases("revocations")
        assertEquals(3, entries.size)
        for (entry in entries) {
            val wire = entry.field("wire").text()

            val result = outcome { GrantVerifier.revocation(wire) }

            assertEquals(entry.field("name").text(), entry.field("expect").text(), result)
        }
    }

    @Test
    fun everyUseCase() = runTest {
        val entries = cases("uses")
        assertEquals(13, entries.size)
        for (entry in entries) {
            val name = entry.field("name").text()
            val grant = entry.field("grant").text()
            val use = entry.field("use").text()
            val audience = GrantClientID(entry.field("audience").text())
            val feature = entry.field("feature").text()
            val mode = when (entry.field("mode").text()) {
                "live" -> GrantVerifier.UseMode.Live(
                    feature,
                    entry.field("now").number(),
                    InMemoryGrantReplayStore(entry.field("storeStartedAt").number()),
                )
                else -> GrantVerifier.UseMode.Record(feature)
            }
            val revocations = list(entry, grant)

            if (entry.field("presentTwice").boolean == true) {
                val first = outcome { GrantVerifier.use(use, grant, audience, mode, revocations) }
                assertEquals("$name: the first presentation", "valid", first)
            }
            val result = outcome { GrantVerifier.use(use, grant, audience, mode, revocations) }

            assertEquals(name, entry.field("expect").text(), result)
        }
    }
}
