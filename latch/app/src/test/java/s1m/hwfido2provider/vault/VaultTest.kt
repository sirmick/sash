package s1m.hwfido2provider.vault

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The consequences live here, so this over-tests rather than under-tests.
 *
 * Argon2 is deliberately cheap in these tests: the parameters are a property of
 * the vault, stored in meta.json, precisely so they can differ.
 */
class VaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val cheap = KdfParams(memoryKb = 256, iterations = 1, parallelism = 1)
    private val pass = "correct horse battery staple".toCharArray()

    @Before
    fun setUp() {
        root = tmp.newFolder("vault")
    }

    private fun create(node: String = "device-a") = Vault.create(root, pass, node, cheap)
    private fun unlock(node: String = "device-a", p: CharArray = pass) = Vault.unlock(root, p, node)

    // ----- round trip -----------------------------------------------------

    @Test
    fun `a credential survives a lock and unlock`() {
        val id = create().create("chase.com", "mick", "hunter2", "checking").id

        val entry = unlock()!!.get(id)!!
        assertEquals("chase.com", entry.origin)
        assertEquals("mick", entry.username)
        assertEquals("hunter2", entry.password)
        assertEquals("checking", entry.notes)
        assertFalse(entry.deleted)
    }

    @Test
    fun `entries are listed and keyed by id, not position`() {
        val vault = create()
        val a = vault.create("chase.com", "mick", "one")
        val b = vault.create("schwab.com", "mick", "two")

        val byId = unlock()!!.list().associateBy { it.id }
        assertEquals(2, byId.size)
        assertEquals("one", byId[a.id]!!.password)
        assertEquals("two", byId[b.id]!!.password)
    }

    // ----- the passphrase is the vault ------------------------------------

    @Test
    fun `the wrong passphrase fails on an empty vault`() {
        create()
        // Without the sealed check value in meta.json this would succeed:
        // there would be nothing to fail to decrypt.
        assertNull(unlock(p = "wrong".toCharArray()))
    }

    @Test
    fun `the wrong passphrase fails on a populated vault`() {
        create().create("chase.com", "mick", "hunter2")
        assertNull(unlock(p = "wrong".toCharArray()))
    }

    @Test
    fun `the right passphrase still works after a wrong attempt`() {
        val id = create().create("chase.com", "mick", "hunter2").id
        assertNull(unlock(p = "wrong".toCharArray()))
        assertEquals("hunter2", unlock()!!.get(id)!!.password)
    }

    // ----- tamper ---------------------------------------------------------

    @Test
    fun `an altered ciphertext does not open`() {
        val id = create().create("chase.com", "mick", "hunter2").id

        val file = File(root, "entries/$id.bin")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(unlock()!!.get(id))
    }

    @Test
    fun `a credential renamed over another does not open`() {
        val vault = create()
        val a = vault.create("chase.com", "mick", "one")
        val b = vault.create("schwab.com", "mick", "two")

        // The entry id is the associated data, so a ciphertext moved to another
        // filename fails to authenticate rather than silently impersonating it.
        File(root, "entries/${a.id}.bin").copyTo(File(root, "entries/${b.id}.bin"), overwrite = true)

        assertNull(unlock()!!.get(b.id))
    }

    @Test
    fun `one unreadable file does not make the vault unopenable`() {
        val vault = create()
        val good = vault.create("chase.com", "mick", "one")
        vault.create("schwab.com", "mick", "two")

        File(root, "entries/${vault.create("x.com", "y", "z").id}.bin").writeBytes(byteArrayOf(1, 2, 3))

        val listed = unlock()!!.list()
        assertEquals(2, listed.size)
        assertTrue(listed.any { it.id == good.id })
    }

    // ----- tombstones -----------------------------------------------------

    @Test
    fun `a tombstone survives, and keeps nothing but its identity`() {
        val vault = create()
        val entry = vault.create("chase.com", "mick", "hunter2", "checking")
        vault.delete(entry)

        val tomb = unlock()!!.get(entry.id)!!
        assertTrue(tomb.deleted)
        assertEquals(entry.id, tomb.id)
        assertEquals(entry.created, tomb.created)
        // Which sites you hold accounts on is exactly what random filenames
        // exist to withhold; a tombstone must not put it back.
        assertEquals("", tomb.origin)
        assertEquals("", tomb.username)
        assertEquals("", tomb.password)
        assertEquals("", tomb.notes)
        assertTrue(tomb.history.isEmpty())
    }

    @Test
    fun `a tombstone outranks the entry it replaced`() {
        val vault = create()
        val entry = vault.create("chase.com", "mick", "hunter2")
        val tomb = vault.delete(entry)
        assertTrue(tomb.modified > entry.modified)
    }

    @Test
    fun `purge removes old tombstones and leaves fresh ones`() {
        val vault = create()
        val old = vault.create("chase.com", "mick", "one")
        val fresh = vault.create("schwab.com", "mick", "two")
        val live = vault.create("fidelity.com", "mick", "three")
        vault.delete(old)
        vault.delete(fresh)

        val thirtyDays = 30L * 24 * 60 * 60 * 1000
        // Pretend we are far enough in the future for `old` to have reached
        // every device, by moving the horizon rather than the clock.
        vault.purgeTombstones(thirtyDays, now = System.currentTimeMillis() + thirtyDays + 1)

        val ids = unlock()!!.list().map { it.id }.toSet()
        assertFalse(ids.contains(old.id))
        assertFalse(ids.contains(fresh.id))
        assertTrue(ids.contains(live.id))
    }

    // ----- history --------------------------------------------------------

    @Test
    fun `rotating a password retires the old one into history`() {
        val vault = create()
        val entry = vault.create("chase.com", "mick", "one")
        val rotated = vault.update(entry, password = "two")

        assertEquals("two", rotated.password)
        assertEquals(1, rotated.history.size)
        assertEquals("one", rotated.history[0].password)
        assertEquals("one", unlock()!!.get(entry.id)!!.history[0].password)
    }

    @Test
    fun `editing something other than the password does not touch history`() {
        val vault = create()
        val entry = vault.create("chase.com", "mick", "one")
        val edited = vault.update(entry, notes = "checking")

        assertEquals("checking", edited.notes)
        assertTrue(edited.history.isEmpty())
    }

    // ----- forward compatibility ------------------------------------------

    @Test
    fun `fields written by a newer build are round-tripped, not dropped`() {
        // Without this the older of two devices silently deletes the newer
        // one's data every time it writes.
        val entry = Entry(
            id = "11111111-1111-1111-1111-111111111111",
            origin = "chase.com",
            username = "mick",
            password = "hunter2",
            created = Hlc(1, 0, "a"),
            modified = Hlc(1, 0, "a"),
            extras = JsonObject(mapOf("totpSecret" to JsonPrimitive("JBSWY3DP")))
        )

        val decoded = EntryCodec.decode(EntryCodec.encode(entry))
        assertEquals(JsonPrimitive("JBSWY3DP"), decoded.extras["totpSecret"])
        assertEquals("hunter2", decoded.password)
    }

    @Test
    fun `kdf parameters are written out in full, not left to defaults`() {
        Vault.create(root, pass, "device-a", KdfParams(memoryKb = 512, iterations = 2, parallelism = 1))
        val meta = File(root, "meta.json").readText()

        // A KdfParams left at its defaults serialises to `{}` unless defaults
        // are encoded. It round-trips today and stops round-tripping the day we
        // raise the cost, which is the one moment this has to work.
        assertTrue(meta, meta.contains("\"memoryKb\": 512"))
        assertTrue(meta, meta.contains("\"iterations\": 2"))
        assertTrue(meta, meta.contains("\"algo\": \"argon2id\""))
    }

    @Test
    fun `a vault opens with the parameters it was made with, not this build's defaults`() {
        val id = Vault.create(root, pass, "device-a", KdfParams(memoryKb = 512, iterations = 2))
            .create("chase.com", "mick", "hunter2").id
        // unlock() takes no KdfParams: it must use what meta.json says.
        assertEquals("hunter2", unlock()!!.get(id)!!.password)
    }

    @Test
    fun `a vault from a newer schema is refused rather than opened`() {
        create()
        val meta = File(root, "meta.json")
        meta.writeText(meta.readText().replace("\"schema\": 1", "\"schema\": 99"))

        val thrown = runCatching { unlock() }.exceptionOrNull()
        assertTrue(thrown is VaultException)
        assertTrue(thrown!!.message!!.contains("newer"))
    }

    @Test
    fun `creating over an existing vault is refused`() {
        create()
        assertTrue(runCatching { create() }.exceptionOrNull() is VaultException)
    }

    // ----- writes are atomic ----------------------------------------------

    @Test
    fun `temp files never land in the synced tree`() {
        val vault = create()
        vault.create("chase.com", "mick", "one")

        val stray = File(root, "entries").listFiles()!!.filterNot { it.name.endsWith(".bin") }
        assertTrue("stray files in entries/: $stray", stray.isEmpty())
        // ...and the scratch directory is ignored, while sync-conflict is not:
        // those are the losing side of a concurrent edit and dropping them
        // discards passwords.
        val stignore = File(root, ".stignore").readText()
        assertTrue(stignore.contains("/.tmp"))
        assertFalse(stignore.contains("sync-conflict"))
    }

    // ----- the clock -------------------------------------------------------

    @Test
    fun `stamps stay monotonic when the wall clock stands still`() {
        val clock = Clock("device-a", now = { 1000L })
        val stamps = (1..5).map { clock.tick() }
        assertTrue(stamps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `stamps stay monotonic when the wall clock goes backwards`() {
        var t = 5000L
        val clock = Clock("device-a", now = { t })
        val first = clock.tick()
        t = 1000L
        assertTrue(clock.tick() > first)
    }

    @Test
    fun `observing a device that is ahead makes our next stamp outrank it`() {
        val clock = Clock("device-a", now = { 1000L })
        val theirs = Hlc(9999L, 3, "device-b")
        clock.observe(theirs)
        assertTrue(clock.tick() > theirs)
    }

    @Test
    fun `two devices at the same instant still order totally`() {
        val a = Clock("device-a", now = { 1000L }).tick()
        val b = Clock("device-b", now = { 1000L }).tick()
        assertTrue(a != b)
        assertTrue((a < b) != (b < a))
    }

    @Test
    fun `unlocking observes the clocks of entries other devices wrote`() {
        create("device-a").create("chase.com", "mick", "one")

        // A second device whose wall clock is far behind must still be able to
        // write an edit that wins, or its changes look stale forever.
        val behind = Vault.unlock(root, pass, "device-b")!!
        val entry = behind.list().first()
        val rotated = behind.update(entry, password = "two")
        assertTrue(rotated.modified > entry.modified)
        assertNotNull(unlock()!!.get(entry.id))
    }
}
