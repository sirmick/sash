package s1m.hwfido2provider.vault

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The unlocked vault, held for the process.
 *
 * It is a singleton because three separate entry points need the same one: the
 * app's own UI, the credential provider answering a passkey-style request, and
 * the autofill service answering a form. A vault per caller would mean three
 * unlock prompts for one login.
 */
object VaultManager {
    private const val TAG = "latch"
    private const val DIR = "vault"

    /** Long enough for the sync folder to settle; see VAULT.md on maxConflicts. */
    private const val TOMBSTONE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

    private var vault: Vault? = null
    private var lastTouch = 0L

    /** Conflicts found by the most recent unlock, for the UI to report. */
    @Volatile
    var lastResolutions: List<Resolution> = emptyList()
        private set

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun exists(context: Context): Boolean = File(dir(context), "meta.json").isFile

    @Synchronized
    fun isUnlocked(context: Context): Boolean = peek(context) != null

    /**
     * The unlocked vault, or null. Touching it defers the auto-lock, so a user
     * working through a list of credentials is not interrupted by one.
     */
    @Synchronized
    fun require(context: Context): Vault? = peek(context)?.also { lastTouch = now() }

    @Synchronized
    fun create(context: Context, passphrase: CharArray): Vault {
        val store = VaultStore(context)
        val created = Vault.create(dir(context), passphrase, store.nodeId)
        adopt(context, created, passphrase)
        return created
    }

    /** Returns null when the passphrase is wrong. */
    @Synchronized
    fun unlock(context: Context, passphrase: CharArray): Vault? {
        val store = VaultStore(context)
        val opened = Vault.unlock(dir(context), passphrase, store.nodeId) ?: return null
        adopt(context, opened, passphrase)
        return opened
    }

    /**
     * Unlocks from the Keystore, for the daily path where the user has just
     * authenticated. Returns null if the wrapped key is missing or dead, which
     * is the signal to ask for the passphrase instead.
     */
    @Synchronized
    fun unlockWithKeystore(context: Context): Vault? {
        val store = VaultStore(context)
        val wrapped = store.wrappedKey ?: return null
        val key = Keystore.unwrap(wrapped) ?: run {
            // A dead wrapping key is not a transient failure. Clearing it stops
            // the UI offering a path that can never work again.
            Log.i(TAG, "vault: wrapped key unusable, clearing")
            store.wrappedKey = null
            return null
        }
        val opened = Vault.open(dir(context), key, store.nodeId) ?: return null
        settle(opened)
        vault = opened
        lastTouch = now()
        return opened
    }

    /**
     * Whether the screen-lock path is worth offering. Both halves matter: a
     * device with no screen lock cannot hold the key, and a vault that has never
     * been opened with the passphrase on *this* device has no key to unwrap.
     */
    fun canUseScreenLock(context: Context): Boolean =
        VaultStore(context).wrappedKey != null && Keystore.isAvailable()

    @Synchronized
    fun lock() {
        vault = null
        lastTouch = 0
    }

    private fun adopt(context: Context, opened: Vault, passphrase: CharArray) {
        settle(opened)
        vault = opened
        lastTouch = now()

        // Remember the key for the daily path, but only where the device can
        // actually gate it behind the user. Without a screen lock there is
        // nothing to authenticate against, and storing the key unguarded would
        // trade the whole point of the vault for convenience.
        val store = VaultStore(context)
        if (store.wrappedKey == null && Keystore.isAvailable()) {
            runCatching { store.wrappedKey = Keystore.wrap(opened.rawKey()) }
                .onFailure { Log.w(TAG, "vault: could not wrap key: ${it.message}") }
        }
    }

    /**
     * Resolve conflicts on unlock, never lazily: Syncthing refuses to make a
     * conflict of a conflict and deletes the old copy instead, so a conflict
     * file left sitting is a credential waiting to be dropped.
     */
    private fun settle(opened: Vault) {
        lastResolutions = runCatching { opened.resolveConflicts() }
            .onFailure { Log.w(TAG, "vault: conflict resolution failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (lastResolutions.isNotEmpty()) {
            Log.i(TAG, "vault: resolved ${lastResolutions.size} conflict(s)")
        }
        runCatching { opened.purgeTombstones(TOMBSTONE_TTL_MILLIS) }
    }

    private fun peek(context: Context): Vault? {
        val held = vault ?: return null
        val minutes = VaultStore(context).autoLockMinutes
        if (minutes > 0 && now() - lastTouch > minutes * 60_000L) {
            Log.i(TAG, "vault: auto-locked after $minutes min idle")
            lock()
            return null
        }
        return held
    }

    private fun now() = System.currentTimeMillis()
}
