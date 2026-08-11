package s1m.hwfido2provider.vault

import kotlinx.serialization.json.JsonObject

/** What resolving one conflict did, so the user can be told about it. */
data class Resolution(
    val id: String,
    val origin: String,
    /** True when both sides carried a different password — the case worth a prompt. */
    val passwordsDiffered: Boolean,
    /** False when the merge matched what was already on disk and nothing was written. */
    val rewrote: Boolean
)

/**
 * Merging the two sides of a conflict.
 *
 * There is no concurrency detection here, deliberately. Syncthing decides that
 * causally, with version vectors, and only writes a `.sync-conflict-` copy when
 * `InConflictWith` is true — ordered edits simply overwrite. So by the time this
 * runs, the two entries are known to be genuinely concurrent, and all that is
 * left is picking a winner and not losing anything on the way.
 */
object Conflicts {
    /**
     * `<uuid>.sync-conflict-<YYYYMMDD>-<HHMMSS>-<device>.bin`, as written by
     * Syncthing's `moveForConflict`.
     */
    private val PATTERN = Regex("""^(.+)\.sync-conflict-\d{8}-\d{6}-[^.]+\.bin$""")

    /** The entry id a conflict file belongs to, or null if the name is not one. */
    fun idOf(fileName: String): String? = PATTERN.matchEntire(fileName)?.groupValues?.get(1)

    fun isConflict(fileName: String): Boolean = idOf(fileName) != null

    /**
     * Higher clock wins; the loser's password is retired into history rather
     * than dropped. When two devices both rotate a credential, only one of them
     * is what the site now has — keeping the other is what makes the wrong
     * guess recoverable instead of gone.
     */
    fun merge(a: Entry, b: Entry): Entry {
        val winner = if (a.modified > b.modified) a else b
        val loser = if (winner === a) b else a

        // A delete that outranks an edit stays a delete. Resurrecting a password
        // is worse than losing a recent change to one, and a tombstone that
        // carried history forward would put back exactly what deleting removed.
        if (winner.deleted) return winner

        val retired = if (loser.deleted || loser.password == winner.password) {
            emptyList()
        } else {
            listOf(Past(loser.password, loser.modified))
        }

        val history = (winner.history + retired + loser.history)
            .groupBy { it.password }
            .map { (_, sightings) -> sightings.maxByOrNull { it.replaced }!! }
            .filter { it.password != winner.password }
            .sortedByDescending { it.replaced }

        // Fields a newer build wrote survive from both sides; ours win on
        // collision, since the winner is the newer write of a key we know.
        val extras = buildMap {
            putAll(loser.extras)
            putAll(winner.extras)
        }

        return winner.copy(history = history, extras = JsonObject(extras))
    }
}
