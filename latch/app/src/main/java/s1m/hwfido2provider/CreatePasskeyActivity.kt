package s1m.hwfido2provider

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import com.google.android.gms.fido.Fido
import com.google.android.gms.fido.fido2.api.common.AuthenticatorAttestationResponse
import com.google.android.gms.fido.fido2.api.common.AuthenticatorErrorResponse
import com.google.android.gms.fido.fido2.api.common.BrowserPublicKeyCredentialCreationOptions
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.ExperimentalSerializationApi
import org.json.JSONException
import org.json.JSONObject
import org.microg.gms.auth.credentials.provider.parsePublicKeyCredentialCreationOptions
import org.microg.gms.auth.credentials.provider.toJson
import org.microg.gms.common.GmsService
import org.microg.gms.fido.core.ui.AuthenticatorActivity
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_CALLER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_OPTIONS
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_SERVICE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_SOURCE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_TYPE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_APP
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_BROWSER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_REGISTER

class CreatePasskeyActivity : AppCompatActivity() {
    @SuppressLint("RestrictedApi")
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

                val pk = PublicKeyCredential.deserializeFromBytes(responseData)

                try {
                    when (pk.response) {
                        is AuthenticatorAttestationResponse -> {
                            val responseIntent = Intent()
                            PendingIntentHandler.setCreateCredentialResponse(responseIntent, CreatePublicKeyCredentialResponse(pk.toJson()))
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

        val providerRequest = PendingIntentHandler
            .retrieveProviderCreateCredentialRequest(intent)

        val request = providerRequest
            ?.callingRequest
            ?.let { it as CreatePublicKeyCredentialRequest? }
            ?: run {
                Log.w(TAG, "Failed to get request from intent")
                return finishCanceled(getString(R.string.toast_cannot_parse_request))
            }

        // origin may be null if it doesn't come from a browser
        // Note that we can have the origin without the json allowlist here with request.origin
        val callerOrigin = providerRequest.callingAppInfo.getCallerOrigin(this) ?: run {
            Log.w(TAG, "Requested from unauthorized browser")
            return finishCanceled(getString(R.string.toast_unauthorized_browser))
        }

        val createRequestObject = JSONObject(request.requestJson)
        val requestOptions = try {
            createRequestObject.parsePublicKeyCredentialCreationOptions()
        } catch (_: JSONException) {
            Log.w(TAG, "Failed to get requestOptions from createRequestObject in intent, json: ${request.requestJson}")
            return finishCanceled(getString(R.string.toast_cannot_parse_request))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get requestOptions from createRequestObject in intent, json: ${request.requestJson}", e)
            return finishCanceled(getString(R.string.toast_cannot_parse_request))
        }

        val (options, source) = if (callerOrigin.origin?.startsWith("https://") == true) {
            val browserOptions =
                BrowserPublicKeyCredentialCreationOptions.Builder()
                    .setPublicKeyCredentialCreationOptions(requestOptions)
                    .setOrigin(callerOrigin.origin.toUri())
                    .apply {
                        request.clientDataHash?.let {
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
            .putExtra(KEY_TYPE, TYPE_REGISTER)
            .putExtra(KEY_OPTIONS, options)
            .putExtra(KEY_CALLER, callerOrigin.caller)

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

    companion object {
        private const val TAG = "CreatePasskeyActivity"

        fun createIntentToCreateKey(context: Context): Intent = Intent(context, CreatePasskeyActivity::class.java).apply {
            `package` = context.packageName
        }
    }
}
