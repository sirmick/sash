package s1m.hwfido2provider

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
import s1m.hwfido2provider.vault.VaultManager

/**
 * The vault's own screens: create, unlock, list, edit.
 *
 * Separate from [MainActivity], which stays upstream's passkey settings screen.
 * Keeping them apart means the fork adds a file rather than rewriting one.
 */
class VaultActivity : ComponentActivity(), VaultActions {
    private var screen by mutableStateOf<VaultScreen>(VaultScreen.Unlock)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refresh()
        setContent { AppTheme { VaultUi(screen, this) } }
        // Our own passphrase field is a password field like any other, and
        // whichever provider is active cannot tell it apart from a login form.
        // Excluding the whole window stops it being offered for capture.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onResume() {
        super.onResume()
        // The vault may have auto-locked while we were away.
        refresh()
    }

    private fun refresh() {
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
        if (opened) refresh()
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

    override fun back() = refresh()

    companion object {
        private const val TAG = "latch"
    }
}
