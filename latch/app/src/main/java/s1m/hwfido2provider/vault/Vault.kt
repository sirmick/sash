package s1m.hwfido2provider.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Vault metadata. Not secret, and deliberately so: an attacker holding the
 * folder learns the KDF cost and a salt, neither of which helps them, while a
 * device with different defaults can still open a vault another device made.
 *
 * [check] is a sealed constant. Without it an empty vault would accept any
 * passphrase, because there would be nothing to fail to decrypt.
 */
@Serializable
data class VaultMeta(
    val schema: Int,
    val kdf: KdfParams,
    val salt: String,
    val check: String
)

class VaultException(message: String) : Exception(message)

/**
 * One credential per file, in a folder Syncthing carries.
 *
 * ```
 * root/
 *   meta.json          kdf params, salt, schema — not secret
 *   .stignore          keeps our temp directory out of the sync
 *   .tmp/              scratch for atomic writes, same filesystem
 *   entries/<uuid>.bin one credential, AEAD-encrypted
 * ```
 *
 * Filenames are random UUIDs, never derived from the site. `pass` is fairly
 * criticised for the opposite: deterministic names leak which sites you have
 * accounts on to anyone holding the folder. Random names cost a full read on
 * unlock and leak nothing.
 */
class Vault private constructor(
    private val root: File,
    private val key: ByteArray,
    val meta: VaultMeta,
    private val clock: Clock
) {
    private val entriesDir = File(root, ENTRIES)
    private val tmpDir = File(root, TMP)

    /**
     * Every entry, tombstones included — callers that want live credentials
     * filter on [Entry.deleted]. Unreadable files are skipped rather than
     * thrown on, so one corrupt file cannot make the whole vault unopenable.
     */
    fun list(): List<Entry> {
        // Conflict copies end in .bin too. They are excluded by name rather than
        // left to fail the AAD check: that check does reject them today, but a
        // credential list is not the place to depend on it.
        val files = entriesDir.listFiles { f: File ->
            f.isFile && f.name.endsWith(SUFFIX) && !Conflicts.isConflict(f.name)
        } ?: return emptyList()
        return files.mapNotNull { read(it) }.onEach { clock.observe(it.modified) }
    }

    fun get(id: String): Entry? = read(File(entriesDir, id + SUFFIX))

    /** Creates a credential and returns it, stamped. */
    fun create(origin: String, username: String, password: String, notes: String = ""): Entry {
        val stamp = clock.tick()
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            origin = origin,
            username = username,
            password = password,
            notes = notes,
            created = stamp,
            modified = stamp
        )
        write(entry)
        return entry
    }

    /**
     * Replaces a credential, retiring the outgoing password into history rather
     * than discarding it. When two devices both rotate, only one is what the
     * site now has; keeping the loser is what makes the wrong guess survivable.
     */
    fun update(
        entry: Entry,
        password: String = entry.password,
        username: String = entry.username,
        notes: String = entry.notes
    ): Entry {
        val stamp = clock.tick()
        val history =
            if (password == entry.password) {
                entry.history
            } else {
                listOf(Past(entry.password, stamp)) + entry.history
            }
        val next = entry.copy(
            username = username,
            password = password,
            notes = notes,
            modified = stamp,
            history = history
        )
        write(next)
        return next
    }

    /**
     * Soft-deletes. Syncthing propagates real deletions fine, but a delete on
     * one device racing an edit on another can resurrect the entry, and a
     * resurrected password is worse than a stale one. A tombstone also means we
     * never issue a delete for Syncthing to race an edit against, so the
     * delete-versus-edit conflict simply cannot arise.
     *
     * The tombstone keeps nothing but its identity. Not even the origin: which
     * sites you hold accounts on is exactly the metadata random filenames exist
     * to withhold.
     */
    fun delete(entry: Entry): Entry {
        val tomb = Entry(
            id = entry.id,
            origin = "",
            username = "",
            password = "",
            notes = "",
            created = entry.created,
            modified = clock.tick(),
            deleted = true
        )
        write(tomb)
        return tomb
    }

    /**
     * Resolves every conflict Syncthing has left in the folder.
     *
     * Call this on unlock, not lazily. Syncthing refuses to make a conflict of a
     * conflict — `moveForConflict` logs "Conflict on existing conflict copy" and
     * *removes* the old copy instead — so an unresolved conflict file is not
     * safe indefinitely.
     */
    fun resolveConflicts(): List<Resolution> {
        val files = entriesDir.listFiles { f: File -> f.isFile && Conflicts.isConflict(f.name) }
            ?: return emptyList()

        return files.mapNotNull { file ->
            val id = Conflicts.idOf(file.name) ?: return@mapNotNull null
            // The ciphertext was sealed under the entry's id, not the conflict
            // copy's name, so it is the id that authenticates it.
            val plain = Crypto.open(key, file.readBytes(), id.encodeToByteArray())
                ?: return@mapNotNull null
            val theirs = runCatching { EntryCodec.decode(plain) }.getOrNull()
                ?: return@mapNotNull null

            val ours = get(id)
            val merged = if (ours == null) theirs else Conflicts.merge(ours, theirs)
            clock.observe(merged.modified)

            // Only write when the *plaintext* changed. A fresh nonce per seal
            // means re-encrypting an unchanged entry produces different bytes,
            // so two devices both resolving would manufacture a new conflict out
            // of agreement, forever. Comparing entries makes this idempotent.
            val rewrote = merged != ours
            if (rewrote) write(merged)
            file.delete()

            Resolution(
                id = id,
                origin = merged.origin,
                passwordsDiffered = ours != null &&
                    !ours.deleted && !theirs.deleted &&
                    ours.password != theirs.password,
                rewrote = rewrote
            )
        }
    }

    /** Removes tombstones that have had long enough to reach every device. */
    fun purgeTombstones(olderThanMillis: Long, now: Long = System.currentTimeMillis()) {
        list().filter { it.deleted && now - it.modified.wall > olderThanMillis }
            .forEach { File(entriesDir, it.id + SUFFIX).delete() }
    }

    internal fun write(entry: Entry) {
        val sealed = Crypto.seal(key, EntryCodec.encode(entry), entry.id.encodeToByteArray())
        writeAtomic(File(entriesDir, entry.id + SUFFIX), sealed)
    }

    private fun read(file: File): Entry? {
        if (!file.isFile) return null
        val id = file.name.removeSuffix(SUFFIX)
        val plain = Crypto.open(key, file.readBytes(), id.encodeToByteArray()) ?: return null
        return runCatching { EntryCodec.decode(plain) }.getOrNull()
    }

    /**
     * Temp file, fsync, rename. A half-written credential that replaced a whole
     * one would be indistinguishable from a corrupt one.
     *
     * The temp file lives in [TMP] rather than beside its target because
     * Syncthing would otherwise sync it — and a partial write propagating to
     * another device is the failure this is meant to prevent, arriving by post.
     */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        tmpDir.mkdirs()
        val tmp = File(tmpDir, target.name + "." + UUID.randomUUID())
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("rename failed: $tmp -> $target")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val SCHEMA = 1
        private const val ENTRIES = "entries"
        private const val TMP = ".tmp"
        private const val SUFFIX = ".bin"
        private const val META = "meta.json"
        private const val CHECK_PLAINTEXT = "latch"

        // Our scratch directory only. Never sync-conflict: those files are the
        // losing side of a concurrent edit, and ignoring them discards passwords.
        private const val STIGNORE = "/$TMP\n"

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun create(root: File, passphrase: CharArray, node: String, kdf: KdfParams = KdfParams()): Vault {
            if (File(root, META).exists()) throw VaultException("vault already exists at $root")
            root.mkdirs()
            File(root, ENTRIES).mkdirs()
            val salt = Crypto.randomBytes(Crypto.SALT_BYTES)
            val key = Crypto.deriveKey(passphrase, salt, kdf)
            val meta = VaultMeta(
                schema = SCHEMA,
                kdf = kdf,
                salt = b64(salt),
                check = b64(
                    Crypto.seal(
                        key,
                        CHECK_PLAINTEXT.encodeToByteArray(),
                        CHECK_PLAINTEXT.encodeToByteArray()
                    )
                )
            )
            File(root, META).writeText(json.encodeToString(meta))
            File(root, ".stignore").writeText(STIGNORE)
            return Vault(root, key, meta, Clock(node))
        }

        /** Returns null when the passphrase is wrong. Throws when the vault is unusable. */
        fun unlock(root: File, passphrase: CharArray, node: String): Vault? {
            val metaFile = File(root, META)
            if (!metaFile.isFile) throw VaultException("no vault at $root")
            val meta = runCatching { json.decodeFromString<VaultMeta>(metaFile.readText()) }
                .getOrElse { throw VaultException("meta.json is unreadable: ${it.message}") }
            // Refuse a document a newer build wrote rather than opening it
            // partially — the next write would destroy what we could not read.
            if (meta.schema > SCHEMA) {
                throw VaultException("vault schema ${meta.schema} is newer than this build ($SCHEMA)")
            }
            val key = Crypto.deriveKey(passphrase, unb64(meta.salt), meta.kdf)
            Crypto.open(key, unb64(meta.check), CHECK_PLAINTEXT.encodeToByteArray())
                ?: return null
            File(root, ENTRIES).mkdirs()
            return Vault(root, key, meta, Clock(node))
        }

        // java.util rather than android.util: it exists from API 26 (we require
        // 34) and works unchanged under a JVM unit test, where android.util.Base64
        // is a stub that throws.
        private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    }
}
