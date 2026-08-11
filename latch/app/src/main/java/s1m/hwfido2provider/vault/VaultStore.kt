package s1m.hwfido2provider.vault

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Small persistent settings for the vault.
 *
 * Deliberately separate from upstream's [s1m.hwfido2provider.Store] rather than
 * added to it: every upstream file we leave alone is a file `git subtree pull`
 * cannot conflict on.
 */
class VaultStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * This device's tie-breaker, stable for the life of the install. A device
     * that changes it stops breaking ties consistently with its own past
     * writes, so it is generated once and never rewritten.
     */
    val nodeId: String
        get() = prefs.getString(NODE_ID, null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit { putString(NODE_ID, it) }
        }

    /** The derived key, sealed by an Android Keystore key that needs the user. */
    var wrappedKey: String?
        get() = prefs.getString(WRAPPED_KEY, null)
        set(value) = prefs.edit { if (value == null) remove(WRAPPED_KEY) else putString(WRAPPED_KEY, value) }

    /** Minutes of inactivity before the vault locks itself. 0 disables. */
    var autoLockMinutes: Int
        get() = prefs.getInt(AUTO_LOCK, 5)
        set(value) = prefs.edit { putInt(AUTO_LOCK, value) }

    companion object {
        private const val PREFS = "latch"
        private const val NODE_ID = "node_id"
        private const val WRAPPED_KEY = "wrapped_key"
        private const val AUTO_LOCK = "auto_lock_minutes"
    }
}
