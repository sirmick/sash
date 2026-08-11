package s1m.hwfido2provider

import android.content.Context
import android.util.Log
import androidx.credentials.provider.CallingAppInfo
import java.security.MessageDigest

private const val TAG = "Utils"
private fun ByteArray.toColonHexFormat(): String {
    val colonHexFormat = HexFormat {
        upperCase = true
        bytes {
            byteSeparator = ":"
        }
    }
    return this.toHexString(colonHexFormat)
}

data class CallerOrigin(
    val caller: String,
    val origin: String?,
    val sigDigest: ByteArray
)

fun CallingAppInfo.getCallerOrigin(context: Context): CallerOrigin? {
    // Log SHA-256 hash of first signature of calling app
    val caller = this.packageName
    val sig = this.signingInfo.signingCertificateHistory.first()
    val sigDigest = MessageDigest.getInstance("SHA-256")
        .digest(sig.toByteArray())
    Log.d(TAG, "SHA256 digest of signature from $caller: ${sigDigest.toColonHexFormat()}")

    // TODO: We should always allow the default browser in the allowlist
    val jsonAllowlist = context.resources.openRawResource(R.raw.allowlist).bufferedReader().use { it.readText() }
    return runCatching {
        val origin = this.getOrigin(jsonAllowlist)
        CallerOrigin(caller, origin, sigDigest)
    }.getOrNull()
}
