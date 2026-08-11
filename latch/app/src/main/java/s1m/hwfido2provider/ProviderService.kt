package s1m.hwfido2provider

import android.app.PendingIntent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.provider.Action
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.provider.RemoteEntry
import com.google.android.gms.fido.common.Transport
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialType
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialUserEntity
import kotlin.io.encoding.Base64
import org.json.JSONObject
import org.microg.gms.auth.credentials.provider.parsePublicKeyCredentialRequestOptions
import org.microg.gms.fido.core.Database
import org.microg.gms.fido.core.R as microgR
import org.microg.gms.fido.core.transport.Transport as CoreTransport
import s1m.hwfido2provider.migration.Migrations

class ProviderService : CredentialProviderService() {

    override fun onCreate() {
        super.onCreate()
        // We ensure microg lib uses embedded implementation
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit { putString(PREF_TARGET, packageName) }
        Migrations(this).run()
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        Log.d(TAG, "CreateCreds")

        val intent = CreatePasskeyActivity.createIntentToCreateKey(this)
        val pendingIntent = PendingIntent.getActivity(
            this,
            pendingIntentId++,
            intent,
            PendingIntent.FLAG_MUTABLE + PendingIntent.FLAG_UPDATE_CURRENT
        )
        callback.onResult(
            BeginCreateCredentialResponse(
                createEntries = listOf(
                    CreateEntry(
                        accountName = getString(R.string.credential_entry),
                        pendingIntent = pendingIntent
                    )
                ),
                remoteEntry = RemoteEntry(pendingIntent)
            )
        )
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        Log.d(TAG, "GetCreds")
        // The origin can be null if it doesn't come from a Browser
        val callerOrigin = request.callingAppInfo?.getCallerOrigin(this) ?: run {
            Log.w(TAG, "Failed to get callingAppInfo")
            Toast.makeText(this, getString(R.string.toast_unauthorized_browser), Toast.LENGTH_LONG).show()
            callback.onError(GetCredentialUnsupportedException())
            return
        }
        Log.d(TAG, "Get credential options: ${request.beginGetCredentialOptions}")

        val entry = request.beginGetCredentialOptions
            .firstOrNull { it is BeginGetPublicKeyCredentialOption }
            as BeginGetPublicKeyCredentialOption?
        val requestJson = entry?.requestJson ?: run {
            Log.w(TAG, "Failed to get request json")
            callback.onError(GetCredentialUnknownException())
            return
        }

        // actionPendingIntent is the default action, if the user doesn't have
        // a device-bound credential, or if they select "Sign-in options"
        val actionIntent = GetPasskeyActivity.createIntentToSelectKey(this, null, null)
        val actionPendingIntent = PendingIntent.getActivity(
            this,
            pendingIntentId++,
            actionIntent,
            PendingIntent.FLAG_MUTABLE + PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Then, for each device-bound credential compatible with the request,
        // we offer a dedicated entry
        val requestObject = JSONObject(requestJson)
        val request = try {
            requestObject.parsePublicKeyCredentialRequestOptions()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get request from json", e)
            callback.onError(GetCredentialUnknownException())
            return
        }

        Log.d(TAG, "Request allowList: ${request.allowList?.map { r -> base64.encode(r.id) }}")
        val db = Database(this)
        val knownEntries = db.getKnownRegistrationInfo(request.rpId).mapNotNull {
            // knownEntries must contain SCREEN_LOCK/INTERNAL credentials only
            if (it.transport != org.microg.gms.fido.core.transport.Transport.SCREEN_LOCK) {
                return@mapNotNull null
            }

            val id = base64.decode(it.credential)

            // Check if the key is in the allowList, or if the allowList is empty (= allow any)
            if (request.allowList.isNullOrEmpty()) {
                Log.d(TAG, "Request allowList is null or empty: accepting credential ${it.credential}")
            } else if (request.allowList.orEmpty().none { r ->
                    r.id.contentEquals(id) &&
                        r.type == PublicKeyCredentialType.PUBLIC_KEY &&
                        // Note: the current known registration is necessary internal, cf. top of the mapNotNull
                        r.transports?.contains(Transport.INTERNAL) ?: true
                }
            ) {
                Log.d(TAG, "No key found for the request")
                return@mapNotNull null
            }

            val intent = GetPasskeyActivity.createIntentToSelectKey(this, it.credential, null)
            val pendingIntent = PendingIntent.getActivity(
                this,
                pendingIntentId++,
                intent,
                PendingIntent.FLAG_MUTABLE + PendingIntent.FLAG_UPDATE_CURRENT
            )
            PublicKeyCredentialEntry.Builder(
                this,
                username = PublicKeyCredentialUserEntity.parseJson(it.userJson).displayName,
                pendingIntent = pendingIntent,
                beginGetPublicKeyCredentialOption = entry
            ).build()
        }.toMutableList()

        // Base64-encoded digest of the signature, the same format as microG does
        val signatureDigest = Base64.encode(callerOrigin.sigDigest) + "\u000a"
        if (
            callerOrigin.origin == null ||
            !callerOrigin.origin.startsWith("https://") ||
            db.isPrivileged(callerOrigin.caller, signatureDigest)
        ) {
            Log.d(TAG, "Transport can be selected without user ack (privileged app or non-browser login)")
            val store = Store(this)

            // We add NFC and USB entries only if the request doesn't contain an allowList where all elements transports are internal or hybrid
            val isInternalOnly = !request.allowList.isNullOrEmpty() &&
                request.allowList!!.all { cred ->
                    !cred.transports.isNullOrEmpty() && cred.transports!!.all { tr -> tr == Transport.HYBRID || tr == Transport.INTERNAL }
                }
            if (!isInternalOnly) {
                if (store.entryNfc) {
                    val intent = GetPasskeyActivity.createIntentToSelectKey(this, null, CoreTransport.NFC)
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        pendingIntentId++,
                        intent,
                        PendingIntent.FLAG_MUTABLE + PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    knownEntries.add(
                        PublicKeyCredentialEntry.Builder(
                            this,
                            username = getString(microgR.string.fido_transport_selection_nfc),
                            pendingIntent = pendingIntent,
                            beginGetPublicKeyCredentialOption = entry
                        ).build()
                    )
                }
                if (store.entryUsb) {
                    val intent = GetPasskeyActivity.createIntentToSelectKey(this, null, CoreTransport.USB)
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        pendingIntentId++,
                        intent,
                        PendingIntent.FLAG_MUTABLE + PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    knownEntries.add(
                        PublicKeyCredentialEntry.Builder(
                            this,
                            username = getString(microgR.string.fido_transport_selection_usb),
                            pendingIntent = pendingIntent,
                            beginGetPublicKeyCredentialOption = entry
                        ).build()
                    )
                }
            }
            if (store.entryHybrid) {
                val intent = GetPasskeyActivity.createIntentToSelectKey(this, null, CoreTransport.HYBRID)
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    pendingIntentId++,
                    intent,
                    PendingIntent.FLAG_MUTABLE + PendingIntent.FLAG_UPDATE_CURRENT
                )
                knownEntries.add(
                    PublicKeyCredentialEntry.Builder(
                        this,
                        username = getString(microgR.string.fido_transport_selection_hybrid),
                        pendingIntent = pendingIntent,
                        beginGetPublicKeyCredentialOption = entry
                    ).build()
                )
            }
        } else {
            Log.d(TAG, "Need user to grant privileges to select transport (browser login, for non-privileged app)")
        }

        // The generic entries is shown only if we don't have a device-bound credential
        val genericEntries = listOf(
            PublicKeyCredentialEntry.Builder(
                this,
                username = getString(R.string.credential_entry),
                pendingIntent = actionPendingIntent,
                beginGetPublicKeyCredentialOption = entry
            ).build()
        )

        callback.onResult(
            BeginGetCredentialResponse(
                credentialEntries = knownEntries.ifEmpty { genericEntries },
                actions = if (knownEntries.isNotEmpty()) {
                    listOf(
                        Action(
                            title = getString(R.string.credential_entry),
                            pendingIntent = actionPendingIntent,
                            subtitle = getString(R.string.app_name)
                        )
                    )
                } else {
                    listOf()
                },
                remoteEntry = null
            )
        )
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        Log.d(TAG, "ClearCreds - Not yet implemented")
    }

    companion object {
        private var pendingIntentId = 1
        private const val TAG = "ProviderService"
        private const val PREF_NAME = "org.microg.gms_connection"
        private const val PREF_TARGET = "target"
    }
}
