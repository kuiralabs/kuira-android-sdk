package com.midnight.kuira.core.grant

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A grant the Sigil issued, as the roster keeps it: public fields only. */
data class GrantRecord(
    /** `H(preimage)`, lowercase hex. */
    val id: String,
    /** `base64url(preimage || signature)`, as handed to the grantee. */
    val wire: String,
    val audience: String,
    val rotation: Long,
    /** The root key, lowercase hex. */
    val root: String,
    val client: String,
    /** The grantee key's wire form. */
    val grantee: String,
    val scope: List<String>,
    val issuedAt: Long,
    val expiresAt: Long,
    val generation: Long,
    val revokedAt: Long? = null,
)

/** What the Sigil keeps per audience: its current rotation and generation, and its roots' public keys. */
data class GrantAudienceState(
    val rotation: Long = 0,
    val generation: Long = 0,
    /** Root keys by rotation, lowercase hex. */
    val roots: Map<Long, String> = emptyMap(),
)

/**
 * The grants this device issued, its per-audience state, and the request nonces it has accepted until their
 * requests expire. Everything here is public: no root key or seed material is ever stored.
 *
 * The file is created on first write and replaced atomically on every write.
 */
class GrantRoster(private val file: File) {
    internal class Contents(
        val records: MutableList<GrantRecord> = mutableListOf(),
        val audiences: MutableMap<String, GrantAudienceState> = linkedMapOf(),
        /** Request nonce (hex) to the request's expiry. */
        val nonces: MutableMap<String, Long> = linkedMapOf(),
    ) {
        fun copy(): Contents = Contents(records.toMutableList(), LinkedHashMap(audiences), LinkedHashMap(nonces))
    }

    private val mutex = Mutex()
    private var contents: Contents = if (file.exists()) decode(file.readText(Charsets.UTF_8)) else Contents()

    suspend fun records(): List<GrantRecord> = mutex.withLock { contents.records.toList() }

    suspend fun state(audience: GrantClientID): GrantAudienceState =
        mutex.withLock { contents.audiences[audience.value] ?: GrantAudienceState() }

    /** Grants an audience reports that this device never issued: under Passport's rule, a possible compromise. */
    suspend fun unknown(audience: GrantClientID, reported: List<String>): List<String> = mutex.withLock {
        val known = contents.records.filter { it.audience == audience.value }.map { it.id }.toSet()
        reported.filter { it !in known }
    }

    internal suspend fun update(change: (Contents) -> Unit) = mutex.withLock {
        val next = contents.copy()
        change(next)
        write(encode(next))
        contents = next
    }

    internal suspend fun snapshot(): Contents = mutex.withLock { contents.copy() }

    private fun write(text: String) {
        val directory = file.absoluteFile.parentFile
        directory.mkdirs()
        val temporary = File.createTempFile(file.name, ".tmp", directory)
        try {
            temporary.writeText(text, Charsets.UTF_8)
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        fun encode(contents: Contents): String = GrantJson.Obj(
            linkedMapOf(
                "records" to GrantJson.Arr(contents.records.map(::encodeRecord)),
                "audiences" to GrantJson.Obj(contents.audiences.mapValues { encodeState(it.value) }),
                "nonces" to GrantJson.Obj(contents.nonces.mapValues { GrantJson.number(it.value) }),
            ),
        ).encode()

        fun encodeRecord(record: GrantRecord): GrantJson {
            val fields = linkedMapOf(
                "id" to GrantJson.Str(record.id),
                "wire" to GrantJson.Str(record.wire),
                "audience" to GrantJson.Str(record.audience),
                "rotation" to GrantJson.number(record.rotation),
                "root" to GrantJson.Str(record.root),
                "client" to GrantJson.Str(record.client),
                "grantee" to GrantJson.Str(record.grantee),
                "scope" to GrantJson.Arr(record.scope.map { GrantJson.Str(it) }),
                "issuedAt" to GrantJson.number(record.issuedAt),
                "expiresAt" to GrantJson.number(record.expiresAt),
                "generation" to GrantJson.number(record.generation),
            )
            record.revokedAt?.let { fields["revokedAt"] = GrantJson.number(it) }
            return GrantJson.Obj(fields)
        }

        fun encodeState(state: GrantAudienceState): GrantJson = GrantJson.Obj(
            linkedMapOf(
                "rotation" to GrantJson.number(state.rotation),
                "generation" to GrantJson.number(state.generation),
                "roots" to GrantJson.Obj(state.roots.entries.associate { it.key.toString() to GrantJson.Str(it.value) }),
            ),
        )

        fun decode(text: String): Contents {
            val json = GrantJson.parse(text)
            val records = json["records"]?.array?.map(::decodeRecord) ?: corrupt()
            val audiences = json["audiences"]?.obj?.mapValues { decodeState(it.value) } ?: corrupt()
            val nonces = json["nonces"]?.obj?.mapValues { it.value.long ?: corrupt() } ?: corrupt()
            return Contents(records.toMutableList(), LinkedHashMap(audiences), LinkedHashMap(nonces))
        }

        fun decodeRecord(json: GrantJson): GrantRecord = GrantRecord(
            id = json["id"]?.string ?: corrupt(),
            wire = json["wire"]?.string ?: corrupt(),
            audience = json["audience"]?.string ?: corrupt(),
            rotation = json["rotation"]?.long ?: corrupt(),
            root = json["root"]?.string ?: corrupt(),
            client = json["client"]?.string ?: corrupt(),
            grantee = json["grantee"]?.string ?: corrupt(),
            scope = json["scope"]?.array?.map { it.string ?: corrupt() } ?: corrupt(),
            issuedAt = json["issuedAt"]?.long ?: corrupt(),
            expiresAt = json["expiresAt"]?.long ?: corrupt(),
            generation = json["generation"]?.long ?: corrupt(),
            revokedAt = json["revokedAt"]?.let { it.long ?: corrupt() },
        )

        fun decodeState(json: GrantJson): GrantAudienceState = GrantAudienceState(
            rotation = json["rotation"]?.long ?: corrupt(),
            generation = json["generation"]?.long ?: corrupt(),
            roots = json["roots"]?.obj?.entries?.associate { (key, value) ->
                (key.toLongOrNull() ?: corrupt()) to (value.string ?: corrupt())
            } ?: corrupt(),
        )

        fun corrupt(): Nothing = throw GrantRefusal.Malformed("not a grant roster")
    }
}
