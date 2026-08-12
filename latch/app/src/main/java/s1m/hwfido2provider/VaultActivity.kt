package s1m.hwfido2provider

import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import s1m.hwfido2provider.ui.VaultActions
import s1m.hwfido2provider.ui.VaultScreen
import s1m.hwfido2provider.ui.VaultUi
import s1m.hwfido2provider.ui.theme.AppTheme
import s1m.hwfido2provider.vault.Entry
import kotlin.concurrent.thread
import s1m.hwfido2provider.vault.Pairing
import s1m.hwfido2provider.vault.SyncApi
import s1m.hwfido2provider.vault.SyncSetup
import s1m.hwfido2provider.vault.VaultManager

/**
 * The vault's own screens: create, unlock, list, edit.
 *
 * Separate from [MainActivity], which stays upstream's passkey settings screen.
 * Keeping them apart means the fork adds a file rather than rewriting one.
 */
class VaultActivity : ComponentActivity(), VaultActions {
    private var screen by mutableStateOf<VaultScreen>(VaultScreen.Unlock)
    private var syncing by mutableStateOf(false)
    private var syncStatus by mutableStateOf<String?>(null)
    private var needsPairing by mutableStateOf(false)
    /** A scanned pair link waiting for the vault to be unlocked. */
    private var pendingPair: Pair<String, String?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refresh()
        setContent { AppTheme { VaultUi(screen, this) } }
        // Our own passphrase field is a password field like any other, and
        // whichever provider is active cannot tell it apart from a login form.
        // Excluding the whole window stops it being offered for capture.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        handlePairLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairLink(intent)
    }

    override fun onResume() {
        super.onResume()
        // The vault may have auto-locked while we were away.
        refresh()
    }

    /**
     * Recomputes the screen from the vault's state.
     *
     * Screens the user navigated to are left alone. The sync poller calls this
     * once a second, and without the guard it would bounce you out of Edit or
     * Devices a moment after you opened them — which it did.
     */
    private fun refresh() {
        when (screen) {
            is VaultScreen.Edit, is VaultScreen.Pick, is VaultScreen.Sync -> return
            else -> Unit
        }
        screen = when {
            !VaultManager.exists(this) -> VaultScreen.Create
            else -> VaultManager.require(this)?.let {
                VaultScreen.ListEntries(
                    entries = it.list().filterNot { e -> e.deleted }.sortedBy { e -> e.origin },
                    resolutions = VaultManager.lastResolutions
                )
            } ?: VaultScreen.Unlock
        }
    }

    override fun create(passphrase: String): Boolean {
        VaultManager.create(this, passphrase.toCharArray())
        refresh()
        return true
    }

    override fun unlock(passphrase: String): Boolean {
        val opened = VaultManager.unlock(this, passphrase.toCharArray()) != null
        if (opened) {
            refresh()
            tryPendingPair()
        }
        return opened
    }

    /**
     * The daily path. The Keystore key is time-bound to a recent
     * authentication, so a successful prompt is what makes the wrapped key
     * usable — there is no CryptoObject to thread through.
     */
    override fun unlockWithScreenLock() {
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.latch_biometric_title))
            .setSubtitle(getString(R.string.latch_biometric_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (VaultManager.unlockWithKeystore(this@VaultActivity) == null) {
                        Log.i(TAG, "vault: keystore unlock unavailable, passphrase required")
                    }
                    refresh()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    Log.d(TAG, "vault: biometric error $code: $message")
                }
            }
        )
    }

    override fun canUseScreenLock(): Boolean = VaultManager.canUseScreenLock(this)

    override fun syncRunning(): Boolean = syncing

    override fun syncStatus(): String? = syncStatus

    override fun toggleSync() {
        if (syncing) {
            SyncService.stop(this)
            syncStatus = null
        } else {
            SyncService.start(this)
            pollSyncStatus()
        }
        syncing = !syncing
    }

    /**
     * Waits for the daemon to come up and reports what it says about itself.
     *
     * Off the main thread: it is a unix socket in our own sandbox rather than
     * the network, but it does not exist for the first second or two after
     * launch and blocking the UI on that would be visible.
     */
    private fun pollSyncStatus() = thread(isDaemon = true, name = "sync-status") {
        var sawDaemon = false
        // Keeps polling rather than reading once, because on a restored device
        // the vault does not exist when sync starts — it arrives a few seconds
        // later, and the screen has to notice and switch from Create to Unlock.
        repeat(POLL_SECONDS) {
            Thread.sleep(1_000)
            val id = SyncApi.deviceId(this)
            if (id == null) {
                if (!sawDaemon) return@repeat
            } else {
                sawDaemon = true
                val paired = SyncSetup.isPaired(this)
                val state = if (paired) SyncSetup.folderState(this) else null
                runOnUiThread {
                    needsPairing = !paired
                    syncStatus = "This device: ${id.take(7)}" +
                        (state?.let { s -> " — vault $s" } ?: "")
                    refresh()
                }
            }
        }
        if (!sawDaemon) runOnUiThread { syncStatus = getString(R.string.latch_sync_unreachable) }
    }

    override fun syncNeedsPairing(): Boolean = needsPairing

    override fun openSync() {
        screen = VaultScreen.Sync(getString(R.string.latch_sync_start), null, emptyList())
        thread(isDaemon = true, name = "sync-devices") {
            val id = SyncApi.deviceId(this) ?: return@thread
            // The address we advertise is best-effort: on a LAN the peer can
            // usually find us, and a wrong address is worse than none.
            val qr = runCatching { Pairing.qr(Pairing.uri(id, null)) }.getOrNull()
            val peers = SyncSetup.peers(this)
            runOnUiThread {
                if (screen is VaultScreen.Sync) screen = VaultScreen.Sync(id, qr, peers)
            }
        }
    }

    /**
     * A `latch://pair` link, which is what the other device's camera app opens
     * after scanning our QR. Pairing needs an unlocked vault, so a link that
     * arrives while locked is held until the unlock completes.
     */
    private fun handlePairLink(intent: Intent?) {
        val (peerId, address) = Pairing.parse(intent?.data) ?: return
        Log.i(TAG, "sync: pair link for ${peerId.take(7)}")
        if (!syncing) {
            SyncService.start(this)
            syncing = true
        }
        pendingPair = peerId to address
        tryPendingPair()
    }

    private fun tryPendingPair() {
        val (peerId, address) = pendingPair ?: return
        if (!VaultManager.exists(this)) return
        pendingPair = null
        thread(isDaemon = true, name = "sync-pair-link") {
            // The daemon may still be starting when the link arrives.
            repeat(20) {
                if (SyncApi.deviceId(this) != null) {
                    runCatching { SyncSetup.pair(this, peerId, address) }
                        .onFailure { Log.e(TAG, "sync: pairing failed: ${it.message}", it) }
                    pollSyncStatus()
                    return@thread
                }
                Thread.sleep(1_000)
            }
            Log.w(TAG, "sync: daemon never came up, pairing dropped")
        }
    }

    override fun pair(peerId: String, address: String) {
        if (peerId.isBlank()) return
        thread(isDaemon = true, name = "sync-pair") {
            runCatching { SyncSetup.pair(this, peerId, address) }
                .onFailure { Log.e(TAG, "sync: pairing failed: ${it.message}", it) }
            runOnUiThread { needsPairing = false }
            pollSyncStatus()
        }
    }

    override fun lock() {
        VaultManager.lock()
        refresh()
    }

    override fun save(
        entry: Entry?,
        origin: String,
        username: String,
        password: String,
        notes: String
    ) {
        val vault = VaultManager.require(this) ?: return refresh()
        if (entry == null) {
            vault.create(origin, username, password, notes)
        } else {
            vault.update(entry, password = password, username = username, notes = notes)
        }
        refresh()
    }

    override fun delete(entry: Entry) {
        VaultManager.require(this)?.delete(entry)
        refresh()
    }

    override fun edit(entry: Entry?) {
        screen = VaultScreen.Edit(entry)
    }

    override fun back() {
        screen = VaultScreen.Unlock
        refresh()
    }

    companion object {
        private const val TAG = "latch"

        /** How long to keep watching after sync starts, in seconds. */
        private const val POLL_SECONDS = 90
    }
}
