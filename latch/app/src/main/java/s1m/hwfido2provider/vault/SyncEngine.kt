package s1m.hwfido2provider.vault

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Syncthing, run as a subprocess and driven over its own REST API.
 *
 * Not "a Syncthing client" — the user never sees Syncthing at all. The
 * expensive part of this was learned elsewhere and transfers unchanged: a
 * CGO-free Go binary runs on Android as-is, and since API 29 an app may not
 * exec anything from its data directory, so it ships as `libsyncthing.so` and
 * runs out of `nativeLibraryDir`.
 */
object SyncEngine {
    private const val TAG = "latch"
    private const val BINARY = "libsyncthing.so"
    private const val HOME = "syncthing"
    private const val SOCKET = "syncthing.sock"

    /**
     * The API listens on a unix socket inside our sandbox, never on
     * 127.0.0.1:8384.
     *
     * Android has no per-uid loopback isolation: a server bound to loopback is
     * reachable by every app on the device. Syncthing's own Android client binds
     * TCP, and that would put an API which can add sync folders and read the
     * device id behind no boundary at all. A socket in filesDir is inside the
     * app sandbox, and nothing is bound to the network.
     */
    fun socket(context: Context): File = File(context.filesDir, SOCKET)

    fun home(context: Context): File = File(context.filesDir, HOME)

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, BINARY)

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    /**
     * Starts the daemon. The caller owns the process and must stop it; nothing
     * here supervises, because a foreground service is what keeps it alive and
     * Android is what decides when that ends.
     */
    fun start(context: Context): Process {
        val binary = binary(context)
        if (!binary.canExecute()) {
            throw IllegalStateException("no executable syncthing at $binary")
        }
        home(context).mkdirs()
        // A stale socket from a killed process would fail the bind; Syncthing
        // unlinks before binding, but only for a path it owns.
        socket(context).delete()

        Log.i(TAG, "sync: starting $binary")
        return ProcessBuilder(
            binary.absolutePath,
            "serve",
            "--no-browser",
            "--home", home(context).absolutePath
        )
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment()["STGUIADDRESS"] = "unix://" + socket(context).absolutePath
                // Nothing here should reach for a browser or a home directory
                // we do not own.
                pb.environment()["HOME"] = context.filesDir.absolutePath
                pb.environment()["STNOUPGRADE"] = "1"
            }
            .start()
    }

    fun stop(process: Process?) {
        process ?: return
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        Log.i(TAG, "sync: stopped")
    }
}
