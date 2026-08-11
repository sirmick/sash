package s1m.hwfido2provider.vault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Conflicts are fabricated from ciphertext two real vault handles wrote, rather
 * than from hand-assembled bytes: the point of the exercise is that a file
 * Syncthing left behind opens and merges, and bytes we built ourselves would
 * prove only that our own assumptions are self-consistent.
 */
class ConflictsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val cheap = KdfParams(memoryKb = 256, iterations = 1, parallelism = 1)
    private val pass = "correct horse battery staple".toCharArray()

    @Before
    fun setUp() {
        root = tmp.newFolder("vault")
    }

    private fun deviceA() = Vault.unlock(root, pass, "device-a")!!
    private fun deviceB() = Vault.unlock(root, pass, "device-b")!!
    private fun live(id: String) = File(root, "entries/$id.bin")
    private fun conflict(id: String, device: String = "K7RZPXQ") =
        File(root, "entries/$id.sync-conflict-20260811-153000-$device.bin")

    /**
     * Both devices edit the same credential from the same starting point, and
     * B's write is the one Syncthing sets aside.
     */
    private fun bothEdited(aPassword: String, bPassword: String): Entry {
        val start = Vault.create(root, pass, "device-a", cheap)
            .create("chase.com", "mick", "original")

        deviceB().update(deviceB().get(start.id)!!, password = bPassword)
        val bSide = live(start.id).readBytes()

        deviceA().update(start, password = aPassword)
        conflict(start.id).writeBytes(bSide)
        return start
    }

    // ----- the exit criterion ---------------------------------------------

    @Test
    fun `a conflict resolves to the newer entry with the older kept`() {
        val start = bothEdited(aPassword = "a-side", bPassword = "b-side")

        val resolved = deviceA().resolveConflicts()

        assertEquals(1, resolved.size)
        assertEquals(start.id, resolved[0].id)
        assertTrue(resolved[0].passwordsDiffered)
        assertTrue(resolved[0].rewrote)

        val merged = deviceA().get(start.id)!!
        // A wrote second, so A wins; B's password survives in history rather
        // than being discarded, because only one of them is what the site has.
        assertEquals("a-side", merged.password)
        assertTrue(merged.history.any { it.password == "b-side" })
        assertTrue(merged.history.any { it.password == "original" })

        assertFalse("the conflict file must be cleared", conflict(start.id).exists())
    }

    @Test
    fun `resolving is idempotent when the plaintext already agrees`() {
        val start = bothEdited(aPassword = "a-side", bPassword = "b-side")
        deviceA().resolveConflicts()

        val merged = deviceA().get(start.id)!!
        val before = live(start.id).readBytes()

        // Re-seal the identical entry. A fresh nonce per encryption means these
        // bytes differ from `before` while the plaintext is the same -- which is
        // precisely the case that would loop if resolution compared ciphertext.
        deviceA().write(merged)
        val reSealed = live(start.id).readBytes()
        assertFalse("re-sealing should produce different bytes", reSealed.contentEquals(before))

        live(start.id).copyTo(conflict(start.id), overwrite = true)
        live(start.id).writeBytes(before)

        val resolved = deviceA().resolveConflicts()

        assertEquals(1, resolved.size)
        assertFalse("agreement must not be rewritten", resolved[0].rewrote)
        assertTrue(live(start.id).readBytes().contentEquals(before))
        assertFalse(conflict(start.id).exists())
    }

    @Test
    fun `a second pass has nothing left to do`() {
        val start = bothEdited(aPassword = "a-side", bPassword = "b-side")
        deviceA().resolveConflicts()
        assertTrue(deviceA().resolveConflicts().isEmpty())
        assertEquals("a-side", deviceA().get(start.id)!!.password)
    }

    // ----- deletes ---------------------------------------------------------

    @Test
    fun `a delete that outranks an edit stays deleted`() {
        val start = Vault.create(root, pass, "device-a", cheap)
            .create("chase.com", "mick", "original")

        deviceB().update(deviceB().get(start.id)!!, password = "b-side")
        val bSide = live(start.id).readBytes()

        deviceA().delete(start)
        conflict(start.id).writeBytes(bSide)

        deviceA().resolveConflicts()

        val merged = deviceA().get(start.id)!!
        assertTrue(merged.deleted)
        // A tombstone that carried history forward would put back exactly what
        // deleting removed.
        assertTrue(merged.history.isEmpty())
        assertEquals("", merged.password)
        assertEquals("", merged.origin)
    }

    @Test
    fun `an edit that outranks a delete resurrects nothing it should not`() {
        val start = Vault.create(root, pass, "device-a", cheap)
            .create("chase.com", "mick", "original")

        deviceB().delete(deviceB().get(start.id)!!)
        val bTomb = live(start.id).readBytes()

        deviceA().update(start, password = "a-side")
        conflict(start.id).writeBytes(bTomb)

        deviceA().resolveConflicts()

        val merged = deviceA().get(start.id)!!
        assertFalse(merged.deleted)
        assertEquals("a-side", merged.password)
        // The tombstone carried no password, so there is nothing of it to keep.
        assertTrue(merged.history.none { it.password.isEmpty() })
    }

    // ----- edges -----------------------------------------------------------

    @Test
    fun `a conflict for an entry we do not have is adopted`() {
        val start = Vault.create(root, pass, "device-a", cheap)
            .create("chase.com", "mick", "original")
        val bytes = live(start.id).readBytes()
        live(start.id).delete()
        conflict(start.id).writeBytes(bytes)

        deviceA().resolveConflicts()

        assertEquals("original", deviceA().get(start.id)!!.password)
    }

    @Test
    fun `a conflict we cannot decrypt is left alone rather than destroyed`() {
        val start = Vault.create(root, pass, "device-a", cheap)
            .create("chase.com", "mick", "original")
        conflict(start.id).writeBytes(byteArrayOf(9, 9, 9))

        assertTrue(deviceA().resolveConflicts().isEmpty())
        assertTrue("an unreadable conflict is evidence, not litter", conflict(start.id).exists())
        assertEquals("original", deviceA().get(start.id)!!.password)
    }

    @Test
    fun `conflict copies are not mistaken for entries`() {
        val start = bothEdited(aPassword = "a-side", bPassword = "b-side")
        // list() must not return the losing side as though it were a credential
        // of its own, or the UI shows every conflict twice.
        assertEquals(1, deviceA().list().size)
        assertEquals(start.id, deviceA().list()[0].id)
    }

    // ----- the filename grammar --------------------------------------------

    @Test
    fun `syncthing conflict names are recognised and entry names are not`() {
        val id = "11111111-1111-1111-1111-111111111111"
        assertEquals(id, Conflicts.idOf("$id.sync-conflict-20260811-153000-K7RZPXQ.bin"))
        assertNull(Conflicts.idOf("$id.bin"))
        assertNull(Conflicts.idOf("meta.json"))
        assertNull(Conflicts.idOf("$id.sync-conflict-nonsense.bin"))
    }

    // ----- the merge itself -------------------------------------------------

    @Test
    fun `history is unioned across both sides and deduplicated`() {
        val a = Entry(
            id = "x", origin = "chase.com", username = "mick", password = "newest",
            created = Hlc(1, 0, "a"), modified = Hlc(30, 0, "a"),
            history = listOf(Past("shared", Hlc(10, 0, "a")), Past("only-a", Hlc(20, 0, "a")))
        )
        val b = a.copy(
            password = "b-side", modified = Hlc(25, 0, "b"),
            history = listOf(Past("shared", Hlc(11, 0, "b")), Past("only-b", Hlc(21, 0, "b")))
        )

        val merged = Conflicts.merge(a, b)

        assertEquals("newest", merged.password)
        assertEquals(
            listOf("only-b", "only-a", "shared", "b-side").sorted(),
            merged.history.map { it.password }.sorted()
        )
        // The later sighting of a shared password is the one kept.
        assertEquals(Hlc(11, 0, "b"), merged.history.first { it.password == "shared" }.replaced)
        // Newest first, so the UI can show recency without re-sorting.
        assertEquals(merged.history, merged.history.sortedByDescending { it.replaced })
    }

    @Test
    fun `merging is symmetric`() {
        val a = Entry(
            id = "x", origin = "chase.com", username = "mick", password = "a-side",
            created = Hlc(1, 0, "a"), modified = Hlc(30, 0, "a")
        )
        val b = a.copy(password = "b-side", modified = Hlc(25, 0, "b"))
        // Every device resolves independently and must reach the same answer,
        // or they trade rewrites forever.
        assertEquals(Conflicts.merge(a, b), Conflicts.merge(b, a))
    }

    @Test
    fun `the winner's password never appears in its own history`() {
        val a = Entry(
            id = "x", origin = "chase.com", username = "mick", password = "same",
            created = Hlc(1, 0, "a"), modified = Hlc(30, 0, "a")
        )
        val b = a.copy(modified = Hlc(25, 0, "b"), history = listOf(Past("same", Hlc(5, 0, "b"))))
        assertTrue(Conflicts.merge(a, b).history.isEmpty())
    }
}
