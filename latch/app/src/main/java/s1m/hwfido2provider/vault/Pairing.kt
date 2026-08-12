package s1m.hwfido2provider.vault

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pairing by QR, without latch containing a camera.
 *
 * One device shows a code, the other scans it **with the system camera app**,
 * which opens the `latch://pair` link and hands it to us. That is the sanctioned
 * Android route and it costs nothing: no camera permission, no CameraX, no
 * preview surface, and no second decoder to keep working. `PACK.md` already
 * assumed the camera app is the scanner, since GrapheneOS ships one.
 */
object Pairing {
    const val SCHEME = "latch"
    const val HOST = "pair"

    /** What the QR encodes: who to pair with, and optionally where to find them. */
    fun uri(deviceId: String, address: String?): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("id", deviceId)
            .apply { address?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("addr", it) } }
            .build()
            .toString()

    /** The device id and address carried by a scanned link, if it is one of ours. */
    fun parse(uri: Uri?): Pair<String, String?>? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val id = uri.getQueryParameter("id")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return id to uri.getQueryParameter("addr")?.trim()
    }

    /**
     * Renders [content] as a QR bitmap.
     *
     * Error correction is set high rather than low: this gets scanned off a
     * phone screen, at an angle, often with a fingerprint on the glass.
     */
    fun qr(content: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 2
            )
        )
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}

/** A peer, as the sync screen shows it. */
data class Peer(
    val id: String,
    val name: String,
    val connected: Boolean,
    val address: String
) {
    val shortId: String get() = id.take(7)
}
