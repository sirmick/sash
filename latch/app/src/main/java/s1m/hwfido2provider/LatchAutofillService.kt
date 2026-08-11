package s1m.hwfido2provider

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import s1m.hwfido2provider.vault.Entry
import s1m.hwfido2provider.vault.FormFields
import s1m.hwfido2provider.vault.FormScanner
import s1m.hwfido2provider.vault.PasswordEntries
import s1m.hwfido2provider.vault.VaultManager

/**
 * Filling passwords into web forms.
 *
 * This is the path that matters for pane. GeckoView implements the Android
 * autofill framework, so a login form inside an origin-locked pane app is filled
 * by exactly the same mechanism as one in any browser — there is nothing to
 * inject and no engine hook to maintain.
 *
 * The match is on the **web domain**, never the package name: every pane app
 * reports `com.pane`, so the package says nothing about which site is on screen.
 */
class LatchAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null)
            return
        }

        // Never autofill ourselves. Observed, not anticipated: the first run
        // offered to save the vault's own master passphrase into the vault it
        // protects. Our unlock screen is a password field like any other, and
        // the framework cannot know the difference.
        if (structure.activityComponent?.packageName == packageName) {
            callback.onSuccess(null)
            return
        }

        val form = FormScanner.scan(structure, request.fillContexts.lastOrNull()?.focusedId)
        if (!form.fillable) {
            // Logged rather than returned silently: "no fields found" and "not
            // called at all" look identical from outside, and cost an hour once.
            Log.d(TAG, "autofill: no fillable fields in ${structure.activityComponent}")
            if (Log.isLoggable(TAG, Log.VERBOSE)) FormScanner.describe(structure)
            callback.onSuccess(null)
            return
        }
        Log.d(TAG, "autofill: form on ${form.domain ?: "(no domain)"}")

        val response = FillResponse.Builder()
        // Offer to save whatever the user types, even when we have nothing to
        // fill. A vault that only ever fills is a vault nobody fills.
        response.setSaveInfo(
            SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                form.ids
            ).setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build()
        )

        val vault = VaultManager.require(this)
        if (vault == null) {
            // Locked: one entry that unlocks. Reporting nothing would read as
            // "latch has no password for this site" when it has not been asked.
            response.setAuthentication(
                form.ids,
                unlockIntent(form).intentSender,
                remoteView(getString(R.string.latch_autofill_unlock))
            )
            callback.onSuccess(response.build())
            return
        }

        val matches = PasswordEntries.matching(vault, form.domain)
        if (matches.isEmpty()) {
            Log.d(TAG, "autofill: nothing stored for ${form.domain}")
            callback.onSuccess(response.build())
            return
        }
        matches.forEach { response.addDataset(dataset(it, form)) }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onFailure(null)
            return
        }
        if (structure.activityComponent?.packageName == packageName) {
            callback.onFailure(null)
            return
        }
        val form = FormScanner.scan(structure)
        val vault = VaultManager.require(this) ?: run {
            // Saving needs the key. Failing loudly beats silently discarding a
            // password the user believes was captured.
            callback.onFailure(getString(R.string.latch_autofill_locked_save))
            return
        }

        val username = valueOf(structure, form.username).orEmpty()
        val password = valueOf(structure, form.password).orEmpty()
        if (password.isEmpty()) {
            callback.onFailure(null)
            return
        }

        val origin = form.domain?.removePrefix("www.").orEmpty()
        val existing = vault.list().firstOrNull {
            !it.deleted && it.origin == origin && it.username == username
        }
        if (existing == null) {
            vault.create(origin, username, password)
        } else {
            // Same site and username with a new password is a rotation, so the
            // old one is retired into history rather than overwritten.
            vault.update(existing, password = password)
        }
        Log.i(TAG, "autofill: saved credential for ${origin.ifEmpty { "(no domain)" }}")
        callback.onSuccess()
    }

    private fun dataset(entry: Entry, form: FormFields): Dataset {
        val builder = Dataset.Builder(remoteView("${entry.username} — ${entry.origin}"))
        form.username?.let { builder.setValue(it, AutofillValue.forText(entry.username)) }
        form.password?.let { builder.setValue(it, AutofillValue.forText(entry.password)) }
        return builder.build()
    }

    private fun unlockIntent(form: FormFields): PendingIntent = PendingIntent.getActivity(
        this,
        UNLOCK_REQUEST,
        PasswordActivity.forAutofill(this, form),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun remoteView(text: String) =
        RemoteViews(packageName, R.layout.latch_autofill_entry).apply {
            setTextViewText(R.id.latch_autofill_text, text)
        }

    private fun valueOf(structure: AssistStructure, id: android.view.autofill.AutofillId?): String? {
        if (id == null) return null
        for (i in 0 until structure.windowNodeCount) {
            find(structure.getWindowNodeAt(i).rootViewNode, id)?.let { node ->
                return node.autofillValue?.takeIf { it.isText }?.textValue?.toString()
            }
        }
        return null
    }

    private fun find(node: AssistStructure.ViewNode, id: android.view.autofill.AutofillId): AssistStructure.ViewNode? {
        if (node.autofillId == id) return node
        for (i in 0 until node.childCount) {
            find(node.getChildAt(i), id)?.let { return it }
        }
        return null
    }

    companion object {
        private const val TAG = "latch"
        private const val UNLOCK_REQUEST = 2000
    }
}
