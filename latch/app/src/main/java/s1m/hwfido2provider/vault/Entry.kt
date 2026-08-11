package s1m.hwfido2provider.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** A password this entry used to have, kept when it was replaced. */
@Serializable
data class Past(val password: String, val replaced: Hlc)

/**
 * One credential: one file, one entry.
 *
 * [origin] is the join to pane's registry — the same origin table that decides
 * which app may go where decides which credential is offered. If the two ever
 * disagree, autofill offers credentials on a surface the fence considers
 * foreign and the fence becomes decorative.
 *
 * [history] is why conflict resolution can be lossless. When two devices both
 * rotate a password, only one of them is what the site now has; keeping the
 * loser means the wrong guess is recoverable rather than gone.
 *
 * [extras] holds fields written by a build newer than this one. Preserving them
 * is cheap now and impossible later: without it, the older of two devices
 * silently deletes the newer one's data every time it writes.
 */
data class Entry(
    val id: String,
    val origin: String,
    val username: String,
    val password: String,
    val notes: String = "",
    val created: Hlc,
    val modified: Hlc,
    val deleted: Boolean = false,
    val history: List<Past> = emptyList(),
    val extras: JsonObject = JsonObject(emptyMap())
)

/**
 * Entry to JSON and back, preserving unknown fields.
 *
 * kotlinx.serialization offers only two behaviours for a field it does not
 * know: fail, or drop. Neither round-trips, so the known fields go through a
 * generated serializer and everything else is carried alongside verbatim.
 */
object EntryCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Wire(
        val id: String,
        val origin: String,
        val username: String,
        val password: String,
        val notes: String = "",
        val created: Hlc,
        val modified: Hlc,
        val deleted: Boolean = false,
        val history: List<Past> = emptyList()
    )

    private val KNOWN = setOf(
        "id", "origin", "username", "password", "notes",
        "created", "modified", "deleted", "history"
    )

    fun encode(entry: Entry): ByteArray {
        val wire = Wire(
            id = entry.id,
            origin = entry.origin,
            username = entry.username,
            password = entry.password,
            notes = entry.notes,
            created = entry.created,
            modified = entry.modified,
            deleted = entry.deleted,
            history = entry.history
        )
        val merged = json.encodeToJsonElement(wire).jsonObject.toMutableMap()
        // Ours win on collision: a key we know is a key we own.
        entry.extras.forEach { (k, v) -> if (k !in KNOWN) merged.putIfAbsent(k, v) }
        return json.encodeToString(JsonObject(merged)).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): Entry {
        val obj = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val wire = json.decodeFromJsonElement(Wire.serializer(), obj)
        val extras = obj.filterKeys { it !in KNOWN }
        return Entry(
            id = wire.id,
            origin = wire.origin,
            username = wire.username,
            password = wire.password,
            notes = wire.notes,
            created = wire.created,
            modified = wire.modified,
            deleted = wire.deleted,
            history = wire.history,
            extras = JsonObject(extras)
        )
    }
}
