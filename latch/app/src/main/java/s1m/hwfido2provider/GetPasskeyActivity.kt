package s1m.hwfido2provider

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import com.google.android.gms.fido.Fido
import com.google.android.gms.fido.fido2.api.common.AuthenticatorAssertionResponse
import com.google.android.gms.fido.fido2.api.common.AuthenticatorErrorResponse
import com.google.android.gms.fido.fido2.api.common.BrowserPublicKeyCredentialRequestOptions
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential as MPublicKeyCredential
import kotlin.also
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.ExperimentalSerializationApi
import org.json.JSONException
import org.json.JSONObject
import org.microg.gms.auth.credentials.provider.parsePublicKeyCredentialRequestOptions
import org.microg.gms.auth.credentials.provider.toJson
import org.microg.gms.common.GmsService
import org.microg.gms.fido.core.transport.Transport
import org.microg.gms.fido.core.transport.TransportHandlerCallback
import org.microg.gms.fido.core.ui.AuthenticatorActivity
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_ALLOW_INSTANT
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_CALLER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_CREDENTIAL_ID
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_OPTIONS
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_PRESELECTED_TRANSPORT
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_SERVICE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_SOURCE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_TYPE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_APP
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_BROWSER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_SIGN

class GetPasskeyActivity :
    AppCompatActivity(),
    TransportHandlerCallback {

    sealed class GetOptions {
        abstract val requestJson: String
        abstract val clientDataHash: ByteArray?
        abstract val callingAppInfo: CallingAppInfo

        class BeginGet(opt: BeginGetPublicKeyCredentialOption, override val callingAppInfo: CallingAppInfo) : GetOptions() {
            override val requestJson: String = opt.requestJson
            override val clientDataHash: ByteArray? = opt.clientDataHash
        }
        class ProviderGet(opt: GetPublicKeyCredentialOption, override val callingAppInfo: CallingAppInfo) : GetOptions() {
            override val requestJson: String = opt.requestJson
            override val clientDataHash: ByteArray? = opt.clientDataHash
        }

        companion object {
            fun retrieve(intent: Intent): GetOptions? = PendingIntentHandler
                .retrieveProviderGetCredentialRequest(intent)
                ?.let {
                    val opt = it.credentialOptions.firstOrNull() as? GetPublicKeyCredentialOption
                        ?: return null
                    ProviderGet(opt, it.callingAppInfo)
                } ?: PendingIntentHandler
                .retrieveBeginGetCredentialRequest(intent)
                ?.let {
                    val opt = it.beginGetCredentialOptions.firstOrNull() as? BeginGetPublicKeyCredentialOption
                        ?: return null
                    val callingAppInfo = it.callingAppInfo ?: return null
                    BeginGet(opt, callingAppInfo)
                }
        }
    }

    @OptIn(ExperimentalEncodingApi::class, ExperimentalSerializationApi::class)
    private val getResult =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (it.resultCode == RESULT_OK) {
                val responseData: ByteArray =
                    it.data?.getByteArrayExtra(Fido.FIDO2_KEY_CREDENTIAL_EXTRA)
                        ?: run {
                            Log.w(TAG, "Failed to get FIDO response in result")
                            return@registerForActivityResult finishCanceled(getString(R.string.toast_failed_to_get_response))
                        }

                val pk = MPublicKeyCredential.deserializeFromBytes(responseData)

                try {
                    when (pk.response) {
                        is AuthenticatorAssertionResponse -> {
                            val responseIntent = Intent()
                            PendingIntentHandler.setGetCredentialResponse(
                                responseIntent,
                                GetCredentialResponse(PublicKeyCredential(pk.toJson()))
                            )
                            setResult(RESULT_OK, responseIntent)
                            finish()
                        }

                        is AuthenticatorErrorResponse -> {
                            val msg = (pk.response as AuthenticatorErrorResponse).errorMessage ?: "Unknown error"
                            Log.w(TAG, "An error occurred: $msg")
                            finishCanceled(msg)
                        }

                        else -> {
                            Log.w(TAG, "PublicKeyCredential didn't succeed")
                            finishCanceled(getString(R.string.toast_failed_to_get_response))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "An error occurred", e)
                    finishCanceled(e.message ?: getString(R.string.toast_failed_to_get_response))
                }
            } else {
                Log.w(TAG, "Failed to get result")
                finishCanceled(getString(R.string.toast_failed_to_get_response))
            }
        }

    @OptIn(ExperimentalEncodingApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        val requestOpt = GetOptions.retrieve(intent) ?: run {
            Log.w(TAG, "Failed to get request options")
            return finishCanceled(getString(R.string.toast_cannot_parse_request))
        }

        // origin may be null if it doesn't come from a browser
        // Note that we can have the origin without the json allowlist here with request.origin
        val (caller, origin) = requestOpt.callingAppInfo.getCallerOrigin(this) ?: run {
            Log.w(TAG, "Requested from unauthorized browser")
            return finishCanceled(getString(R.string.toast_unauthorized_browser))
        }

        // It is set and non-null if the user select an entry
        val credentialId = intent.extras?.getString(EXTRA_CREDENTIAL_ID)
        val preselectedTransport = intent.extras?.getString(EXTRA_PRESELECTED_TRANSPORT)

        val requestObject = JSONObject(requestOpt.requestJson)

        val requestOptions = try {
            requestObject.parsePublicKeyCredentialRequestOptions()
        } catch (_: JSONException) {
            Log.w(TAG, "Failed to get requestOptions from requestObject in intent")
            return finishCanceled(getString(R.string.toast_cannot_parse_request))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get requestOptions from requestObject in intent", e)
            return finishCanceled(getString(R.string.toast_cannot_parse_request))
        }

        val (options, source) = if (origin?.startsWith("https://") == true) {
            val browserOptions =
                BrowserPublicKeyCredentialRequestOptions.Builder()
                    .setPublicKeyCredentialRequestOptions(requestOptions)
                    .setOrigin(origin.toUri())
                    .apply {
                        requestOpt.clientDataHash?.let {
                            setClientDataHash(it)
                        }
                    }
                    .build()
            browserOptions.serializeToBytes() to SOURCE_BROWSER
        } else {
            requestOptions.serializeToBytes() to SOURCE_APP
        }

        // cf. https://github.com/microg/GmsCore/blob/352f2d72fa52c6c3c4fdd79d575a071a0da72ad1/play-services-fido/core/src/main/kotlin/org/microg/gms/fido/core/privileged/Fido2PrivilegedService.kt#L73
        val intent = Intent(this, AuthenticatorActivity::class.java)
            .putExtra(KEY_SERVICE, GmsService.FIDO2_API.SERVICE_ID)
            .putExtra(KEY_SOURCE, source)
            .putExtra(KEY_TYPE, TYPE_SIGN)
            .putExtra(KEY_OPTIONS, options)
            .putExtra(KEY_CALLER, caller)
            .also { i ->
                credentialId?.let {
                    i.putExtra(KEY_CREDENTIAL_ID, it)
                        .putExtra(KEY_ALLOW_INSTANT, AuthenticatorActivity.AllowedInstantLevel.INSTANT.ordinal)
                } ?: run {
                    preselectedTransport?.let {
                        i.putExtra(KEY_PRESELECTED_TRANSPORT, it)
                            .putExtra(KEY_ALLOW_INSTANT, AuthenticatorActivity.AllowedInstantLevel.PRESELECT.ordinal)
                    } ?: run {
                        i.putExtra(KEY_ALLOW_INSTANT, AuthenticatorActivity.AllowedInstantLevel.NONE.ordinal)
                    }
                }
            }

        getResult.launch(intent)
    }

    /**
     * We always show a toast if the activity fail
     */
    private fun finishCanceled(toastMsg: String) {
        Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onStatusChanged(
        transport: org.microg.gms.fido.core.transport.Transport,
        status: String,
        extras: Bundle?
    ) {
        Log.d(TAG, "onStatusChanged: $transport, $status")
    }

    companion object {
        private const val TAG = "GetPasskeyActivity"
        const val EXTRA_CREDENTIAL_ID = "allowCredentials"
        const val EXTRA_PRESELECTED_TRANSPORT = "transport"

        /**
         * @param credential: internal credential to use, base64 encoded
         */
        fun createIntentToSelectKey(
            context: Context,
            credential: String?,
            transport: Transport?
        ): Intent = Intent(context, GetPasskeyActivity::class.java).apply {
            `package` = context.packageName
            credential?.let {
                putExtra(EXTRA_CREDENTIAL_ID, credential)
            }
            transport?.let {
                putExtra(EXTRA_PRESELECTED_TRANSPORT, transport.toString())
            }
        }
    }
}
