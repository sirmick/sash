package s1m.hwfido2provider.vault

import android.app.PendingIntent
import android.content.Context
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import s1m.hwfido2provider.PasswordActivity

/**
 * The password half of the credential provider.
 *
 * Kept out of upstream's ProviderService so the passkey path stays exactly as
 * it was, and so the fork's diff against upstream is a handful of call sites
 * rather than a rewritten file.
 */
object PasswordEntries {

    /**
     * Entries to offer for a password request.
     *
     * When the vault is locked we cannot enumerate anything, so we offer a
     * single entry that unlocks — rather than reporting no credentials, which
     * would read to the user as "latch has nothing for this site" when in fact
     * it has not been asked.
     */
    fun forGet(context: Context, request: BeginGetCredentialRequest, origin: String?): List<CredentialEntry> {
        val option = request.beginGetCredentialOptions
            .filterIsInstance<BeginGetPasswordOption>()
            .firstOrNull() ?: return emptyList()

        val vault = VaultManager.require(context)
            ?: return listOf(entry(context, LOCKED_LABEL, null, option))

        return matching(vault, origin).map { entry(context, it.username, it.id, option) }
    }

    /**
     * Credentials plausibly for [origin], newest first.
     *
     * Matching is host-suffix, so a credential saved for `chase.com` is offered
     * on `secure01a.chase.com`. A caller with no origin — an app rather than a
     * browser — gets everything and picks, because we hold no mapping from
     * package names to sites and guessing one would be worse than asking.
     */
    fun matching(vault: Vault, origin: String?): List<Entry> {
        val live = vault.list().filterNot { it.deleted }
        val host = origin?.let { hostOf(it) } ?: return live.sortedBy { it.origin }
        return live
            .filter { host == it.origin || host.endsWith("." + it.origin) }
            .sortedByDescending { it.modified }
    }

    fun forCreate(context: Context, request: BeginCreateCredentialRequest): CreateEntry? {
        if (request.type != TYPE_PASSWORD) return null
        return CreateEntry(
            accountName = context.getString(s1m.hwfido2provider.R.string.latch_title),
            pendingIntent = pendingIntent(context, PasswordActivity.forCreate(context))
        )
    }

    private fun entry(
        context: Context,
        username: String,
        entryId: String?,
        option: BeginGetPasswordOption
    ) = PasswordCredentialEntry.Builder(
        context,
        username = username,
        pendingIntent = pendingIntent(context, PasswordActivity.forGet(context, entryId)),
        beginGetPasswordOption = option
    ).build()

    private fun pendingIntent(context: Context, intent: android.content.Intent) =
        PendingIntent.getActivity(
            context,
            nextRequestCode++,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun hostOf(origin: String): String =
        runCatching { java.net.URI(origin).host ?: origin }.getOrDefault(origin)
            .removePrefix("www.")

    const val TYPE_PASSWORD = "android.credentials.TYPE_PASSWORD_CREDENTIAL"
    private const val LOCKED_LABEL = "Unlock to see passwords"
    private var nextRequestCode = 1000
}
