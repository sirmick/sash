package s1m.hwfido2provider

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.provider.PendingIntentHandler
import s1m.hwfido2provider.ui.VaultActions
import s1m.hwfido2provider.ui.VaultScreen
import s1m.hwfido2provider.ui.VaultUi
import s1m.hwfido2provider.ui.theme.AppTheme
import s1m.hwfido2provider.vault.Entry
import s1m.hwfido2provider.vault.FormFields
import s1m.hwfido2provider.vault.PasswordEntries
import s1m.hwfido2provider.vault.VaultManager

/**
 * The ceremony behind a password entry: unlock if needed, choose, hand back.
 *
 * The system launches this from the PendingIntent attached to whichever entry
 * the user tapped in the credential sheet. It is the only place a password
 * leaves the vault, and it always has a human in front of it.
 */
class PasswordActivity : ComponentActivity(), VaultActions {
    private var screen by mutableStateOf<VaultScreen>(VaultScreen.Unlock)
    private val creating: CreatePasswordRequest? by lazy {
        (PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)?.callingRequest
            as? CreatePasswordRequest)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refresh()
        setContent { AppTheme { VaultUi(screen, this) } }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun refresh() {
        if (!VaultManager.exists(this)) {
            screen = VaultScreen.Create
            return
        }
        val vault = VaultManager.require(this)
        if (vault == null) {
            screen = VaultScreen.Unlock
            return
        }

        creating?.let { request ->
            // A save request carries the credential already; there is nothing to
            // choose, so store it and get out of the way.
            val origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()
            vault.create(origin, request.id, request.password)
            Log.i(TAG, "vault: saved password for ${origin.ifEmpty { "(no origin)" }}")
            setResult(
                Activity.RESULT_OK,
                Intent().also { PendingIntentHandler.setCreateCredentialResponse(it, CreatePasswordResponse()) }
            )
            finish()
            return
        }

        // An entry id means the user already picked in the system sheet.
        intent.getStringExtra(EXTRA_ENTRY_ID)?.let { id ->
            vault.get(id)?.let { return choose(it) }
        }

        screen = VaultScreen.Pick(
            prompt = getString(R.string.latch_title),
            entries = PasswordEntries.matching(vault, intent.getStringExtra(EXTRA_ORIGIN))
        )
    }

    override fun choose(entry: Entry) {
        autofillForm()?.let { return chooseForAutofill(entry, it) }

        val response = GetCredentialResponse(PasswordCredential(entry.username, entry.password))
        setResult(
            Activity.RESULT_OK,
            Intent().also { PendingIntentHandler.setGetCredentialResponse(it, response) }
        )
        finish()
    }

    /**
     * The autofill framework expects an authenticated request to come back as a
     * Dataset, not a credential — the fields were identified before we were
     * launched, so the ids travel here in the intent and come back filled.
     */
    private fun chooseForAutofill(entry: Entry, form: FormFields) {
        val presentation = RemoteViews(packageName, R.layout.latch_autofill_entry).apply {
            setTextViewText(R.id.latch_autofill_text, entry.username)
        }
        val dataset = Dataset.Builder(presentation).apply {
            form.username?.let { setValue(it, AutofillValue.forText(entry.username)) }
            form.password?.let { setValue(it, AutofillValue.forText(entry.password)) }
        }.build()

        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
        )
        finish()
    }

    private fun autofillForm(): FormFields? {
        val username = intent.getParcelableExtra(EXTRA_AUTOFILL_USERNAME, AutofillId::class.java)
        val password = intent.getParcelableExtra(EXTRA_AUTOFILL_PASSWORD, AutofillId::class.java)
        if (username == null && password == null) return null
        return FormFields(username, password, intent.getStringExtra(EXTRA_ORIGIN))
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

    override fun unlockWithScreenLock() = Unit
    override fun canUseScreenLock(): Boolean = false
    override fun lock() = Unit
    override fun edit(entry: Entry?) = Unit
    override fun delete(entry: Entry) = Unit
    override fun save(
        entry: Entry?,
        origin: String,
        username: String,
        password: String,
        notes: String
    ) = Unit

    /** Backing out of the sheet has to be reported, or the caller waits forever. */
    override fun back() {
        setResult(
            Activity.RESULT_CANCELED,
            Intent().also {
                PendingIntentHandler.setGetCredentialException(
                    it,
                    GetCredentialCancellationException()
                )
            }
        )
        finish()
    }

    companion object {
        private const val TAG = "latch"
        private const val EXTRA_ENTRY_ID = "latch.entry_id"
        private const val EXTRA_ORIGIN = "latch.origin"
        private const val EXTRA_CREATE = "latch.create"
        private const val EXTRA_AUTOFILL_USERNAME = "latch.autofill.username"
        private const val EXTRA_AUTOFILL_PASSWORD = "latch.autofill.password"

        fun forGet(context: Context, entryId: String?, origin: String? = null): Intent =
            Intent(context, PasswordActivity::class.java)
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_ORIGIN, origin)

        /** Unlock-then-pick for a form the autofill service already identified. */
        fun forAutofill(context: Context, form: FormFields): Intent =
            Intent(context, PasswordActivity::class.java)
                .putExtra(EXTRA_AUTOFILL_USERNAME, form.username)
                .putExtra(EXTRA_AUTOFILL_PASSWORD, form.password)
                .putExtra(EXTRA_ORIGIN, form.domain)

        fun forCreate(context: Context, origin: String? = null): Intent =
            Intent(context, PasswordActivity::class.java)
                .putExtra(EXTRA_CREATE, true)
                .putExtra(EXTRA_ORIGIN, origin)
    }
}
