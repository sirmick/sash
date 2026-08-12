package s1m.hwfido2provider.vault

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * Syncthing's REST API, over the unix socket.
 *
 * Android's `LocalSocket` rather than `java.net.UnixDomainSocketAddress`: it is
 * the platform's own filesystem-namespace unix socket and has been there
 * forever, so there is no API-level question to answer.
 *
 * HTTP is written by hand because the request is three lines and the response
 * is a JSON body — pulling in a client to speak to a socket inside our own
 * sandbox would be more moving parts than the protocol has.
 */
object SyncApi {
    private const val TAG = "latch"

    class Unavailable(message: String) : Exception(message)

    /** Syncthing's API key, generated into its own config on first run. */
    fun apiKey(context: Context): String? {
        val config = File(SyncEngine.home(context), "config.xml")
        if (!config.isFile) return null
        return Regex("<apikey>(.*?)</apikey>").find(config.readText())?.groupValues?.get(1)
    }

    fun status(context: Context): JSONObject = JSONObject(get(context, "/rest/system/status"))

    fun config(context: Context): JSONObject = JSONObject(get(context, "/rest/config"))

    /** This device's id — the thing a QR code carries when pairing. */
    fun deviceId(context: Context): String? =
        runCatching { status(context).getString("myID") }
            .onFailure { Log.d(TAG, "sync: no device id yet: ${it.message}") }
            .getOrNull()

    fun get(context: Context, path: String): String = request(context, "GET", path, null)

    fun post(context: Context, path: String, body: String): String =
        request(context, "POST", path, body)

    private fun indexOfCrlfCrlf(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) return i
        }
        return -1
    }

    /**
     * Reassembles a chunked body.
     *
     * Done on bytes rather than a decoded string: chunk lengths are byte
     * counts, and any multi-byte character straddling a chunk boundary would
     * put a string-based offset out of step with them.
     */
    private fun dechunk(body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(body.size)
        var i = 0
        while (i < body.size) {
            var eol = i
            while (eol < body.size - 1 && !(body[eol] == CR && body[eol + 1] == LF)) eol++
            val header = body.decodeToString(i, eol).substringBefore(';').trim()
            val size = header.toIntOrNull(16) ?: throw Unavailable("bad chunk length \"$header\"")
            if (size == 0) break
            val start = eol + 2
            if (start + size > body.size) throw Unavailable("truncated chunk")
            out.write(body, start, size)
            i = start + size + 2 // trailing CRLF
        }
        return out.toByteArray()
    }

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A

    fun request(context: Context, method: String, path: String, body: String?): String {
        val socketPath = SyncEngine.socket(context)
        if (!socketPath.exists()) throw Unavailable("syncthing is not running")
        val key = apiKey(context) ?: throw Unavailable("no api key yet")

        LocalSocket(LocalSocket.SOCKET_STREAM).use { socket ->
            socket.connect(
                LocalSocketAddress(socketPath.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
            )
            val payload = body?.toByteArray()
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                // Syncthing checks Host against its allowlist; "localhost" is
                // always accepted, and over a unix socket there is no real host.
                append("Host: localhost\r\n")
                append("X-API-Key: $key\r\n")
                append("Connection: close\r\n")
                if (payload != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                }
                append("\r\n")
            }
            socket.outputStream.write(head.toByteArray())
            payload?.let { socket.outputStream.write(it) }
            socket.outputStream.flush()

            val response = socket.inputStream.readBytes()
            val split = indexOfCrlfCrlf(response)
            if (split < 0) throw Unavailable("malformed response")
            val headers = response.decodeToString(0, split)
            val body = response.copyOfRange(split + 4, response.size)

            val status = headers.lineSequence().first()
            if (!status.contains(" 200")) throw Unavailable("$method $path -> $status")

            // Connection: close does NOT mean the body is unframed. Go's http
            // server buffers small responses and sets Content-Length, but
            // switches to chunked once the body outgrows that buffer — so
            // /rest/system/status parsed fine and /rest/config came back
            // starting "a3c", a chunk length, and failed to parse.
            val chunked = headers.lineSequence().any {
                it.startsWith("Transfer-Encoding:", true) && it.contains("chunked", true)
            }
            return (if (chunked) dechunk(body) else body).decodeToString()
        }
    }
}
